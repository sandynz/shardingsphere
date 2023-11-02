/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.data.pipeline.core.importer.sink;

import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.data.pipeline.api.datasource.config.impl.ShardingSpherePipelineDataSourceConfiguration;
import org.apache.shardingsphere.data.pipeline.api.ingest.record.DataRecord;
import org.apache.shardingsphere.data.pipeline.api.ingest.record.Record;
import org.apache.shardingsphere.data.pipeline.common.config.ImporterConfiguration;
import org.apache.shardingsphere.data.pipeline.common.datasource.PipelineDataSourceManager;
import org.apache.shardingsphere.data.pipeline.common.job.progress.listener.PipelineJobProgressUpdatedParameter;
import org.apache.shardingsphere.data.pipeline.common.sqlbuilder.PipelineImportSQLBuilder;
import org.apache.shardingsphere.data.pipeline.core.importer.sink.writer.PipelineDataSourceDirectWriter;
import org.apache.shardingsphere.data.pipeline.core.importer.sink.writer.PipelineDataSourceShardingWriter;
import org.apache.shardingsphere.data.pipeline.core.importer.sink.writer.PipelineDataSourceWriter;
import org.apache.shardingsphere.data.pipeline.spi.ratelimit.JobRateLimitAlgorithm;
import org.apache.shardingsphere.infra.util.close.QuietlyCloser;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Pipeline data source sink.
 */
@Slf4j
public final class PipelineDataSourceSink implements PipelineSink {
    
    private final PipelineDataSourceWriter dataSourceWriter;
    
    public PipelineDataSourceSink(final ImporterConfiguration importerConfig, final PipelineDataSourceManager dataSourceManager) {
        JobRateLimitAlgorithm rateLimitAlgorithm = importerConfig.getRateLimitAlgorithm();
        PipelineImportSQLBuilder importSQLBuilder = new PipelineImportSQLBuilder(importerConfig.getDataSourceConfig().getDatabaseType());
        dataSourceWriter = importerConfig.getDataSourceConfig() instanceof ShardingSpherePipelineDataSourceConfiguration
                ? new PipelineDataSourceShardingWriter(importerConfig, dataSourceManager, rateLimitAlgorithm, importSQLBuilder)
                : new PipelineDataSourceDirectWriter(importerConfig, dataSourceManager, rateLimitAlgorithm, importSQLBuilder);
    }
    
    @Override
    public boolean identifierMatched(final Object identifier) {
        throw new UnsupportedOperationException();
    }
    
    @Override
    public PipelineJobProgressUpdatedParameter write(final String ackId, final List<Record> records) {
        List<DataRecord> dataRecords = records.stream().filter(DataRecord.class::isInstance).map(DataRecord.class::cast).collect(Collectors.toList());
        if (dataRecords.isEmpty()) {
            return new PipelineJobProgressUpdatedParameter(0);
        }
        return dataSourceWriter.tryFlush(dataRecords);
    }
    
    @Override
    public void close() {
        dataSourceWriter.forceFlush();
        QuietlyCloser.close(dataSourceWriter);
    }
}
