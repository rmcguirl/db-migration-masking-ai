package com.migration.domain.exception;

/**
 * Root of the unchecked exception hierarchy for this application. Every layer
 * (connectors, plan generation, masking, orchestration) throws a subtype of this
 * rather than a checked exception, so callers can catch broadly at a boundary
 * (e.g. the CLI entry point) while still being able to catch narrowly where needed.
 */
public class MigrationException extends RuntimeException {

    public MigrationException(String message) {
        super(message);
    }

    public MigrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
