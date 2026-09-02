package com.migration.orchestrator;

/** One table's outcome for a run (design doc §7 step 9). */
public enum TableOutcomeStatus {
    COMPLETED,
    /** Skipped as a transitive dependent of a failed/blocked table, or as part of an UNSUPPORTED cycle. */
    SKIPPED,
    FAILED
}
