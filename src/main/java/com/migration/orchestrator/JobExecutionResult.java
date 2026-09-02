package com.migration.orchestrator;

import com.migration.domain.TableRef;

import java.util.Map;

/** Per-table outcomes for one {@code MigrationJobService.loadAll(...)} call. */
public record JobExecutionResult(Map<TableRef, TableRunOutcome> tableOutcomes) {

    public JobExecutionResult {
        tableOutcomes = Map.copyOf(tableOutcomes == null ? Map.of() : tableOutcomes);
    }

    public boolean hasFailuresOrSkips() {
        return tableOutcomes.values().stream().anyMatch(o -> o.status() != TableOutcomeStatus.COMPLETED);
    }
}
