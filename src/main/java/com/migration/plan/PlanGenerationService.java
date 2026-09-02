package com.migration.plan;

import com.migration.config.MigrationConfig;
import com.migration.domain.SchemaModel;

/**
 * Orchestrates plan generation: cache lookup, else pattern scan + AI analysis, merge
 * with user overrides, apply the confidence fallback policy, validate key-column
 * technique constraints, and persist (design doc §2).
 */
public interface PlanGenerationService {

    /**
     * @param schemaFingerprint computed by the caller (via {@code SchemaFingerprinter})
     *                          and passed in rather than recomputed here
     */
    MigrationPlan getOrGeneratePlan(String schemaFingerprint, SchemaModel sourceSchema, MigrationConfig config);
}
