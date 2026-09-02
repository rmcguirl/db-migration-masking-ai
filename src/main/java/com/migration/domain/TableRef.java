package com.migration.domain;

/**
 * A qualified reference to a table (relational) or collection (document-store).
 * {@code catalog}/{@code schema} are null when the engine or the table has no such
 * qualifier (e.g. MySQL has no separate schema concept, Mongo has neither) — a graph
 * node is really {@code (catalog, schema, table)} so a cross-schema or cross-catalog FK
 * is just an edge between two differently-qualified refs (design doc §5).
 *
 * @param catalog optional catalog/database qualifier; null if not applicable
 * @param schema  optional schema qualifier; null if not applicable
 * @param table   the table/collection name; required
 */
public record TableRef(String catalog, String schema, String table) {

    public TableRef {
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("table must not be blank");
        }
    }

    public static TableRef of(String table) {
        return new TableRef(null, null, table);
    }

    public static TableRef of(String schema, String table) {
        return new TableRef(null, schema, table);
    }

    public static TableRef of(String catalog, String schema, String table) {
        return new TableRef(catalog, schema, table);
    }

    /** A human-readable, fully-qualified name for logs/reports/error messages. */
    public String qualifiedName() {
        StringBuilder sb = new StringBuilder();
        if (catalog != null && !catalog.isBlank()) {
            sb.append(catalog).append('.');
        }
        if (schema != null && !schema.isBlank()) {
            sb.append(schema).append('.');
        }
        return sb.append(table).toString();
    }
}
