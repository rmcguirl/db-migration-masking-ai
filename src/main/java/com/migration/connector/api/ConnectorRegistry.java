package com.migration.connector.api;

/**
 * Resolves the configured {@code type:} string to a concrete {@link SourceConnector}/
 * {@link DestinationConnector} bean, indexed by {@code @ConnectorFor} value. Adding a new
 * database type never touches this contract or its resolution logic (design doc §3).
 */
public interface ConnectorRegistry {

    SourceConnector resolveSource(String dbType);

    DestinationConnector resolveDestination(String dbType);
}
