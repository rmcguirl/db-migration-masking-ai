package com.migration.batch;

import com.migration.connector.api.DestinationConnector;
import com.migration.connector.api.SourceConnector;
import com.migration.domain.Row;
import com.migration.domain.TableDescriptor;
import com.migration.domain.TableRef;
import com.migration.masking.MaskingEngine;
import com.migration.plan.TablePlan;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Builds one chunk-oriented {@link Step} per table: keyset-paginated read → mask →
 * upsert, chunk-committed (design doc §7 step 8). One step instance per table per run —
 * this is a factory, not a singleton bean, since each table needs its own reader/writer
 * state.
 */
@Component
public class TableStepFactory {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final MaskingEngine maskingEngine;

    public TableStepFactory(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                             MaskingEngine maskingEngine) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.maskingEngine = maskingEngine;
    }

    public TableStep createStep(TablePlan tablePlan, TableDescriptor sourceTableDescriptor, SourceConnector source,
                                 DestinationConnector destination, int chunkSize) {
        TableRef sourceRef = sourceTableDescriptor.ref();
        TableRef destinationRef = TableRef.of(tablePlan.destinationTable());

        KeysetPaginatingItemReader reader = new KeysetPaginatingItemReader(source, sourceRef,
                sourceTableDescriptor.primaryKeyColumns(), chunkSize);
        MaskingItemProcessor processor = new MaskingItemProcessor(maskingEngine, tablePlan);
        UpsertItemWriter writer = new UpsertItemWriter(destination, destinationRef, tablePlan.naturalKey());

        Step step = new StepBuilder("table-step-" + tablePlan.sourceTable(), jobRepository)
                .<Row, Row>chunk(chunkSize, transactionManager)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .build();
        return new TableStep(step, writer);
    }
}
