/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.query.groupby;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Supplier;
import com.google.common.collect.Lists;
import com.google.inject.Inject;
import org.apache.druid.error.DruidException;
import org.apache.druid.frame.Frame;
import org.apache.druid.frame.allocation.MemoryAllocatorFactory;
import org.apache.druid.frame.segment.FrameCursorUtils;
import org.apache.druid.frame.write.FrameWriterFactory;
import org.apache.druid.frame.write.FrameWriterUtils;
import org.apache.druid.frame.write.FrameWriters;
import org.apache.druid.guice.annotations.Merging;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.Pair;
import org.apache.druid.java.util.common.granularity.Granularity;
import org.apache.druid.java.util.common.guava.MappedSequence;
import org.apache.druid.java.util.common.guava.Sequence;
import org.apache.druid.java.util.common.guava.Sequences;
import org.apache.druid.java.util.common.io.Closer;
import org.apache.druid.query.CacheStrategy;
import org.apache.druid.query.DataSource;
import org.apache.druid.query.FrameSignaturePair;
import org.apache.druid.query.IterableRowsCursorHelper;
import org.apache.druid.query.Query;
import org.apache.druid.query.QueryContexts;
import org.apache.druid.query.QueryDataSource;
import org.apache.druid.query.QueryPlus;
import org.apache.druid.query.QueryResourceId;
import org.apache.druid.query.QueryRunner;
import org.apache.druid.query.QueryToolChest;
import org.apache.druid.query.SubqueryQueryRunner;
import org.apache.druid.query.aggregation.AggregatorFactory;
import org.apache.druid.query.aggregation.MetricManipulationFn;
import org.apache.druid.query.aggregation.MetricManipulatorFns;
import org.apache.druid.query.cache.CacheKeyBuilder;
import org.apache.druid.query.context.ResponseContext;
import org.apache.druid.query.dimension.DefaultDimensionSpec;
import org.apache.druid.query.dimension.DimensionSpec;
import org.apache.druid.query.extraction.ExtractionFn;
import org.apache.druid.segment.Cursor;
import org.apache.druid.segment.DimensionHandlerUtils;
import org.apache.druid.segment.column.ColumnType;
import org.apache.druid.segment.column.NullableTypeStrategy;
import org.apache.druid.segment.column.RowSignature;
import org.apache.druid.segment.column.ValueType;
import org.apache.druid.segment.nested.StructuredData;
import org.joda.time.DateTime;

import javax.annotation.Nullable;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.BinaryOperator;

/**
 * Toolchest for GroupBy queries
 */
public class GroupByQueryQueryToolChest extends QueryToolChest<ResultRow, GroupByQuery>
{
  private static final byte GROUPBY_QUERY = 0x14;
  private static final TypeReference<Object> OBJECT_TYPE_REFERENCE =
      new TypeReference<>() {};
  private static final TypeReference<ResultRow> TYPE_REFERENCE = new TypeReference<>() {};

  private final GroupingEngine groupingEngine;
  private final GroupByQueryConfig queryConfig;
  private final GroupByQueryMetricsFactory queryMetricsFactory;
  private final GroupByResourcesReservationPool groupByResourcesReservationPool;
  private final GroupByStatsProvider groupByStatsProvider;

  @VisibleForTesting
  public GroupByQueryQueryToolChest(
      GroupingEngine groupingEngine,
      GroupByResourcesReservationPool groupByResourcesReservationPool
  )
  {
    this(
        groupingEngine,
        GroupByQueryConfig::new,
        DefaultGroupByQueryMetricsFactory.instance(),
        groupByResourcesReservationPool,
        new GroupByStatsProvider()
    );
  }

  @VisibleForTesting
  public GroupByQueryQueryToolChest(
      GroupingEngine groupingEngine,
      GroupByResourcesReservationPool groupByResourcesReservationPool,
      GroupByStatsProvider groupByStatsProvider
  )
  {
    this(
        groupingEngine,
        GroupByQueryConfig::new,
        DefaultGroupByQueryMetricsFactory.instance(),
        groupByResourcesReservationPool,
        groupByStatsProvider
    );
  }

  @Inject
  public GroupByQueryQueryToolChest(
      GroupingEngine groupingEngine,
      Supplier<GroupByQueryConfig> queryConfigSupplier,
      GroupByQueryMetricsFactory queryMetricsFactory,
      @Merging GroupByResourcesReservationPool groupByResourcesReservationPool,
      GroupByStatsProvider groupByStatsProvider
  )
  {
    this.groupingEngine = groupingEngine;
    this.queryConfig = queryConfigSupplier.get();
    this.queryMetricsFactory = queryMetricsFactory;
    this.groupByResourcesReservationPool = groupByResourcesReservationPool;
    this.groupByStatsProvider = groupByStatsProvider;
  }

  @Override
  public QueryRunner<ResultRow> mergeResults(final QueryRunner<ResultRow> runner)
  {
    return mergeResults(runner, true);
  }


  @Override
  public QueryRunner<ResultRow> mergeResults(final QueryRunner<ResultRow> runner, boolean willMergeRunner)
  {
    return (queryPlus, responseContext) -> {
      if (queryPlus.getQuery().context().isBySegment()) {
        return runner.run(queryPlus, responseContext);
      }

      final GroupByQuery groupByQuery = (GroupByQuery) queryPlus.getQuery();
      return initAndMergeGroupByResults(groupByQuery, runner, responseContext, willMergeRunner);
    };
  }

  @Override
  public BinaryOperator<ResultRow> createMergeFn(Query<ResultRow> query)
  {
    return groupingEngine.createMergeFn(query);
  }

  @Override
  public Comparator<ResultRow> createResultComparator(Query<ResultRow> query)
  {
    return groupingEngine.createResultComparator(query);
  }

  private Sequence<ResultRow> initAndMergeGroupByResults(
      final GroupByQuery query,
      QueryRunner<ResultRow> runner,
      ResponseContext context,
      boolean willMergeRunner
  )
  {
    // Reserve the group by resources (merge buffers) required for executing the query
    final QueryResourceId queryResourceId = query.context().getQueryResourceId();
    final GroupByStatsProvider.PerQueryStats perQueryStats =
        groupByStatsProvider.getPerQueryStatsContainer(query.context().getQueryResourceId());

    groupByResourcesReservationPool.reserve(
        queryResourceId,
        query,
        willMergeRunner,
        perQueryStats
    );

    final GroupByQueryResources resource = groupByResourcesReservationPool.fetch(queryResourceId);
    if (resource == null) {
      throw DruidException.defensive(
          "Did not associate any resources with the given query resource id [%s]",
          queryResourceId
      );
    }
    try {
      Closer closer = Closer.create();

      final Sequence<ResultRow> mergedSequence = mergeGroupByResults(
          query,
          resource,
          runner,
          context,
          closer,
          perQueryStats
      );

      // Clean up the resources reserved during the execution of the query
      closer.register(() -> groupByResourcesReservationPool.clean(queryResourceId));
      closer.register(() -> groupByStatsProvider.closeQuery(query.context().getQueryResourceId()));
      return Sequences.withBaggage(mergedSequence, closer);
    }
    catch (Exception e) {
      // Error creating the Sequence; release resources.
      resource.close();
      throw e;
    }
  }

  private Sequence<ResultRow> mergeGroupByResults(
      final GroupByQuery query,
      GroupByQueryResources resource,
      QueryRunner<ResultRow> runner,
      ResponseContext context,
      Closer closer,
      GroupByStatsProvider.PerQueryStats perQueryStats
  )
  {
    if (isNestedQueryPushDown(query)) {
      return mergeResultsWithNestedQueryPushDown(query, resource, runner, context, perQueryStats);
    }
    return mergeGroupByResultsWithoutPushDown(query, resource, runner, context, closer, perQueryStats);
  }

  private Sequence<ResultRow> mergeGroupByResultsWithoutPushDown(
      GroupByQuery query,
      GroupByQueryResources resource,
      QueryRunner<ResultRow> runner,
      ResponseContext context,
      Closer closer,
      GroupByStatsProvider.PerQueryStats perQueryStats
  )
  {
    // If there's a subquery, merge subquery results and then apply the aggregator

    final DataSource dataSource = query.getDataSource();

    if (dataSource instanceof QueryDataSource) {
      final GroupByQuery subquery;
      try {
        // Inject outer query context keys into subquery if they don't already exist in the subquery context.
        // Unlike withOverriddenContext's normal behavior, we want keys present in the subquery to win.
        final Map<String, Object> subqueryContext = new TreeMap<>();
        if (query.getContext() != null) {
          for (Map.Entry<String, Object> entry : query.getContext().entrySet()) {
            if (entry.getValue() != null) {
              subqueryContext.put(entry.getKey(), entry.getValue());
            }
          }
        }
        if (((QueryDataSource) dataSource).getQuery().getContext() != null) {
          subqueryContext.putAll(((QueryDataSource) dataSource).getQuery().getContext());
        }
        if (canPerformSubquery(((QueryDataSource) dataSource).getQuery())) {
          subqueryContext.put(QueryContexts.FINALIZE_KEY, true);
        }
        subqueryContext.put(GroupByQuery.CTX_KEY_SORT_BY_DIMS_FIRST, false);
        subquery = (GroupByQuery) ((QueryDataSource) dataSource).getQuery().withOverriddenContext(subqueryContext);

        closer.register(() -> groupByStatsProvider.closeQuery(subquery.context().getQueryResourceId()));
      }
      catch (ClassCastException e) {
        throw new UnsupportedOperationException("Subqueries must be of type 'group by'");
      }

      final Sequence<ResultRow> subqueryResult = mergeGroupByResults(
          subquery,
          resource,
          runner,
          context,
          closer,
          perQueryStats
      );

      final Sequence<ResultRow> finalizingResults = finalizeSubqueryResults(subqueryResult, subquery);

      if (query.getSubtotalsSpec() != null) {
        return groupingEngine.processSubtotalsSpec(
            query,
            resource,
            groupingEngine.processSubqueryResult(
                subquery,
                query, resource,
                finalizingResults,
                false,
                perQueryStats
            ),
            perQueryStats
        );
      } else {
        return groupingEngine.applyPostProcessing(
            groupingEngine.processSubqueryResult(
                subquery,
                query,
                resource,
                finalizingResults,
                false,
                perQueryStats
            ),
            query
        );
      }

    } else {
      if (query.getSubtotalsSpec() != null) {
        return groupingEngine.processSubtotalsSpec(
            query,
            resource,
            groupingEngine.mergeResults(runner, query.withSubtotalsSpec(null), context),
            perQueryStats
        );
      } else {
        return groupingEngine.applyPostProcessing(groupingEngine.mergeResults(runner, query, context), query);
      }
    }
  }

  private Sequence<ResultRow> mergeResultsWithNestedQueryPushDown(
      GroupByQuery query,
      GroupByQueryResources resource,
      QueryRunner<ResultRow> runner,
      ResponseContext context,
      GroupByStatsProvider.PerQueryStats perQueryStats
  )
  {
    Sequence<ResultRow> pushDownQueryResults = groupingEngine.mergeResults(runner, query, context);
    final Sequence<ResultRow> finalizedResults = finalizeSubqueryResults(pushDownQueryResults, query);
    GroupByQuery rewrittenQuery = rewriteNestedQueryForPushDown(query);
    return groupingEngine.applyPostProcessing(
        groupingEngine.processSubqueryResult(
            query,
            rewrittenQuery,
            resource,
            finalizedResults,
            true,
            perQueryStats
        ),
        query
    );
  }

  /**
   * Rewrite the aggregator and dimension specs since the push down nested query will return
   * results with dimension and aggregation specs of the original nested query.
   */
  @VisibleForTesting
  GroupByQuery rewriteNestedQueryForPushDown(GroupByQuery query)
  {
    return query.withAggregatorSpecs(Lists.transform(query.getAggregatorSpecs(), (agg) -> agg.getCombiningFactory()))
                .withDimensionSpecs(Lists.transform(
                    query.getDimensions(),
                    (dim) -> new DefaultDimensionSpec(
                        dim.getOutputName(),
                        dim.getOutputName(),
                        dim.getOutputType()
                    )
                ));
  }

  private Sequence<ResultRow> finalizeSubqueryResults(Sequence<ResultRow> subqueryResult, GroupByQuery subquery)
  {
    final Sequence<ResultRow> finalizingResults;
    if (subquery.context().isFinalize(false)) {
      finalizingResults = new MappedSequence<>(
          subqueryResult,
          makePreComputeManipulatorFn(
              subquery,
              MetricManipulatorFns.finalizing()
          )::apply
      );
    } else {
      finalizingResults = subqueryResult;
    }
    return finalizingResults;
  }

  public static boolean isNestedQueryPushDown(GroupByQuery q)
  {
    return q.getDataSource() instanceof QueryDataSource
           && q.context().getBoolean(GroupByQueryConfig.CTX_KEY_FORCE_PUSH_DOWN_NESTED_QUERY, false)
           && q.getSubtotalsSpec() == null;
  }

  @Override
  public GroupByQueryMetrics makeMetrics(GroupByQuery query)
  {
    GroupByQueryMetrics queryMetrics = queryMetricsFactory.makeMetrics();
    queryMetrics.query(query);
    return queryMetrics;
  }

  @Override
  public Function<ResultRow, ResultRow> makePreComputeManipulatorFn(
      final GroupByQuery query,
      final MetricManipulationFn fn
  )
  {
    if (MetricManipulatorFns.identity().equals(fn)) {
      return Functions.identity();
    }

    return row -> {
      final ResultRow newRow = row.copy();
      final List<AggregatorFactory> aggregatorSpecs = query.getAggregatorSpecs();
      final int aggregatorStart = query.getResultRowAggregatorStart();

      for (int i = 0; i < aggregatorSpecs.size(); i++) {
        AggregatorFactory agg = aggregatorSpecs.get(i);
        newRow.set(aggregatorStart + i, fn.manipulate(agg, row.get(aggregatorStart + i)));
      }

      return newRow;
    };
  }

  @Override
  public Function<ResultRow, ResultRow> makePostComputeManipulatorFn(
      final GroupByQuery query,
      final MetricManipulationFn fn
  )
  {
    final BitSet optimizedDims = extractionsToRewrite(query);
    final Function<ResultRow, ResultRow> preCompute = makePreComputeManipulatorFn(query, fn);

    if (optimizedDims.isEmpty()) {
      return preCompute;
    }

    // If we have optimizations that can be done at this level, we apply them here

    final List<DimensionSpec> dimensions = query.getDimensions();
    final List<ExtractionFn> extractionFns = new ArrayList<>(dimensions.size());
    for (int i = 0; i < dimensions.size(); i++) {
      final DimensionSpec dimensionSpec = dimensions.get(i);
      final ExtractionFn extractionFnToAdd;

      if (optimizedDims.get(i)) {
        extractionFnToAdd = dimensionSpec.getExtractionFn();
      } else {
        extractionFnToAdd = null;
      }

      extractionFns.add(extractionFnToAdd);
    }

    final int dimensionStart = query.getResultRowDimensionStart();
    return row -> {
      // preCompute.apply(row) will either return the original row, or create a copy.
      ResultRow newRow = preCompute.apply(row);

      //noinspection ObjectEquality (if preCompute made a copy, no need to make another copy)
      if (newRow == row) {
        newRow = row.copy();
      }

      for (int i = optimizedDims.nextSetBit(0); i >= 0; i = optimizedDims.nextSetBit(i + 1)) {
        newRow.set(
            dimensionStart + i,
            extractionFns.get(i).apply(newRow.get(dimensionStart + i))
        );
      }

      return newRow;
    };
  }

  @Override
  public TypeReference<ResultRow> getResultTypeReference()
  {
    return TYPE_REFERENCE;
  }

  @Override
  public ObjectMapper decorateObjectMapper(final ObjectMapper objectMapper, final GroupByQuery query)
  {
    return ResultRowObjectMapperDecoratorUtil.decorateObjectMapper(objectMapper, query, queryConfig);
  }

  @Override
  public QueryRunner<ResultRow> preMergeQueryDecoration(final QueryRunner<ResultRow> runner)
  {
    return new SubqueryQueryRunner<>(
        new QueryRunner<>()
        {
          @Override
          public Sequence<ResultRow> run(QueryPlus<ResultRow> queryPlus, ResponseContext responseContext)
          {
            GroupByQuery groupByQuery = (GroupByQuery) queryPlus.getQuery();
            final List<DimensionSpec> dimensionSpecs = new ArrayList<>();
            final BitSet optimizedDimensions = extractionsToRewrite(groupByQuery);
            final List<DimensionSpec> dimensions = groupByQuery.getDimensions();
            for (int i = 0; i < dimensions.size(); i++) {
              final DimensionSpec dimensionSpec = dimensions.get(i);
              if (optimizedDimensions.get(i)) {
                dimensionSpecs.add(
                    new DefaultDimensionSpec(dimensionSpec.getDimension(), dimensionSpec.getOutputName())
                );
              } else {
                dimensionSpecs.add(dimensionSpec);
              }
            }

            return runner.run(
                queryPlus.withQuery(groupByQuery.withDimensionSpecs(dimensionSpecs)),
                responseContext
            );
          }
        }
    );
  }

  @Nullable
  @Override
  public CacheStrategy<ResultRow, Object, GroupByQuery> getCacheStrategy(GroupByQuery query)
  {
    return getCacheStrategy(query, null);
  }

  @Override
  public CacheStrategy<ResultRow, Object, GroupByQuery> getCacheStrategy(
      final GroupByQuery query,
      @Nullable final ObjectMapper mapper
  )
  {

    for (DimensionSpec dimension : query.getDimensions()) {
      if (dimension.getOutputType().is(ValueType.COMPLEX) && !dimension.getOutputType().equals(ColumnType.NESTED_DATA)) {
        if (mapper == null) {
          throw DruidException.defensive(
              "Cannot deserialize complex dimension of type[%s] from result cache if object mapper is not provided",
              dimension.getOutputType().getComplexTypeName()
          );
        }
      }
    }
    final Class<?>[] dimensionClasses = createDimensionClasses(query);

    return new CacheStrategy<>()
    {
      private static final byte CACHE_STRATEGY_VERSION = 0x1;
      private final List<AggregatorFactory> aggs = query.getAggregatorSpecs();
      private final List<DimensionSpec> dims = query.getDimensions();

      @Override
      public boolean isCacheable(GroupByQuery query, boolean willMergeRunners, boolean bySegment)
      {
        //disable segment-level cache on borker,
        //see PR https://github.com/apache/druid/issues/3820
        return willMergeRunners || !bySegment;
      }

      @Override
      public byte[] computeCacheKey(GroupByQuery query)
      {
        CacheKeyBuilder builder = new CacheKeyBuilder(GROUPBY_QUERY)
            .appendByte(CACHE_STRATEGY_VERSION)
            .appendCacheable(query.getGranularity())
            .appendCacheable(query.getDimFilter())
            .appendCacheables(query.getAggregatorSpecs())
            .appendCacheables(query.getDimensions())
            .appendCacheable(query.getVirtualColumns());
        if (query.isApplyLimitPushDown()) {
          builder.appendCacheable(query.getLimitSpec());
        }
        return builder.build();
      }

      @Override
      public byte[] computeResultLevelCacheKey(GroupByQuery query)
      {
        final CacheKeyBuilder builder = new CacheKeyBuilder(GROUPBY_QUERY)
            .appendByte(CACHE_STRATEGY_VERSION)
            .appendCacheable(query.getGranularity())
            .appendCacheable(query.getDimFilter())
            .appendCacheables(query.getAggregatorSpecs())
            .appendCacheables(query.getDimensions())
            .appendCacheable(query.getVirtualColumns())
            .appendCacheable(query.getHavingSpec())
            .appendCacheable(query.getLimitSpec())
            .appendCacheables(query.getPostAggregatorSpecs());

        if (query.getSubtotalsSpec() != null && !query.getSubtotalsSpec().isEmpty()) {
          for (List<String> subTotalSpec : query.getSubtotalsSpec()) {
            builder.appendStrings(subTotalSpec);
          }
        }
        return builder.build();
      }

      @Override
      public TypeReference<Object> getCacheObjectClazz()
      {
        return OBJECT_TYPE_REFERENCE;
      }

      @Override
      public Function<ResultRow, Object> prepareForCache(boolean isResultLevelCache)
      {
        final boolean resultRowHasTimestamp = query.getResultRowHasTimestamp();

        return new Function<>()
        {
          @Override
          public Object apply(ResultRow resultRow)
          {
            final List<Object> retVal = new ArrayList<>(1 + dims.size() + aggs.size());
            int inPos = 0;

            if (resultRowHasTimestamp) {
              retVal.add(resultRow.getLong(inPos++));
            } else {
              retVal.add(query.getUniversalTimestamp().getMillis());
            }

            for (int i = 0; i < dims.size(); i++) {
              retVal.add(resultRow.get(inPos++));
            }
            for (int i = 0; i < aggs.size(); i++) {
              retVal.add(resultRow.get(inPos++));
            }
            if (isResultLevelCache) {
              for (int i = 0; i < query.getPostAggregatorSpecs().size(); i++) {
                retVal.add(resultRow.get(inPos++));
              }
            }
            return retVal;
          }
        };
      }

      @Override
      public Function<Object, ResultRow> pullFromCache(boolean isResultLevelCache)
      {
        final boolean resultRowHasTimestamp = query.getResultRowHasTimestamp();
        final int dimensionStart = query.getResultRowDimensionStart();
        final int aggregatorStart = query.getResultRowAggregatorStart();
        final int postAggregatorStart = query.getResultRowPostAggregatorStart();

        return new Function<>()
        {
          private final Granularity granularity = query.getGranularity();

          @Override
          public ResultRow apply(Object input)
          {
            Iterator<Object> results = ((List<Object>) input).iterator();

            DateTime timestamp = granularity.toDateTime(((Number) results.next()).longValue());

            final int size = isResultLevelCache
                             ? query.getResultRowSizeWithPostAggregators()
                             : query.getResultRowSizeWithoutPostAggregators();

            final ResultRow resultRow = ResultRow.create(size);

            if (resultRowHasTimestamp) {
              resultRow.set(0, timestamp.getMillis());
            }

            final Iterator<DimensionSpec> dimsIter = dims.iterator();
            int dimPos = 0;
            while (dimsIter.hasNext() && results.hasNext()) {
              final DimensionSpec dimensionSpec = dimsIter.next();
              final Object dimensionObject = results.next();
              final Object dimensionObjectCasted;

              final ColumnType outputType = dimensionSpec.getOutputType();

              // Must convert generic Jackson-deserialized type into the proper type. The downstream functions expect the
              // dimensions to be of appropriate types for further processing like merging and comparing.
              if (outputType.is(ValueType.COMPLEX)) {
                // Json columns can interpret generic data objects appropriately, hence they are wrapped as is in StructuredData.
                // They don't need to converted them from Object.class to StructuredData.class using object mapper as that is an
                // expensive operation that will be wasteful.
                if (outputType.equals(ColumnType.NESTED_DATA)) {
                  dimensionObjectCasted = StructuredData.wrap(dimensionObject);
                } else {
                  dimensionObjectCasted = mapper.convertValue(dimensionObject, dimensionClasses[dimPos]);
                }
              } else {
                dimensionObjectCasted = DimensionHandlerUtils.convertObjectToType(
                    dimensionObject,
                    dimensionSpec.getOutputType()
                );
              }
              resultRow.set(dimensionStart + dimPos, dimensionObjectCasted);
              dimPos++;
            }

            CacheStrategy.fetchAggregatorsFromCache(
                aggs,
                results,
                isResultLevelCache,
                (aggName, aggPosition, aggValueObject) -> {
                  resultRow.set(aggregatorStart + aggPosition, aggValueObject);
                }
            );

            if (isResultLevelCache) {
              for (int postPos = 0; postPos < query.getPostAggregatorSpecs().size(); postPos++) {
                if (!results.hasNext()) {
                  throw DruidException.defensive("Ran out of objects while reading postaggs from cache!");
                }
                resultRow.set(postAggregatorStart + postPos, results.next());
              }
            }
            if (dimsIter.hasNext() || results.hasNext()) {
              throw new ISE(
                  "Found left over objects while reading from cache!! dimsIter[%s] results[%s]",
                  dimsIter.hasNext(),
                  results.hasNext()
              );
            }

            return resultRow;
          }
        };
      }
    };
  }

  @Override
  public boolean canPerformSubquery(Query<?> subquery)
  {
    Query<?> current = subquery;

    while (current != null) {
      if (!(current instanceof GroupByQuery)) {
        return false;
      }

      if (current.getDataSource() instanceof QueryDataSource) {
        current = ((QueryDataSource) current.getDataSource()).getQuery();
      } else {
        current = null;
      }
    }

    return true;
  }

  @Override
  public RowSignature resultArraySignature(GroupByQuery query)
  {
    return query.getResultRowSignature();
  }

  @Override
  public Sequence<Object[]> resultsAsArrays(final GroupByQuery query, final Sequence<ResultRow> resultSequence)
  {
    return resultSequence.map(ResultRow::getArray);
  }

  /**
   * This returns a single frame containing the results of the group by query.
   */
  @Override
  public Optional<Sequence<FrameSignaturePair>> resultsAsFrames(
      GroupByQuery query,
      Sequence<ResultRow> resultSequence,
      MemoryAllocatorFactory memoryAllocatorFactory,
      boolean useNestedForUnknownTypes
  )
  {
    RowSignature rowSignature = query.getResultRowSignature(
        query.context().isFinalize(true)
        ? RowSignature.Finalization.YES
        : RowSignature.Finalization.NO
    );
    RowSignature modifiedRowSignature = useNestedForUnknownTypes
                                        ? FrameWriterUtils.replaceUnknownTypesWithNestedColumns(rowSignature)
                                        : rowSignature;

    FrameCursorUtils.throwIfColumnsHaveUnknownType(modifiedRowSignature);

    FrameWriterFactory frameWriterFactory = FrameWriters.makeColumnBasedFrameWriterFactory(
        memoryAllocatorFactory,
        modifiedRowSignature,
        new ArrayList<>()
    );


    Pair<Cursor, Closeable> cursorAndCloseable = IterableRowsCursorHelper.getCursorFromSequence(
        resultsAsArrays(query, resultSequence),
        rowSignature
    );
    Cursor cursor = cursorAndCloseable.lhs;
    Closeable closeble = cursorAndCloseable.rhs;

    Sequence<Frame> frames = FrameCursorUtils.cursorToFramesSequence(cursor, frameWriterFactory).withBaggage(closeble);

    return Optional.of(frames.map(frame -> new FrameSignaturePair(frame, modifiedRowSignature)));
  }

  /**
   * This function checks the query for dimensions which can be optimized by applying the dimension extraction
   * as the final step of the query instead of on every event.
   *
   * @param query The query to check for optimizations
   *
   * @return The set of dimensions (as offsets into {@code query.getDimensions()}) which can be extracted at the last
   * second upon query completion.
   */
  private static BitSet extractionsToRewrite(GroupByQuery query)
  {
    final BitSet retVal = new BitSet();

    final List<DimensionSpec> dimensions = query.getDimensions();
    for (int i = 0; i < dimensions.size(); i++) {
      final DimensionSpec dimensionSpec = dimensions.get(i);
      if (dimensionSpec.getExtractionFn() != null
          && ExtractionFn.ExtractionType.ONE_TO_ONE.equals(dimensionSpec.getExtractionFn().getExtractionType())) {
        retVal.set(i);
      }
    }

    return retVal;
  }

  private static Class<?>[] createDimensionClasses(final GroupByQuery query)
  {
    final List<DimensionSpec> queryDimensions = query.getDimensions();
    final Class<?>[] classes = new Class[queryDimensions.size()];
    for (int i = 0; i < queryDimensions.size(); ++i) {
      final ColumnType dimensionOutputType = queryDimensions.get(i).getOutputType();
      if (dimensionOutputType.is(ValueType.COMPLEX)) {
        NullableTypeStrategy nullableTypeStrategy = dimensionOutputType.getNullableStrategy();
        if (!nullableTypeStrategy.groupable()) {
          throw DruidException.defensive(
              "Ungroupable dimension [%s] with type [%s] found in the query.",
              queryDimensions.get(i).getDimension(),
              dimensionOutputType
          );
        }
        classes[i] = nullableTypeStrategy.getClazz();
      } else {
        classes[i] = Object.class;
      }
    }
    return classes;
  }
}
