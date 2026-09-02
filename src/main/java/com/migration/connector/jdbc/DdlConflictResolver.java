package com.migration.connector.jdbc;

import com.migration.plan.ColumnPlan;
import com.migration.plan.DdlConflict;
import com.migration.plan.TablePlan;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Excludes a conflicted (present-but-different) column from a table's plan, or excludes
 * the entire table when the conflict touches a key column — since correctness can't be
 * guaranteed once a primary/foreign/natural-key column's type is wrong (design doc §3).
 * Used by the orchestrator to filter a {@code MigrationPlan} before handing it to
 * {@code MigrationJobService}, so table loading never needs to know about DDL conflicts
 * itself.
 */
@Component
public class DdlConflictResolver {

    /** Empty means the whole table must be skipped this run. */
    public Optional<TablePlan> resolve(TablePlan tablePlan, List<DdlConflict> conflictsForTable, Set<String> keyColumns) {
        if (conflictsForTable.isEmpty()) {
            return Optional.of(tablePlan);
        }
        Set<String> conflictedColumns = conflictsForTable.stream().map(DdlConflict::column).collect(Collectors.toSet());
        boolean keyColumnConflicted = conflictedColumns.stream().anyMatch(keyColumns::contains);
        if (keyColumnConflicted) {
            return Optional.empty();
        }
        List<ColumnPlan> filteredColumns = tablePlan.columns().stream()
                .filter(c -> !conflictedColumns.contains(c.source()))
                .toList();
        return Optional.of(new TablePlan(tablePlan.sourceTable(), tablePlan.destinationTable(), tablePlan.naturalKey(),
                tablePlan.loadOrder(), tablePlan.dependsOn(), filteredColumns, tablePlan.ddlActions()));
    }
}
