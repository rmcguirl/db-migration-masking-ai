package com.migration.plan;

import java.util.Optional;

/**
 * Reads/writes cached {@link MigrationPlan}s in the control-plane store, keyed by schema
 * fingerprint, so a plan survives between a {@code --plan-only} run and a later load run
 * (design doc §2).
 */
public interface PlanCacheRepository {

    Optional<MigrationPlan> findByFingerprint(String schemaFingerprint);

    void save(MigrationPlan plan);
}
