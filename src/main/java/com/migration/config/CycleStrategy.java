package com.migration.config;

/**
 * How a detected table-level FK cycle is handled (design doc §5). A cycle with no
 * matching {@code referentialIntegrity.cycles[]} entry defaults to {@code UNSUPPORTED}
 * at runtime — that outcome is never itself a config value, only a resolved state.
 */
public enum CycleStrategy {
    /** Requires every connector in the cycle to support deferred FK constraints. */
    DEFERRED_TRANSACTION,
    /** A human-declared linear load order; the violating FK edge is never enforced. */
    DECLARED_ORDER
}
