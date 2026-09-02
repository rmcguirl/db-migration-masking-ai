package com.migration.plan;

/**
 * Resolves {@code ai.provider} config to the matching {@code @AiProviderFor}-annotated
 * {@link AiPlanAnalyzer} bean — the same extensibility pattern as
 * {@code ConnectorRegistry} (design doc §6).
 */
public interface AiProviderRegistry {

    AiPlanAnalyzer resolve(String providerId);
}
