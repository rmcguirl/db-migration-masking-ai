package com.migration.referential;

/**
 * The resolved outcome for one detected FK cycle (design doc §5) — a superset of
 * {@code config.CycleStrategy}, since {@code UNSUPPORTED} is a resolved state, never a
 * value a user configures.
 */
public enum CycleStrategyOutcome {
    DEFERRED_TRANSACTION,
    DECLARED_ORDER,
    UNSUPPORTED
}
