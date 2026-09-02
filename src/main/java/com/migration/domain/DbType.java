package com.migration.domain;

/**
 * The engine identifier used throughout the app to resolve a connector, a type-mapping
 * resource, and (for relational engines) a {@code SqlDialect}. Deliberately a normalized
 * string wrapper rather than an enum: adding a new supported engine (e.g. "db2") must be
 * possible via a new {@code @ConnectorFor}-annotated bean, never by editing this type
 * (design doc §3).
 *
 * @param id the lower-cased engine identifier, e.g. "postgres", "mysql", "mongodb"
 */
public record DbType(String id) {

    public DbType {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("db type id must not be blank");
        }
        id = id.trim().toLowerCase();
    }

    public static DbType of(String id) {
        return new DbType(id);
    }

    @Override
    public String toString() {
        return id;
    }
}
