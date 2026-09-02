package com.migration.orchestrator;

import com.migration.domain.TableRef;

/** Appends structured audit records to the control-plane store (design doc §2, §10). */
public interface AuditLogService {

    void recordRunStart(RunContext ctx);

    void recordTableResult(RunContext ctx, TableRef table, TableRunOutcome outcome);

    void recordRunEnd(RunContext ctx, RunResult result);
}
