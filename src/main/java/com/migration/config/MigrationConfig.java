package com.migration.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The full, immutable configuration tree bound from {@code migration.*} in
 * {@code application.yml} plus an externally supplied
 * {@code --spring.config.additional-location=file:./migration.yml} (design doc §8, §9a).
 * Bean Validation annotations on this tree catch malformed config at startup, before any
 * connection attempt; cross-field checks Bean Validation can't express (an unknown
 * {@code ai.provider}, a {@code DECLARED_ORDER} cycle missing {@code loadOrder}) are
 * checked explicitly against this bound object and throw {@code ConfigurationException}.
 */
@ConfigurationProperties(prefix = "migration")
@Validated
public record MigrationConfig(
        @Valid @NotNull SourceConfig source,
        @Valid @NotNull DestinationConfig destination,
        @Valid @DefaultValue RunConfig run,
        @Valid @DefaultValue OverridesConfig overrides,
        @Valid @NotNull AiConfig ai,
        @Valid @NotNull MaskingConfig masking,
        @Valid @DefaultValue ReferentialIntegrityConfig referentialIntegrity,
        @Valid @DefaultValue ControlPlaneConfig controlPlane) {
}
