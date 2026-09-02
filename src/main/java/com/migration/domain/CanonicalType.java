package com.migration.domain;

/**
 * A canonical type together with the parameters that give it a concrete shape
 * ({@code length} for STRING/BINARY, {@code precision}/{@code scale} for DECIMAL).
 * Carrying the parameters on the type itself (rather than as a sibling field on
 * {@link ColumnDescriptor}) is what lets {@code TypeMapper.fromCanonical(CanonicalType)}
 * produce a fully-specified native type from this value alone.
 *
 * @param kind      the engine-agnostic type
 * @param length    character/byte length, only meaningful for STRING/BINARY; null otherwise
 * @param precision total digits, only meaningful for DECIMAL; null otherwise
 * @param scale     digits after the decimal point, only meaningful for DECIMAL; null otherwise
 */
public record CanonicalType(CanonicalTypeKind kind, Integer length, Integer precision, Integer scale) {

    public CanonicalType {
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
    }

    public static CanonicalType of(CanonicalTypeKind kind) {
        return new CanonicalType(kind, null, null, null);
    }

    public static CanonicalType string(int length) {
        return new CanonicalType(CanonicalTypeKind.STRING, length, null, null);
    }

    public static CanonicalType decimal(int precision, int scale) {
        return new CanonicalType(CanonicalTypeKind.DECIMAL, null, precision, scale);
    }

    public static CanonicalType binary(int length) {
        return new CanonicalType(CanonicalTypeKind.BINARY, length, null, null);
    }
}
