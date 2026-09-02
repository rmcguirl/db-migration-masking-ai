package com.migration.config;

import jakarta.validation.Valid;

import java.util.List;

/**
 * {@code migration.overrides} — everything a human forces rather than leaves to AI
 * analysis. Empty lists (the default) mean "defer entirely to AI + pattern rules."
 */
public record OverridesConfig(
        @Valid List<NaturalKeyOverride> naturalKeys,
        @Valid List<MaskingOverride> masking,
        @Valid List<ForceSensitiveOverride> forceSensitive,
        @Valid List<VirtualForeignKeyConfig> virtualForeignKeys) {

    public OverridesConfig {
        naturalKeys = naturalKeys == null ? List.of() : List.copyOf(naturalKeys);
        masking = masking == null ? List.of() : List.copyOf(masking);
        forceSensitive = forceSensitive == null ? List.of() : List.copyOf(forceSensitive);
        virtualForeignKeys = virtualForeignKeys == null ? List.of() : List.copyOf(virtualForeignKeys);
    }

    public static OverridesConfig empty() {
        return new OverridesConfig(List.of(), List.of(), List.of(), List.of());
    }
}
