package com.migration.domain;

/**
 * An engine-native type as reported by introspection (e.g. Postgres {@code numeric(10,2)},
 * Oracle {@code VARCHAR2(255)}, Mongo's observed BSON type for a field), or as produced by
 * a {@code TypeMapper} for DDL generation. {@code typeName} is the engine's own type
 * keyword, lower-cased for stable comparison; unused parameters are null.
 *
 * @param typeName  the native type keyword (e.g. "varchar", "number", "int32")
 * @param length    character/byte length, where applicable; null otherwise
 * @param precision total digits, where applicable; null otherwise
 * @param scale     digits after the decimal point, where applicable; null otherwise
 */
public record NativeTypeDescriptor(String typeName, Integer length, Integer precision, Integer scale) {

    public NativeTypeDescriptor {
        if (typeName == null || typeName.isBlank()) {
            throw new IllegalArgumentException("typeName must not be blank");
        }
        typeName = typeName.trim().toLowerCase();
    }

    public static NativeTypeDescriptor of(String typeName) {
        return new NativeTypeDescriptor(typeName, null, null, null);
    }

    public static NativeTypeDescriptor sized(String typeName, int length) {
        return new NativeTypeDescriptor(typeName, length, null, null);
    }

    public static NativeTypeDescriptor decimal(String typeName, int precision, int scale) {
        return new NativeTypeDescriptor(typeName, null, precision, scale);
    }
}
