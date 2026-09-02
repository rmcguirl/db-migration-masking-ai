package com.migration.connector.api;

import com.migration.domain.SchemaModel;
import com.migration.plan.DdlPlan;

/**
 * Optional capability: additive-only DDL diff/apply. Implemented only by engines that
 * support DDL (every relational engine); a document-store connector like MongoDB simply
 * doesn't implement this, and the orchestrator checks {@code instanceof} and skips that
 * step cleanly rather than calling a no-op (design doc §3).
 */
public interface DdlCapable {

    /** Diffs the desired schema against the destination's actually introspected schema. Never mutates. */
    DdlPlan diffSchema(SchemaModel desired, SchemaModel existing);

    /** Applies only the additive actions in {@code plan} (creates/adds); conflicts are never auto-resolved. */
    void applyDdl(DdlPlan plan);
}
