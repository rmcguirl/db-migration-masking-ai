package com.migration.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code migration.controlPlane} — the durable, always-relational store for the plan
 * cache, Spring Batch's {@code JobRepository}, and the audit log (design doc §1
 * assumption 5). Defaults to an embedded, file-backed H2 database; swappable to a
 * managed Postgres instance via {@code datastore} with no code change.
 */
public record ControlPlaneConfig(
        @NotBlank @DefaultValue("jdbc:h2:file:./control-plane/migration-control") String datastore) {
}
