package com.migration.orchestrator;

/**
 * The top-level result of one {@code MigrationOrchestrator.run(...)} invocation, mapped
 * by the CLI to a process exit code (design doc §8): 0 for {@code SUCCESS}, a distinct
 * non-zero code for {@code PARTIAL_FAILURE} vs {@code HARD_FAILURE} so scripts/CI can
 * tell "some tables didn't load" apart from "the run never started."
 */
public record RunResult(RunOutcome outcome, String message) {

    public static RunResult success(String message) {
        return new RunResult(RunOutcome.SUCCESS, message);
    }

    public static RunResult partialFailure(String message) {
        return new RunResult(RunOutcome.PARTIAL_FAILURE, message);
    }

    public static RunResult hardFailure(String message) {
        return new RunResult(RunOutcome.HARD_FAILURE, message);
    }
}
