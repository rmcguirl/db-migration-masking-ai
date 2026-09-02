package com.migration.domain;

import java.util.List;
import java.util.Optional;

/**
 * The full set of tables/collections introspected (or, for a document-store source,
 * inferred by sampling) from one source or destination connector at a point in time,
 * plus any introspected FK edges (empty for an engine with no enforced constraints, e.g.
 * MongoDB). This is what the schema fingerprinter hashes to key the plan cache, and what
 * {@code ReferentialGraphBuilder} reads FK edges from — merged with config-declared
 * virtual FKs — to build the load-order dependency graph (design doc §5).
 *
 * @param tables      the tables/collections in this schema
 * @param foreignKeys FK edges introspected across every table in {@code tables}
 */
public record SchemaModel(List<TableDescriptor> tables, List<ForeignKeyRef> foreignKeys) {

    public SchemaModel {
        tables = List.copyOf(tables == null ? List.of() : tables);
        foreignKeys = List.copyOf(foreignKeys == null ? List.of() : foreignKeys);
    }

    public static SchemaModel of(List<TableDescriptor> tables) {
        return new SchemaModel(tables, List.of());
    }

    public static SchemaModel of(List<TableDescriptor> tables, List<ForeignKeyRef> foreignKeys) {
        return new SchemaModel(tables, foreignKeys);
    }

    public Optional<TableDescriptor> table(TableRef ref) {
        return tables.stream().filter(t -> t.ref().equals(ref)).findFirst();
    }
}
