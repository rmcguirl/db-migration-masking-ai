package com.migration.domain;

/**
 * The engine-agnostic type vocabulary every {@code TypeMapper} translates native types
 * into and out of (see the type-mapping matrix in the design doc, §8). Kept intentionally
 * small and stable — a new database engine maps onto these kinds via its own
 * {@code TypeMapper}, never by adding a new kind here.
 */
public enum CanonicalTypeKind {
    STRING,
    TEXT,
    INTEGER,
    LONG,
    DECIMAL,
    BOOLEAN,
    DATE,
    TIMESTAMP,
    BINARY,
    UUID
}
