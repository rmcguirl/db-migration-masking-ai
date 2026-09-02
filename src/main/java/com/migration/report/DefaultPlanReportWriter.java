package com.migration.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.migration.domain.exception.MigrationException;
import com.migration.orchestrator.PlanReportWriter;
import com.migration.orchestrator.ReportSink;
import com.migration.plan.ColumnPlan;
import com.migration.plan.DdlAction;
import com.migration.plan.DdlConflict;
import com.migration.plan.DdlPlan;
import com.migration.plan.MigrationPlan;
import com.migration.plan.TablePlan;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Locale;

/**
 * Renders a {@code --plan-only} report in both forms design doc §7 calls for: a
 * human-readable summary (proposed tables/columns, per-column masking assignment plus
 * confidence/source, natural keys, DDL actions and conflicts) and the machine-readable
 * plan/diff as JSON.
 */
@Component
public class DefaultPlanReportWriter implements PlanReportWriter {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Override
    public void write(MigrationPlan plan, DdlPlan ddlDiff, ReportSink sink) {
        sink.writeJson(toJson(plan, ddlDiff));
        sink.writeHumanReadable(toHumanReadable(plan, ddlDiff));
    }

    private String toJson(MigrationPlan plan, DdlPlan ddlDiff) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(new PlanReport(plan, ddlDiff));
        } catch (Exception e) {
            throw new MigrationException("failed to render plan report as JSON", e);
        }
    }

    private String toHumanReadable(MigrationPlan plan, DdlPlan ddlDiff) {
        StringBuilder out = new StringBuilder();
        out.append("Migration Plan Report\n");
        out.append("Schema fingerprint: ").append(plan.schemaFingerprint()).append('\n');
        out.append("Generated at: ").append(plan.generatedAt()).append("\n\n");

        plan.tables().stream()
                .sorted(Comparator.comparingInt(TablePlan::loadOrder))
                .forEach(table -> appendTable(out, table));

        out.append("DDL actions:\n");
        if (ddlDiff.actions().isEmpty()) {
            out.append("  (none — destination already matches)\n");
        }
        for (DdlAction action : ddlDiff.actions()) {
            out.append("  ").append(describeAction(action)).append('\n');
        }

        if (ddlDiff.hasConflicts()) {
            out.append("\nDDL CONFLICTS (not applied — run-blocking for the affected column/table):\n");
            for (DdlConflict conflict : ddlDiff.conflicts()) {
                out.append("  ").append(conflict.table().qualifiedName()).append('.').append(conflict.column())
                        .append(": expected ").append(conflict.expectedType()).append(", found ")
                        .append(conflict.actualType()).append(" — ").append(conflict.reason()).append('\n');
            }
        }
        return out.toString();
    }

    private void appendTable(StringBuilder out, TablePlan table) {
        out.append("Table: ").append(table.sourceTable());
        if (!table.sourceTable().equalsIgnoreCase(table.destinationTable())) {
            out.append(" -> ").append(table.destinationTable());
        }
        out.append(" (load order ").append(table.loadOrder()).append(")\n");
        out.append("  Natural key: ").append(table.naturalKey()).append('\n');
        if (!table.dependsOn().isEmpty()) {
            out.append("  Depends on: ").append(table.dependsOn()).append('\n');
        }
        for (ColumnPlan column : table.columns()) {
            if (column.sensitive()) {
                out.append(String.format(Locale.ROOT, "  [MASK] %-24s -> %-24s technique=%-20s confidence=%.2f source=%-22s basis=%s%n",
                        column.source(), column.destination(), column.masking().technique(), column.confidence(),
                        column.decisionSource().wireValue(), column.regulatoryBasis()));
            } else {
                out.append(String.format(Locale.ROOT, "  [ ok ] %s%n", column.source()));
            }
        }
        out.append('\n');
    }

    private String describeAction(DdlAction action) {
        return switch (action.type()) {
            case CREATE_SCHEMA -> "CREATE_SCHEMA " + action.schemaName();
            case CREATE_TABLE -> "CREATE_TABLE " + action.table().qualifiedName();
            case ADD_COLUMN -> "ADD_COLUMN " + action.table().qualifiedName() + "." + action.columnDescriptor().name();
        };
    }

    private record PlanReport(MigrationPlan plan, DdlPlan ddlDiff) {
    }
}
