package com.migration.config;

import jakarta.validation.constraints.NotBlank;

/**
 * A human-forced masking technique for one column, taking precedence over AI/pattern
 * classification. {@code technique} is validated against the real
 * {@code MaskingTechniqueId} palette by plan generation, not here — the config layer
 * has no dependency on the masking package (design doc §9a).
 */
public record MaskingOverride(
        @NotBlank String table,
        @NotBlank String column,
        @NotBlank String technique) {
}
