package com.migration.config;

import jakarta.validation.constraints.NotBlank;

/**
 * Forces a column into "sensitive" regardless of AI/pattern verdict — the fallback
 * asymmetry in design doc §6 (rules/overrides may only push a column toward masking,
 * never pull one out) applies to this override too.
 */
public record ForceSensitiveOverride(
        @NotBlank String table,
        @NotBlank String column) {
}
