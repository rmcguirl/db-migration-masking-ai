package com.migration.connector.jdbc;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.migration.connector.api.TypeMapper;
import com.migration.domain.CanonicalType;
import com.migration.domain.CanonicalTypeKind;
import com.migration.domain.NativeTypeDescriptor;
import com.migration.domain.exception.ConfigurationException;
import com.migration.domain.exception.ConnectorException;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads a connector's native&harr;canonical type mapping from a YAML classpath resource
 * (design doc §8): adding a new engine, or a new mapping for an existing one, is a
 * resource-file change, never a code change. One instance per connector.
 */
public final class TypeMappingMatrix implements TypeMapper {

    private final Map<String, CanonicalTypeKind> nativeToCanonicalKind;
    private final Map<CanonicalTypeKind, String> canonicalKindToNativeType;

    private TypeMappingMatrix(Map<String, CanonicalTypeKind> nativeToCanonicalKind,
                               Map<CanonicalTypeKind, String> canonicalKindToNativeType) {
        this.nativeToCanonicalKind = nativeToCanonicalKind;
        this.canonicalKindToNativeType = canonicalKindToNativeType;
    }

    public static TypeMappingMatrix loadFromClasspath(String classpathLocation) {
        try (InputStream in = TypeMappingMatrix.class.getClassLoader().getResourceAsStream(classpathLocation)) {
            if (in == null) {
                throw new ConfigurationException("type mapping resource not found on classpath: " + classpathLocation);
            }
            TypeMappingResource resource = new YAMLMapper().readValue(in, TypeMappingResource.class);

            Map<String, CanonicalTypeKind> nativeToCanonical = new HashMap<>();
            for (TypeMappingResource.Entry entry : resource.nativeToCanonical()) {
                nativeToCanonical.put(entry.nativeType().trim().toLowerCase(),
                        CanonicalTypeKind.valueOf(entry.canonical().trim().toUpperCase()));
            }

            Map<CanonicalTypeKind, String> canonicalToNative = new EnumMap<>(CanonicalTypeKind.class);
            resource.canonicalToNative().forEach((kind, nativeTypeName) ->
                    canonicalToNative.put(CanonicalTypeKind.valueOf(kind.trim().toUpperCase()), nativeTypeName));

            return new TypeMappingMatrix(Map.copyOf(nativeToCanonical), Map.copyOf(canonicalToNative));
        } catch (IOException e) {
            throw new ConfigurationException("failed to load type mapping resource: " + classpathLocation, e);
        }
    }

    @Override
    public CanonicalType toCanonical(NativeTypeDescriptor nativeType) {
        CanonicalTypeKind kind = nativeToCanonicalKind.get(nativeType.typeName());
        if (kind == null) {
            throw new ConnectorException("no canonical mapping for native type '" + nativeType.typeName() + "'");
        }
        return new CanonicalType(kind, nativeType.length(), nativeType.precision(), nativeType.scale());
    }

    @Override
    public NativeTypeDescriptor fromCanonical(CanonicalType canonicalType) {
        String nativeTypeName = canonicalKindToNativeType.get(canonicalType.kind());
        if (nativeTypeName == null) {
            throw new ConnectorException("no native mapping for canonical type '" + canonicalType.kind() + "'");
        }
        return new NativeTypeDescriptor(nativeTypeName, canonicalType.length(), canonicalType.precision(),
                canonicalType.scale());
    }
}
