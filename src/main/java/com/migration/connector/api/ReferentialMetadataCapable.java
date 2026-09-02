package com.migration.connector.api;

import com.migration.domain.ForeignKeyRef;

import java.util.List;

/**
 * Optional capability: introspecting enforced FK constraints. Not implemented by
 * document-store connectors (no FK constraints exist to introspect); the orchestrator
 * relies solely on config-declared virtual FKs in that case (design doc §3, §5).
 */
public interface ReferentialMetadataCapable {

    List<ForeignKeyRef> introspectForeignKeys();
}
