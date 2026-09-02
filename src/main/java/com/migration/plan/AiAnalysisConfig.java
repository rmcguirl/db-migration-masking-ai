package com.migration.plan;

/**
 * The subset of {@code config.AiConfig} an {@code AiPlanAnalyzer} implementation needs,
 * derived by {@code PlanGenerationService} at call time so the analyzer contract doesn't
 * depend on the full config tree.
 *
 * @param confidenceThreshold below this, the caller treats the result as low-confidence
 * @param allowDataSampling   whether the analyzer is permitted to include raw sample
 *                            values in its request payload (opt-in only; default false)
 */
public record AiAnalysisConfig(double confidenceThreshold, boolean allowDataSampling) {
}
