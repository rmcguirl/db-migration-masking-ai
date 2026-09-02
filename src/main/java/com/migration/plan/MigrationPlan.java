package com.migration.plan;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The full generated (or cached) plan for one source schema version: what AI/pattern
 * analysis (or a cache hit) decided about every table and column, keyed by
 * {@code schemaFingerprint}. Produced by {@code PlanGenerationService}, persisted in the
 * control-plane store, consumed directly by the load run, and emitted verbatim by
 * {@code --plan-only} (design doc §9b).
 *
 * @param schemaFingerprint the source schema hash this plan was generated for
 * @param generatedAt       when this plan was generated (not when it was last reused from cache)
 * @param tables             per-table plans
 */
public record MigrationPlan(String schemaFingerprint, Instant generatedAt, List<TablePlan> tables) {

    public MigrationPlan {
        if (schemaFingerprint == null || schemaFingerprint.isBlank()) {
            throw new IllegalArgumentException("schemaFingerprint must not be blank");
        }
        if (generatedAt == null) {
            throw new IllegalArgumentException("generatedAt must not be null");
        }
        tables = List.copyOf(tables == null ? List.of() : tables);
    }

    public Optional<TablePlan> table(String sourceTableName) {
        return tables.stream().filter(t -> t.sourceTable().equalsIgnoreCase(sourceTableName)).findFirst();
    }
}
