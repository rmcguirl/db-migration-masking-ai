package com.migration.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code migration.run} — chunk size and cross-table parallelism for the batch load
 * (design doc §7, §10). Defaults mirror the design-time performance guideline in §10.
 */
public record RunConfig(
        @Min(1) @DefaultValue("5000") int batchSize,
        @Min(1) @DefaultValue("4") int parallelism) {
}
