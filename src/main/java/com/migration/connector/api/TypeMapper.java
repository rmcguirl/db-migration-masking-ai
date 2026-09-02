package com.migration.connector.api;

import com.migration.domain.CanonicalType;
import com.migration.domain.NativeTypeDescriptor;

/**
 * The per-connector type-mapping hook: native-to-canonical for introspection,
 * canonical-to-native for DDL generation. Each connector supplies this as a
 * Spring-loaded config resource (a YAML type-mapping matrix), not conditional Java logic
 * — adding an engine or a new mapping is a config change, not a code change (design doc §8).
 */
public interface TypeMapper {

    CanonicalType toCanonical(NativeTypeDescriptor nativeType);

    NativeTypeDescriptor fromCanonical(CanonicalType canonicalType);
}
