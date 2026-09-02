package com.migration.connector.oracle;

import com.migration.connector.api.TypeMapper;
import com.migration.domain.CanonicalType;
import com.migration.domain.CanonicalTypeKind;
import com.migration.domain.NativeTypeDescriptor;

/**
 * Oracle represents INTEGER, LONG, DECIMAL, and BOOLEAN all as the single native type
 * {@code NUMBER}, distinguished only by precision/scale (design doc §8:
 * INTEGER&rarr;number(10), LONG&rarr;number(19), DECIMAL&rarr;number(p,s),
 * BOOLEAN&rarr;number(1)). A plain one-native-name-to-one-canonical-kind YAML table (as
 * every other engine uses via {@code TypeMappingMatrix}) can't express that
 * disambiguation, since all four share the native type name "number". This class wraps a
 * {@code TypeMappingMatrix} (loaded from {@code oracle-type-mapping.yml}, which
 * deliberately omits "number") and special-cases "number" in Java on both directions.
 */
public class OracleTypeMapper implements TypeMapper {

    private final TypeMapper delegate;

    public OracleTypeMapper(TypeMapper delegate) {
        this.delegate = delegate;
    }

    @Override
    public CanonicalType toCanonical(NativeTypeDescriptor nativeType) {
        if (!"number".equals(nativeType.typeName())) {
            return delegate.toCanonical(nativeType);
        }
        Integer precision = nativeType.precision();
        Integer scale = nativeType.scale();
        if (scale != null && scale != 0) {
            return CanonicalType.decimal(precision == null ? 38 : precision, scale);
        }
        if (precision != null && precision == 1) {
            return CanonicalType.of(CanonicalTypeKind.BOOLEAN);
        }
        if (precision != null && precision <= 9) {
            return CanonicalType.of(CanonicalTypeKind.INTEGER);
        }
        if (precision != null && precision <= 19) {
            return CanonicalType.of(CanonicalTypeKind.LONG);
        }
        return CanonicalType.decimal(precision == null ? 38 : precision, scale == null ? 0 : scale);
    }

    @Override
    public NativeTypeDescriptor fromCanonical(CanonicalType canonicalType) {
        return switch (canonicalType.kind()) {
            case INTEGER -> NativeTypeDescriptor.decimal("number", 10, 0);
            case LONG -> NativeTypeDescriptor.decimal("number", 19, 0);
            case BOOLEAN -> NativeTypeDescriptor.decimal("number", 1, 0);
            case DECIMAL -> NativeTypeDescriptor.decimal("number",
                    canonicalType.precision() == null ? 38 : canonicalType.precision(),
                    canonicalType.scale() == null ? 0 : canonicalType.scale());
            // Oracle requires an explicit size for RAW columns; hardcode 16 (design doc §8
            // pairs UUID with raw(16) exactly) regardless of what the CanonicalType carries.
            case UUID -> NativeTypeDescriptor.sized("raw", 16);
            default -> delegate.fromCanonical(canonicalType);
        };
    }
}
