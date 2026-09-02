package com.migration.domain.exception;

/**
 * Wraps underlying driver/client failures (JDBC {@code SQLException}, MongoDB driver
 * exceptions, network errors) surfaced from {@code testConnection()},
 * {@code introspectSchema()}, {@code extract()}, {@code upsert()} or {@code applyDdl()},
 * so callers catch one type regardless of which engine a connector talks to.
 */
public class ConnectorException extends MigrationException {

    public ConnectorException(String message) {
        super(message);
    }

    public ConnectorException(String message, Throwable cause) {
        super(message, cause);
    }
}
