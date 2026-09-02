package com.migration.domain.exception;

/**
 * Thrown by plan generation (and its validators, e.g. key-column injectivity checks
 * and FK-cycle load-strategy checks) when a {@code MigrationPlan} would be invalid to
 * execute — for example a non-injective masking technique assigned to a key column, or
 * a {@code DEFERRED_TRANSACTION} cycle strategy declared against a connector that
 * doesn't support deferred constraints. Always thrown before any write happens: plan
 * generation fails fast and the run never starts.
 */
public class PlanValidationException extends MigrationException {

    public PlanValidationException(String message) {
        super(message);
    }

    public PlanValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
