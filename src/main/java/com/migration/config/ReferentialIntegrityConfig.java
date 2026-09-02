package com.migration.config;

import jakarta.validation.Valid;

import java.util.List;

/** {@code migration.referentialIntegrity} — declared resolutions for FK cycles. */
public record ReferentialIntegrityConfig(@Valid List<CycleConfig> cycles) {

    public ReferentialIntegrityConfig {
        cycles = cycles == null ? List.of() : List.copyOf(cycles);
    }

    public static ReferentialIntegrityConfig empty() {
        return new ReferentialIntegrityConfig(List.of());
    }
}
