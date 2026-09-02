package com.migration.config;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * One declared resolution for a detected table-level FK cycle (design doc §5, §9a).
 * {@code loadOrder} is required only when {@code strategy} is
 * {@code DECLARED_ORDER} — enforced as a cross-field check at startup
 * ({@code ConfigurationException}), not expressible via Bean Validation alone.
 */
public record CycleConfig(
        @NotEmpty List<String> tables,
        @NotNull CycleStrategy strategy,
        List<String> loadOrder) {

    public CycleConfig {
        loadOrder = loadOrder == null ? List.of() : List.copyOf(loadOrder);
    }
}
