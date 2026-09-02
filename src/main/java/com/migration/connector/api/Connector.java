package com.migration.connector.api;

import com.migration.domain.DbType;

/**
 * The base contract every connector implements, regardless of engine or which side
 * (source/destination) it's used on. Optional capabilities ({@link DdlCapable},
 * {@link TransactionalBatchWrite}, {@link ReferentialMetadataCapable}) are checked via
 * {@code instanceof} at the call site rather than declared here, so an engine that
 * doesn't support DDL or FK metadata (e.g. MongoDB) simply doesn't implement them
 * (design doc §3).
 */
public interface Connector {

    DbType type();

    /** Verifies connectivity and credentials; throws {@code ConnectorException} on failure. */
    void testConnection();

    TypeMapper typeMapper();
}
