package com.migration.domain;

import java.util.List;

/**
 * One table (or inferred collection shape) within a {@link SchemaModel}.
 *
 * @param ref               qualified table/collection reference
 * @param columns           the table's columns/fields, in introspection order
 * @param primaryKeyColumns names of the columns making up the primary key, in key order;
 *                          empty if the table (or engine) has none
 * @param comment           table-level comment, if any; null otherwise
 */
public record TableDescriptor(TableRef ref, List<ColumnDescriptor> columns, List<String> primaryKeyColumns,
                               String comment) {

    public TableDescriptor {
        if (ref == null) {
            throw new IllegalArgumentException("ref must not be null");
        }
        columns = List.copyOf(columns == null ? List.of() : columns);
        primaryKeyColumns = List.copyOf(primaryKeyColumns == null ? List.of() : primaryKeyColumns);
    }

    public static TableDescriptor of(TableRef ref, List<ColumnDescriptor> columns, List<String> primaryKeyColumns) {
        return new TableDescriptor(ref, columns, primaryKeyColumns, null);
    }

    public ColumnDescriptor column(String name) {
        return columns.stream()
                .filter(c -> c.name().equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "no such column '" + name + "' on table " + ref.qualifiedName()));
    }
}
