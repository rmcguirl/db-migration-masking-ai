package com.migration.connector.mongodb;

import org.bson.types.Decimal128;
import org.bson.types.ObjectId;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Derives a BSON type-name label (matching {@code mongodb-type-mapping.yml}'s
 * {@code nativeType} keys) from a sampled field value's Java runtime type. Mongo has no
 * {@code DatabaseMetaData} equivalent, so this — not driver metadata — is how
 * {@code MongoSourceConnector}'s sampling-based introspection classifies a field
 * (design doc's non-relational-support flag: "schema introspection becomes inference,
 * not fact").
 */
final class BsonTypeNames {

    private BsonTypeNames() {
    }

    static String of(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof ObjectId) {
            return "objectid";
        }
        if (value instanceof Integer) {
            return "int32";
        }
        if (value instanceof Long) {
            return "int64";
        }
        if (value instanceof Double || value instanceof Float) {
            return "double";
        }
        if (value instanceof Decimal128) {
            return "decimal128";
        }
        if (value instanceof Boolean) {
            return "bool";
        }
        if (value instanceof Date) {
            return "date";
        }
        if (value instanceof byte[]) {
            return "bindata";
        }
        if (value instanceof List) {
            return "array";
        }
        if (value instanceof Map) {
            return "object";
        }
        return "string";
    }
}
