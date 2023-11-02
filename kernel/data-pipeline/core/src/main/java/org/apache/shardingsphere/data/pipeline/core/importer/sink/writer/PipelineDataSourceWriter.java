package org.apache.shardingsphere.data.pipeline.core.importer.sink.writer;

import org.apache.shardingsphere.data.pipeline.api.ingest.record.DataRecord;
import org.apache.shardingsphere.data.pipeline.api.ingest.record.Record;
import org.apache.shardingsphere.data.pipeline.common.job.progress.listener.PipelineJobProgressUpdatedParameter;

import java.util.List;

public interface PipelineDataSourceWriter extends AutoCloseable {
    
    PipelineJobProgressUpdatedParameter tryFlush(List<DataRecord> dataRecords);
    
    void forceFlush();
}
