package com.migration.orchestrator;

/** Drives the end-to-end sequence for both run modes; owns top-level error handling (design doc §2). */
public interface MigrationOrchestrator {

    RunResult run(RunMode mode);
}
