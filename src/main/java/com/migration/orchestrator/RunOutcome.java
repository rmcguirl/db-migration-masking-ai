package com.migration.orchestrator;

/**
 * The overall run outcome, distinct from any single table's outcome (design doc §7 step
 * 9): a run can complete with a mix of per-table results and still be
 * {@code PARTIAL_FAILURE} overall, without that meaning the whole job failed.
 */
public enum RunOutcome {
    /** Plan-only report written, or every table loaded (or was legitimately skipped as a transitive dependent). */
    SUCCESS,
    /** At least one table failed or was blocked (a DDL conflict, an UNSUPPORTED cycle); others may have completed. */
    PARTIAL_FAILURE,
    /** The run never got far enough to attempt any table load (e.g. a configuration or connection failure). */
    HARD_FAILURE
}
