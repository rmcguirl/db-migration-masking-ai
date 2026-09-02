package com.migration.batch;

import com.migration.config.MigrationConfig;
import com.migration.connector.api.DestinationConnector;
import com.migration.connector.api.RowStream;
import com.migration.connector.api.SourceConnector;
import com.migration.connector.api.TransactionalBatchWrite;
import com.migration.connector.api.UpsertResult;
import com.migration.domain.ExtractCursor;
import com.migration.domain.Row;
import com.migration.domain.SchemaModel;
import com.migration.domain.TableDescriptor;
import com.migration.domain.TableRef;
import com.migration.masking.MaskingEngine;
import com.migration.orchestrator.JobExecutionResult;
import com.migration.orchestrator.MigrationJobService;
import com.migration.orchestrator.TableRunOutcome;
import com.migration.plan.MigrationPlan;
import com.migration.plan.TablePlan;
import com.migration.referential.CycleLoadStrategyResolver;
import com.migration.referential.CycleResolution;
import com.migration.referential.CycleStrategyOutcome;
import com.migration.referential.DependencyGraph;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;

/**
 * Runs one {@code Job} per table, in dependency-graph layer order — tables within a layer
 * run concurrently (bounded by {@code run.parallelism}), layers run sequentially (design
 * doc §7 step 8, §10). A step failure marks its table {@code FAILED} and every table in
 * {@code DependencyGraph.transitiveDependents(...)} of it {@code SKIPPED}, without
 * aborting unrelated branches (§7 step 9) — table-level {@code Job}s are independent
 * {@code JobInstance}s, so one failing never touches Spring Batch's bookkeeping for
 * another.
 *
 * <p><b>Resumability identity:</b> each table's {@code JobParameters} is keyed by
 * {@code (schemaFingerprint, table)} — no separate "explicit run id" (design doc §7 step
 * 10's phrasing), since re-running the same config against an <em>unchanged</em> source
 * schema is exactly the condition under which resumption should happen, and the
 * fingerprint alone already captures that; a schema change naturally produces a new
 * fingerprint and therefore a fresh, non-resumed run.
 *
 * <p>A {@code DEFERRED_TRANSACTION} cycle's tables bypass the Spring Batch step machinery
 * entirely and load synchronously inside one {@link TransactionalBatchWrite#inTransaction}
 * call, since they must share one database transaction (design doc §5) — something
 * per-table {@code Step}/{@code Job} isolation can't express.
 */
@Component
public class DefaultMigrationJobService implements MigrationJobService {

    private final JobRepository jobRepository;
    private final JobLauncher jobLauncher;
    private final TableStepFactory stepFactory;
    private final CycleLoadStrategyResolver cycleLoadStrategyResolver;
    private final MaskingEngine maskingEngine;
    private final MigrationConfig migrationConfig;

    public DefaultMigrationJobService(JobRepository jobRepository, JobLauncher jobLauncher, TableStepFactory stepFactory,
                                       CycleLoadStrategyResolver cycleLoadStrategyResolver, MaskingEngine maskingEngine,
                                       MigrationConfig migrationConfig) {
        this.jobRepository = jobRepository;
        this.jobLauncher = jobLauncher;
        this.stepFactory = stepFactory;
        this.cycleLoadStrategyResolver = cycleLoadStrategyResolver;
        this.maskingEngine = maskingEngine;
        this.migrationConfig = migrationConfig;
    }

    @Override
    public JobExecutionResult loadAll(MigrationPlan plan, DependencyGraph graph, SourceConnector source,
                                       DestinationConnector destination) {
        SchemaModel sourceSchema = source.introspectSchema();
        Map<String, TableDescriptor> descriptorsByName = new HashMap<>();
        for (TableDescriptor table : sourceSchema.tables()) {
            descriptorsByName.put(table.ref().table(), table);
        }

        Predicate<TableRef> supportsDeferredConstraints = t -> destination instanceof TransactionalBatchWrite tbw
                && tbw.supportsDeferredConstraints();
        List<CycleResolution> cycleResolutions = cycleLoadStrategyResolver.resolve(
                graph.detectCycles(), migrationConfig.referentialIntegrity(), supportsDeferredConstraints);
        Map<TableRef, CycleResolution> resolutionByTable = new HashMap<>();
        for (CycleResolution resolution : cycleResolutions) {
            for (TableRef table : resolution.cycleTables()) {
                resolutionByTable.put(table, resolution);
            }
        }

        Map<TableRef, TableRunOutcome> outcomes = new ConcurrentHashMap<>();
        Set<TableRef> unavailable = ConcurrentHashMap.newKeySet();
        Set<TableRef> handledAsPartOfCycle = new HashSet<>();

        int parallelism = Math.max(1, migrationConfig.run().parallelism());
        ExecutorService executor = Executors.newFixedThreadPool(parallelism);
        try {
            for (List<TableRef> layer : graph.loadLayers()) {
                List<Future<?>> futures = new ArrayList<>();
                for (TableRef table : layer) {
                    if (handledAsPartOfCycle.contains(table)) {
                        continue;
                    }
                    CycleResolution resolution = resolutionByTable.get(table);
                    if (resolution != null) {
                        handledAsPartOfCycle.addAll(resolution.cycleTables());
                        futures.add(executor.submit(() -> runCycleGroup(resolution, plan, descriptorsByName, source,
                                destination, outcomes, unavailable, graph)));
                        continue;
                    }
                    futures.add(executor.submit(() -> runOneTable(table, plan, descriptorsByName, source, destination,
                            outcomes, unavailable, graph)));
                }
                awaitAll(futures);
            }
        } finally {
            executor.shutdown();
        }
        return new JobExecutionResult(outcomes);
    }

    private void awaitAll(List<Future<?>> futures) {
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception ignored) {
                // failures are already captured per-table in `outcomes`; this just blocks until the layer finishes
            }
        }
    }

    private void runOneTable(TableRef table, MigrationPlan plan, Map<String, TableDescriptor> descriptorsByName,
                              SourceConnector source, DestinationConnector destination,
                              Map<TableRef, TableRunOutcome> outcomes, Set<TableRef> unavailable, DependencyGraph graph) {
        if (unavailable.contains(table)) {
            return;
        }
        java.util.Optional<TablePlan> tablePlanOpt = plan.table(table.table());
        if (tablePlanOpt.isEmpty()) {
            return;
        }
        TablePlan tablePlan = tablePlanOpt.get();

        for (String parentName : tablePlan.dependsOn()) {
            if (unavailable.contains(TableRef.of(parentName))) {
                skip(table, "transitive dependent of failed/skipped table '" + parentName + "'", outcomes, unavailable, graph);
                return;
            }
        }

        TableDescriptor descriptor = descriptorsByName.get(table.table());
        if (descriptor == null) {
            fail(table, "no source table descriptor found for '" + table.table() + "'", outcomes, unavailable, graph);
            return;
        }

        try {
            TableStep tableStep = stepFactory.createStep(tablePlan, descriptor, source, destination,
                    migrationConfig.run().batchSize());
            Job job = new JobBuilder("migration-job-" + tablePlan.sourceTable(), jobRepository)
                    .start(tableStep.step())
                    .build();
            JobParameters parameters = new JobParametersBuilder()
                    .addString("schemaFingerprint", plan.schemaFingerprint())
                    .addString("table", tablePlan.sourceTable())
                    .toJobParameters();
            JobExecution execution = jobLauncher.run(job, parameters);
            if (execution.getStatus() == BatchStatus.COMPLETED) {
                UpsertResult totals = tableStep.writer().totals();
                outcomes.put(table, TableRunOutcome.completed(totals.inserted(), totals.updated()));
            } else {
                String message = execution.getAllFailureExceptions().stream()
                        .findFirst()
                        .map(Throwable::getMessage)
                        .orElse("step ended with status " + execution.getStatus());
                fail(table, message, outcomes, unavailable, graph);
            }
        } catch (Exception e) {
            fail(table, e.getMessage(), outcomes, unavailable, graph);
        }
    }

    private void runCycleGroup(CycleResolution resolution, MigrationPlan plan, Map<String, TableDescriptor> descriptorsByName,
                                SourceConnector source, DestinationConnector destination,
                                Map<TableRef, TableRunOutcome> outcomes, Set<TableRef> unavailable, DependencyGraph graph) {
        if (resolution.strategy() == CycleStrategyOutcome.UNSUPPORTED) {
            for (TableRef table : resolution.cycleTables()) {
                fail(table, "cycle involving this table has no configured resolution strategy (UNSUPPORTED)",
                        outcomes, unavailable, graph);
            }
            return;
        }

        if (resolution.strategy() == CycleStrategyOutcome.DECLARED_ORDER) {
            for (TableRef table : resolution.loadOrder()) {
                if (unavailable.contains(table)) {
                    break;
                }
                runOneTable(table, plan, descriptorsByName, source, destination, outcomes, unavailable, graph);
            }
            return;
        }

        // DEFERRED_TRANSACTION: bypass per-table Steps; load every cycle table inside one shared transaction.
        TransactionalBatchWrite transactional = (TransactionalBatchWrite) destination;
        try {
            Map<TableRef, UpsertResult> totals = transactional.inTransaction(() -> {
                Map<TableRef, UpsertResult> perTable = new LinkedHashMap<>();
                for (TableRef table : resolution.cycleTables()) {
                    TablePlan tablePlan = plan.table(table.table())
                            .orElseThrow(() -> new IllegalStateException("no plan entry for cycle table " + table.table()));
                    TableDescriptor descriptor = descriptorsByName.get(table.table());
                    perTable.put(table, loadTableSynchronously(tablePlan, descriptor, source, destination));
                }
                return perTable;
            });
            for (Map.Entry<TableRef, UpsertResult> entry : totals.entrySet()) {
                outcomes.put(entry.getKey(), TableRunOutcome.completed(entry.getValue().inserted(), entry.getValue().updated()));
            }
        } catch (Exception e) {
            for (TableRef table : resolution.cycleTables()) {
                fail(table, e.getMessage(), outcomes, unavailable, graph);
            }
        }
    }

    /** Extracts, masks, and upserts an entire table synchronously — used only for a DEFERRED_TRANSACTION cycle group. */
    private UpsertResult loadTableSynchronously(TablePlan tablePlan, TableDescriptor descriptor, SourceConnector source,
                                                 DestinationConnector destination) {
        TableRef sourceRef = descriptor.ref();
        TableRef destinationRef = TableRef.of(tablePlan.destinationTable());
        int batchSize = migrationConfig.run().batchSize();
        ExtractCursor cursor = ExtractCursor.START;
        UpsertResult total = UpsertResult.zero();

        while (true) {
            List<Row> page = new ArrayList<>();
            try (RowStream stream = source.extract(sourceRef, cursor, batchSize)) {
                for (Row row : stream) {
                    page.add(row);
                }
            }
            if (page.isEmpty()) {
                break;
            }
            List<Row> masked = page.stream().map(row -> maskingEngine.maskRow(row, tablePlan)).toList();
            total = total.plus(destination.upsert(destinationRef, tablePlan.naturalKey(), masked));

            Map<String, Object> lastKeyValues = new LinkedHashMap<>();
            Row lastRow = page.get(page.size() - 1);
            for (String keyColumn : descriptor.primaryKeyColumns()) {
                lastKeyValues.put(keyColumn, lastRow.get(keyColumn));
            }
            cursor = new ExtractCursor(lastKeyValues);
            if (page.size() < batchSize) {
                break;
            }
        }
        return total;
    }

    private void fail(TableRef table, String message, Map<TableRef, TableRunOutcome> outcomes, Set<TableRef> unavailable,
                       DependencyGraph graph) {
        outcomes.put(table, TableRunOutcome.failed(message));
        unavailable.add(table);
        markTransitiveDependentsSkipped(table, graph, outcomes, unavailable);
    }

    private void skip(TableRef table, String reason, Map<TableRef, TableRunOutcome> outcomes, Set<TableRef> unavailable,
                       DependencyGraph graph) {
        outcomes.put(table, TableRunOutcome.skipped(reason));
        unavailable.add(table);
        markTransitiveDependentsSkipped(table, graph, outcomes, unavailable);
    }

    private void markTransitiveDependentsSkipped(TableRef table, DependencyGraph graph, Map<TableRef, TableRunOutcome> outcomes,
                                                  Set<TableRef> unavailable) {
        for (TableRef dependent : graph.transitiveDependents(table)) {
            outcomes.putIfAbsent(dependent, TableRunOutcome.skipped("transitive dependent of '" + table.table() + "'"));
            unavailable.add(dependent);
        }
    }
}
