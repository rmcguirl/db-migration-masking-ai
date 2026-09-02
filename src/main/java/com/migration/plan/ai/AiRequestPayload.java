package com.migration.plan.ai;

import java.util.List;

/**
 * The full outbound request built by {@link AbstractAiPlanAnalyzer} for one
 * {@code analyze()} call — one entry per table (design doc §6's per-table batching; this
 * reference implementation sends one request for the whole schema rather than the
 * token-budget-aware multi-table batching §6 describes for very large schemas, a
 * documented simplification since call-count optimization doesn't change correctness).
 */
public record AiRequestPayload(List<AiTableRequest> tables) {
}
