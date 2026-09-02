package com.migration.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code migration.ai} — selects and configures the {@code AiPlanAnalyzer}
 * implementation via {@code provider}, resolved the same way {@code ConnectorRegistry}
 * resolves connectors (design doc §6). {@code provider} is validated against the real
 * {@code @AiProviderFor} registry at startup (a cross-field check the config layer
 * itself can't express), not here.
 *
 * @param enabled             whether AI analysis is permitted at all; when {@code false}
 *                            plan generation relies solely on pattern rules + overrides
 * @param provider            the {@code @AiProviderFor} id to resolve
 * @param confidenceThreshold below this, an AI classification falls back to the most
 *                            conservative technique for the column's inferred type
 *                            rather than being trusted (design doc §6)
 * @param allowDataSampling   opt-in to sending raw sample values (never on by default)
 * @param sampleSize          rows sampled per column for pattern-rule scanning
 */
public record AiConfig(
        @DefaultValue("true") boolean enabled,
        @NotBlank String provider,
        @DecimalMin("0.0") @DecimalMax("1.0") @DefaultValue("0.85") double confidenceThreshold,
        @DefaultValue("false") boolean allowDataSampling,
        @Min(1) @DefaultValue("20") int sampleSize) {
}
