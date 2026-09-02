package com.migration.orchestrator;

/** One table's result for a run: status, row counts (for {@code COMPLETED}), and an error (for {@code FAILED}). */
public record TableRunOutcome(TableOutcomeStatus status, long rowsInserted, long rowsUpdated, String errorMessage) {

    public static TableRunOutcome completed(long rowsInserted, long rowsUpdated) {
        return new TableRunOutcome(TableOutcomeStatus.COMPLETED, rowsInserted, rowsUpdated, null);
    }

    public static TableRunOutcome skipped(String reason) {
        return new TableRunOutcome(TableOutcomeStatus.SKIPPED, 0, 0, reason);
    }

    public static TableRunOutcome failed(String errorMessage) {
        return new TableRunOutcome(TableOutcomeStatus.FAILED, 0, 0, errorMessage);
    }
}
