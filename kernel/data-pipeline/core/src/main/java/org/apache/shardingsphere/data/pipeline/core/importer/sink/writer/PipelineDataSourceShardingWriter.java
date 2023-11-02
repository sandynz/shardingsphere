package org.apache.shardingsphere.data.pipeline.core.importer.sink.writer;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.data.pipeline.api.datasource.config.impl.ShardingSpherePipelineDataSourceConfiguration;
import org.apache.shardingsphere.data.pipeline.api.ingest.record.DataRecord;
import org.apache.shardingsphere.data.pipeline.api.metadata.LogicTableName;
import org.apache.shardingsphere.data.pipeline.common.config.ImporterConfiguration;
import org.apache.shardingsphere.data.pipeline.common.context.PipelineContextKey;
import org.apache.shardingsphere.data.pipeline.common.context.PipelineContextManager;
import org.apache.shardingsphere.data.pipeline.common.datanode.DataNodeUtils;
import org.apache.shardingsphere.data.pipeline.common.datasource.PipelineDataSourceManager;
import org.apache.shardingsphere.data.pipeline.common.job.progress.listener.PipelineJobProgressUpdatedParameter;
import org.apache.shardingsphere.data.pipeline.common.sqlbuilder.PipelineImportSQLBuilder;
import org.apache.shardingsphere.data.pipeline.core.consistencycheck.table.calculator.CalculationContext;
import org.apache.shardingsphere.data.pipeline.spi.ratelimit.JobRateLimitAlgorithm;
import org.apache.shardingsphere.infra.binder.context.statement.dml.InsertStatementContext;
import org.apache.shardingsphere.infra.config.props.ConfigurationProperties;
import org.apache.shardingsphere.infra.datanode.DataNode;
import org.apache.shardingsphere.infra.hint.HintValueContext;
import org.apache.shardingsphere.infra.instance.InstanceContext;
import org.apache.shardingsphere.infra.metadata.ShardingSphereMetaData;
import org.apache.shardingsphere.infra.parser.SQLParserEngine;
import org.apache.shardingsphere.infra.parser.ShardingSphereSQLParserEngine;
import org.apache.shardingsphere.infra.route.context.RouteContext;
import org.apache.shardingsphere.infra.route.context.RouteMapper;
import org.apache.shardingsphere.infra.route.context.RouteUnit;
import org.apache.shardingsphere.infra.util.close.QuietlyCloser;
import org.apache.shardingsphere.infra.yaml.config.pojo.YamlRootConfiguration;
import org.apache.shardingsphere.mode.manager.ContextManager;
import org.apache.shardingsphere.sharding.api.config.ShardingRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.strategy.sharding.ShardingStrategyConfiguration;
import org.apache.shardingsphere.sharding.api.config.strategy.sharding.StandardShardingStrategyConfiguration;
import org.apache.shardingsphere.sharding.exception.metadata.ShardingRuleNotFoundException;
import org.apache.shardingsphere.sharding.route.engine.condition.ShardingCondition;
import org.apache.shardingsphere.sharding.route.engine.condition.ShardingConditions;
import org.apache.shardingsphere.sharding.route.engine.condition.value.ListShardingConditionValue;
import org.apache.shardingsphere.sharding.route.engine.type.standard.ShardingStandardRoutingEngine;
import org.apache.shardingsphere.sharding.rule.ShardingRule;
import org.apache.shardingsphere.sharding.rule.TableRule;
import org.apache.shardingsphere.sharding.yaml.swapper.ShardingRuleConfigurationConverter;
import org.apache.shardingsphere.sql.parser.api.CacheOption;
import org.apache.shardingsphere.sql.parser.sql.common.statement.dml.InsertStatement;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@RequiredArgsConstructor
@Slf4j
public class PipelineDataSourceShardingWriter implements PipelineDataSourceWriter {
    
    private static final ExecutorService EXECUTOR_SERVICE = Executors.newFixedThreadPool(16);
    
    private final int batchSize = 1000;
    
    private final ImporterConfiguration importerConfig;
    
    private final PipelineDataSourceManager dataSourceManager;
    
    private final JobRateLimitAlgorithm rateLimitAlgorithm;
    
    private final PipelineImportSQLBuilder importSQLBuilder;
    
    private final SQLParserEngine sqlParserEngine;
    
    private final ConcurrentMap<String, CalculationContext> dataNodeCalculationContextCache = new ConcurrentHashMap<>();
    
    private final ConcurrentMap<String, AtomicInteger> dataNodeRowCountCache = new ConcurrentHashMap<>();
    
    private final AtomicLong taskIdGenerator = new AtomicLong(1);
    
    private final ConcurrentMap<Long, CompletableFuture<?>> taskIdFutureMap = new ConcurrentHashMap<>();
    
    public PipelineDataSourceShardingWriter(final ImporterConfiguration importerConfig, final PipelineDataSourceManager dataSourceManager,
                                            final JobRateLimitAlgorithm rateLimitAlgorithm, final PipelineImportSQLBuilder importSQLBuilder) {
        this.importerConfig = importerConfig;
        this.dataSourceManager = dataSourceManager;
        this.rateLimitAlgorithm = rateLimitAlgorithm;
        this.importSQLBuilder = importSQLBuilder;
        sqlParserEngine = new ShardingSphereSQLParserEngine(importerConfig.getDataSourceConfig().getDatabaseType(), new CacheOption(1, 100), new CacheOption(1, 100), false);
    }
    
    @SneakyThrows
    @Override
    public PipelineJobProgressUpdatedParameter tryFlush(final List<DataRecord> dataRecords) {
        YamlRootConfiguration yamlRootConfig = (YamlRootConfiguration) importerConfig.getDataSourceConfig().getDataSourceConfiguration();
        ShardingRule shardingRule = createShardingRule(PipelineContextKey.buildForProxy(), yamlRootConfig);
        String logicTableName = dataRecords.get(0).getTableName();
        Optional<TableRule> targetTableRule = shardingRule.findTableRule(logicTableName);
        ShardingStrategyConfiguration targetTableShardingStrategyConfig = targetTableRule.get().getTableShardingStrategyConfig();
        String targetShardingColumn = ((StandardShardingStrategyConfiguration) targetTableShardingStrategyConfig).getShardingColumn();
        InsertStatement insertStatement = (InsertStatement) sqlParserEngine.parse(importSQLBuilder.buildInsertSQL(getSchemaName(dataRecords.get(0).getTableName()), dataRecords.get(0)), true);
        ContextManager contextManager = PipelineContextManager.getProxyContext().getContextManager();
        ShardingSphereMetaData metaData = contextManager.getMetaDataContexts().getMetaData();
        InsertStatementContext insertStatementContext = new InsertStatementContext(metaData, Collections.emptyList(), insertStatement, yamlRootConfig.getDatabaseName());
//        PipelineDataSourceWrapper dataSource = dataSourceManager.getDataSource(importerConfig.getDataSourceConfig());
        int batchRowCount = 0;
        for (DataRecord each : dataRecords) {
            ShardingCondition shardingCondition = new ShardingCondition();
            shardingCondition.setStartIndex(0);
            Object shardingColumnValue = each.getColumn(0).getValue(); // TODO index
            shardingCondition.getValues().add(new ListShardingConditionValue<>(targetShardingColumn, logicTableName, Collections.singletonList(shardingColumnValue)));
            ShardingConditions shardingConditions = new ShardingConditions(Collections.singletonList(shardingCondition), insertStatementContext, shardingRule);
            ShardingStandardRoutingEngine standardRoutingEngine = new ShardingStandardRoutingEngine(logicTableName, shardingConditions,
                    Collections.emptyList(), new HintValueContext(), new ConfigurationProperties(new Properties()));
            RouteContext routeContext = standardRoutingEngine.route(shardingRule);
            for (RouteUnit routeUnit : routeContext.getRouteUnits()) {
                for (RouteMapper routeMapper : routeUnit.getTableMappers()) {
                    DataNode dataNode = new DataNode(routeUnit.getDataSourceMapper().getActualName(), routeMapper.getActualName());
                    String dataNodeText = DataNodeUtils.formatWithSchema(dataNode);
                    dataNodeCalculationContextCache.computeIfAbsent(dataNodeText, s -> new CalculationContext());
                    CalculationContext calculationContext = dataNodeCalculationContextCache.get(dataNodeText);
                    if (null == calculationContext.getConnection()) {
                        // java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available, request timed out after 30006ms.
//                        Connection connection = contextManager.getStorageUnits(yamlRootConfig.getDatabaseName()).get(dataNode.getDataSourceName()).getDataSource().getConnection();
                        Connection connection = dataSourceManager.getDataSource(((ShardingSpherePipelineDataSourceConfiguration) importerConfig.getDataSourceConfig()).getActualDataSourceConfiguration(dataNode.getDataSourceName())).getConnection();
                        calculationContext.setConnection(connection);
                        each.setActualTableName(dataNode.getTableName());
                        PreparedStatement preparedStatement = connection.prepareStatement(importSQLBuilder.buildActualInsertSQL(getSchemaName(each.getTableName()), dataNodeText, each));
                        preparedStatement.setQueryTimeout(30);
                        calculationContext.setPreparedStatement(preparedStatement);
                    }
                    PreparedStatement preparedStatement = calculationContext.getPreparedStatement();
                    for (int i = 0; i < each.getColumnCount(); i++) {
                        preparedStatement.setObject(i + 1, each.getColumn(i).getValue());
                    }
                    preparedStatement.addBatch();
                    ++batchRowCount;
                    int dataNodeRowCount = dataNodeRowCountCache.computeIfAbsent(dataNodeText, s -> new AtomicInteger(0)).incrementAndGet();
                    if (dataNodeRowCount >= batchSize) {
                        dataNodeRowCountCache.get(dataNodeText).addAndGet(-dataNodeRowCount);
                        Long taskId = taskIdGenerator.incrementAndGet();
                        CompletableFuture<?> future = CompletableFuture.runAsync(() -> {
                            try {
                                preparedStatement.executeBatch();
                            } catch (SQLException e) {
                                throw new RuntimeException(e);
                            }
//                            QuietlyCloser.close(calculationContext);
//                            dataNodeCalculationContextCache.remove(dataNodeText);
                        }, EXECUTOR_SERVICE).whenComplete((unused, throwable) -> {
                            taskIdFutureMap.remove(taskId);
                            if (null != throwable) {
                                log.error("executeBatch failed", throwable);
                            }
                        });
                        taskIdFutureMap.put(taskId, future);
                    }
                }
            }
        }
        return new PipelineJobProgressUpdatedParameter(batchRowCount); // TODO rows are not inserted for now
    }
    
    private ShardingRule createShardingRule(final PipelineContextKey contextKey, final YamlRootConfiguration yamlRootConfig) {
        ShardingRuleConfiguration shardingRuleConfig = ShardingRuleConfigurationConverter.findAndConvertShardingRuleConfiguration(yamlRootConfig.getRules())
                .orElseThrow(ShardingRuleNotFoundException::new);
        InstanceContext instanceContext = PipelineContextManager.getContext(contextKey).getContextManager().getInstanceContext();
        return new ShardingRule(shardingRuleConfig, yamlRootConfig.getDataSources().keySet(), instanceContext);
    }
    
    private String getSchemaName(final String logicTableName) {
        return importerConfig.getSchemaName(new LogicTableName(logicTableName));
    }
    
    @SneakyThrows
    @Override
    public void forceFlush() {
        for (CalculationContext each : dataNodeCalculationContextCache.values()) {
            PreparedStatement preparedStatement = each.getPreparedStatement();
            if (null == preparedStatement) {
                continue;
            }
            preparedStatement.executeBatch();
        }
    }
    
    @Override
    public void close() throws Exception {
        log.info("close, taskIdFutureMap.size={}", taskIdFutureMap.size());
        for (CompletableFuture<?> each : taskIdFutureMap.values()) {
            try {
                each.get();
            } catch (final Exception ex) {
                log.warn("future error", ex);
            }
        }
        for (CalculationContext each : dataNodeCalculationContextCache.values()) {
            QuietlyCloser.close(each);
        }
        dataNodeCalculationContextCache.clear();
        dataNodeRowCountCache.clear();
    }
}
