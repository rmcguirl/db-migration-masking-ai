package com.migration.domain;

/**
 * One column (or, for a document-store connector's inferred schema, one observed field)
 * as reported by {@code introspectSchema()}. {@code nativeType} is authoritative for a
 * relational source; for a document-store source it is the inferred, unioned native
 * shape from sampling (design doc's "non-relational support" flag).
 *
 * @param name          column/field name
 * @param canonicalType the type mapped via the owning connector's {@code TypeMapper}
 * @param nativeType    the engine-native type as introspected
 * @param nullable      whether the column allows nulls (or, for Mongo, was observed absent/null)
 * @param primaryKey    whether this column participates in the table's primary key
 * @param comment       column/table-level comment, if any; null otherwise
 */
public record ColumnDescriptor(
        String name,
        CanonicalType canonicalType,
        NativeTypeDescriptor nativeType,
        boolean nullable,
        boolean primaryKey,
        String comment) {

    public ColumnDescriptor {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (canonicalType == null) {
            throw new IllegalArgumentException("canonicalType must not be null");
        }
    }

    public static ColumnDescriptor of(String name, CanonicalType canonicalType, NativeTypeDescriptor nativeType,
                                       boolean nullable, boolean primaryKey) {
        return new ColumnDescriptor(name, canonicalType, nativeType, nullable, primaryKey, null);
    }
}
