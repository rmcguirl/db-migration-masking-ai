package com.migration.connector.jdbc;

import java.util.List;
import java.util.Map;

/**
 * The shape of a per-connector type-mapping YAML resource (design doc §8): a list of
 * native-type-to-canonical-kind entries (used for introspection) and a reverse map of
 * canonical kind to a default native type name (used for DDL generation).
 */
public record TypeMappingResource(List<Entry> nativeToCanonical, Map<String, String> canonicalToNative) {

    public record Entry(String nativeType, String canonical) {
    }
}
