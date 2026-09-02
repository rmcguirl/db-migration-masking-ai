package com.migration.orchestrator;

import java.time.Instant;

/**
 * Identity and timing for one run, threaded through {@code AuditLogService} calls and
 * used as the Spring Batch {@code JobParameters} identity for resumability (design doc
 * §7 step 10: re-invoking with the same schema fingerprint + run id resumes a failed job
 * rather than restarting it).
 */
public record RunContext(String runId, String schemaFingerprint, RunMode mode, Instant startedAt) {

    public static RunContext start(String runId, String schemaFingerprint, RunMode mode) {
        return new RunContext(runId, schemaFingerprint, mode, Instant.now());
    }
}
