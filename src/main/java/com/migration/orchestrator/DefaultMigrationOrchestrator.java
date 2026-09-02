package com.migration.orchestrator;

import com.migration.audit.JdbcAuditLogService;
import com.migration.config.MigrationConfig;
import com.migration.config.VirtualForeignKeyConfig;
import com.migration.connector.api.ConnectorRegistry;
import com.migration.connector.api.DdlCapable;
import com.migration.connector.api.DestinationConnector;
import com.migration.connector.api.SourceConnector;
import com.migration.connector.jdbc.DdlConflictResolver;
import com.migration.domain.CanonicalType;
import com.migration.domain.ColumnDescriptor;
import com.migration.domain.NativeTypeDescriptor;
import com.migration.domain.SchemaModel;
import com.migration.domain.TableDescriptor;
import com.migration.domain.TableRef;
import com.migration.plan.ColumnPlan;
import com.migration.plan.DdlConflict;
import com.migration.plan.DdlPlan;
import com.migration.plan.MigrationPlan;
import com.migration.plan.PlanGenerationService;
import com.migration.plan.SchemaFingerprinter;
import com.migration.plan.TablePlan;
import com.migration.plan.VirtualForeignKey;
import com.migration.referential.DependencyGraph;
import com.migration.referential.ReferentialGraphBuilder;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Drives the full sequence from design doc §7: introspect → fingerprint → get-or-generate
 * plan → DDL reconcile (dry-run for {@code PLAN_ONLY}, applied for {@code FULL}) → build
 * dependency graph → dispatch to the job service ({@code FULL}) or the report writer
 * ({@code PLAN_ONLY}); records every step via the audit service.
 */
@Component
public class DefaultMigrationOrchestrator implements MigrationOrchestrator {

    private final ConnectorRegistry connectorRegistry;
    private final MigrationConfig config;
    private final SchemaFingerprinter schemaFingerprinter;
    private final PlanGenerationService planGenerationService;
    private final ReferentialGraphBuilder referentialGraphBuilder;
    private final DdlConflictResolver ddlConflictResolver;
    private final MigrationJobService migrationJobService;
    private final JdbcAuditLogService auditLogService;
    private final PlanReportWriter planReportWriter;

    public DefaultMigrationOrchestrator(ConnectorRegistry connectorRegistry, MigrationConfig config,
                                         SchemaFingerprinter schemaFingerprinter, PlanGenerationService planGenerationService,
                                         ReferentialGraphBuilder referentialGraphBuilder, DdlConflictResolver ddlConflictResolver,
                                         MigrationJobService migrationJobService, JdbcAuditLogService auditLogService,
                                         PlanReportWriter planReportWriter) {
        this.connectorRegistry = connectorRegistry;
        this.config = config;
        this.schemaFingerprinter = schemaFingerprinter;
        this.planGenerationService = planGenerationService;
        this.referentialGraphBuilder = referentialGraphBuilder;
        this.ddlConflictResolver = ddlConflictResolver;
        this.migrationJobService = migrationJobService;
        this.auditLogService = auditLogService;
        this.planReportWriter = planReportWriter;
    }

    @Override
    public RunResult run(RunMode mode) {
        SourceConnector source = connectorRegistry.resolveSource(config.source().type());
        DestinationConnector destination = connectorRegistry.resolveDestination(config.destination().type());
        source.testConnection();
        destination.testConnection();

        SchemaModel sourceSchema = source.introspectSchema();
        String fingerprint = schemaFingerprinter.fingerprint(sourceSchema);
        RunContext ctx = RunContext.start(UUID.randomUUID().toString(), fingerprint, mode);
        auditLogService.recordRunStart(ctx);

        try {
            MigrationPlan plan = planGenerationService.getOrGeneratePlan(fingerprint, sourceSchema, config);
            DdlPlan ddlDiff = diffDestination(plan, sourceSchema, destination);
            auditLogService.recordPlanAndDdl(ctx, plan, ddlDiff);

            RunResult result = mode == RunMode.PLAN_ONLY
                    ? runPlanOnly(plan, ddlDiff)
                    : runFull(ctx, plan, ddlDiff, sourceSchema, source, destination);

            auditLogService.recordRunEnd(ctx, result);
            return result;
        } catch (RuntimeException e) {
            RunResult failure = RunResult.hardFailure(e.getMessage());
            auditLogService.recordRunEnd(ctx, failure);
            throw e;
        }
    }

    private RunResult runPlanOnly(MigrationPlan plan, DdlPlan ddlDiff) {
        planReportWriter.write(plan, ddlDiff, new com.migration.report.ConsoleReportSink());
        planReportWriter.write(plan, ddlDiff, new com.migration.report.FileReportSink(Path.of("migration-plan-report")));
        return RunResult.success("plan-only run complete; report written for review, no data loaded");
    }

    private RunResult runFull(RunContext ctx, MigrationPlan plan, DdlPlan ddlDiff, SchemaModel sourceSchema,
                               SourceConnector source, DestinationConnector destination) {
        if (destination instanceof DdlCapable ddlCapable) {
            ddlCapable.applyDdl(ddlDiff);
        }

        DependencyGraph graph = buildGraph(sourceSchema);

        Set<TableRef> ddlSkipped = new LinkedHashSet<>();
        MigrationPlan filteredPlan = filterConflictedTables(plan, ddlDiff, sourceSchema, graph, ddlSkipped);
        for (TableRef skipped : ddlSkipped) {
            TableRunOutcome outcome = TableRunOutcome.skipped("excluded by a DDL conflict on a key column, or a transitive dependent of one");
            auditLogService.recordTableResult(ctx, skipped, outcome);
        }

        JobExecutionResult jobResult = migrationJobService.loadAll(filteredPlan, graph, source, destination);
        for (Map.Entry<TableRef, TableRunOutcome> entry : jobResult.tableOutcomes().entrySet()) {
            auditLogService.recordTableResult(ctx, entry.getKey(), entry.getValue());
        }

        boolean anyIssues = !ddlSkipped.isEmpty() || jobResult.hasFailuresOrSkips();
        return anyIssues
                ? RunResult.partialFailure("run completed with some tables skipped or failed; see audit log")
                : RunResult.success("run completed; every table loaded");
    }

    private DdlPlan diffDestination(MigrationPlan plan, SchemaModel sourceSchema, DestinationConnector destination) {
        if (!(destination instanceof DdlCapable ddlCapable)) {
            return DdlPlan.empty();
        }
        SchemaModel existing = destination.introspectSchema();
        SchemaModel desired = buildDesiredSchema(plan, destination);
        return ddlCapable.diffSchema(desired, existing);
    }

    private SchemaModel buildDesiredSchema(MigrationPlan plan, DestinationConnector destination) {
        List<TableDescriptor> tables = new ArrayList<>();
        for (TablePlan tablePlan : plan.tables()) {
            List<String> primaryKeyColumns = tablePlan.naturalKey();
            List<ColumnDescriptor> columns = new ArrayList<>();
            for (ColumnPlan columnPlan : tablePlan.columns()) {
                NativeTypeDescriptor nativeType = destination.typeMapper().fromCanonical(columnPlan.canonicalType());
                boolean isPrimaryKey = primaryKeyColumns.contains(columnPlan.destination());
                // Always created nullable, regardless of source nullability: additive DDL against a
                // possibly-already-populated destination table must never add a NOT NULL column with
                // no default, which is what a "faithful" NOT NULL would risk (design doc §3, §9).
                columns.add(new ColumnDescriptor(columnPlan.destination(), columnPlan.canonicalType(), nativeType,
                        true, isPrimaryKey, null));
            }
            tables.add(new TableDescriptor(TableRef.of(tablePlan.destinationTable()), columns, primaryKeyColumns, null));
        }
        return SchemaModel.of(tables);
    }

    private DependencyGraph buildGraph(SchemaModel sourceSchema) {
        List<VirtualForeignKey> virtualForeignKeys = config.overrides().virtualForeignKeys().stream()
                .map(this::toVirtualForeignKey)
                .toList();
        return referentialGraphBuilder.build(sourceSchema, virtualForeignKeys);
    }

    private VirtualForeignKey toVirtualForeignKey(VirtualForeignKeyConfig cfg) {
        return new VirtualForeignKey(TableRef.of(cfg.childTable()), cfg.childColumns(),
                TableRef.of(cfg.parentTable()), cfg.parentColumns());
    }

    private MigrationPlan filterConflictedTables(MigrationPlan plan, DdlPlan ddlDiff, SchemaModel sourceSchema,
                                                  DependencyGraph graph, Set<TableRef> skippedOut) {
        Map<String, List<DdlConflict>> conflictsByTable = ddlDiff.conflicts().stream()
                .collect(Collectors.groupingBy(c -> c.table().table()));
        if (conflictsByTable.isEmpty()) {
            return plan;
        }

        List<TablePlan> keptTables = new ArrayList<>();
        for (TablePlan tablePlan : plan.tables()) {
            List<DdlConflict> conflicts = conflictsByTable.getOrDefault(tablePlan.destinationTable(), List.of());
            Set<String> keyColumns = keyColumnsFor(tablePlan, sourceSchema);
            Optional<TablePlan> resolved = ddlConflictResolver.resolve(tablePlan, conflicts, keyColumns);
            if (resolved.isPresent()) {
                keptTables.add(resolved.get());
            } else {
                TableRef ref = TableRef.of(tablePlan.sourceTable());
                skippedOut.add(ref);
                skippedOut.addAll(graph.transitiveDependents(ref));
            }
        }
        keptTables.removeIf(t -> skippedOut.contains(TableRef.of(t.sourceTable())));
        return new MigrationPlan(plan.schemaFingerprint(), plan.generatedAt(), keptTables);
    }

    private Set<String> keyColumnsFor(TablePlan tablePlan, SchemaModel sourceSchema) {
        Set<String> keyColumns = new HashSet<>(tablePlan.naturalKey());
        sourceSchema.table(TableRef.of(tablePlan.sourceTable())).ifPresent(t -> keyColumns.addAll(t.primaryKeyColumns()));
        sourceSchema.foreignKeys().stream()
                .filter(fk -> fk.childTable().table().equalsIgnoreCase(tablePlan.sourceTable()))
                .forEach(fk -> keyColumns.addAll(fk.childColumns()));
        return keyColumns;
    }
}
