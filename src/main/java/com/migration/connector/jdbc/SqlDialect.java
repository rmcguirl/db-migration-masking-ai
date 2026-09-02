package com.migration.connector.jdbc;

import com.migration.domain.ColumnDescriptor;
import com.migration.domain.DbType;
import com.migration.domain.NativeTypeDescriptor;
import com.migration.domain.TableDescriptor;
import com.migration.domain.TableRef;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Engine-specific SQL syntax: identifier quoting, DDL type rendering, and the statement
 * shapes that genuinely differ per engine (schema creation, {@code ADD COLUMN}, keyset
 * page limiting, and upsert — e.g. {@code INSERT ... ON CONFLICT} for Postgres,
 * {@code MERGE} for Oracle/SQL Server, {@code INSERT ... ON DUPLICATE KEY UPDATE} for
 * MySQL). {@link #createTableSql(TableDescriptor)} is a default method because standard
 * {@code CREATE TABLE (col type [NOT NULL], ..., PRIMARY KEY (...))} syntax is identical
 * across every supported relational engine (design doc §3, §8).
 */
public interface SqlDialect {

    DbType type();

    String quoteIdentifier(String identifier);

    /** Renders a native type descriptor as DDL syntax, e.g. {@code "varchar(255)"}, {@code "numeric(10,2)"}. */
    String ddlTypeLiteral(NativeTypeDescriptor nativeType);

    /**
     * Creates a schema/namespace if the engine has the concept and supports creating it
     * via DDL. Some engines (Oracle: a schema is a user account, created via
     * {@code CREATE USER}, a security-sensitive operation out of scope for an
     * additive-only data tool) don't support this — those implementations throw
     * {@link UnsupportedOperationException}, and the caller (DdlReconciler) treats an
     * unqualified/default-schema destination as the common case that never needs this.
     */
    String createSchemaIfNotExistsSql(String schemaName);

    String addColumnSql(TableRef table, ColumnDescriptor column);

    /** The trailing clause that limits a keyset-paginated {@code SELECT ... ORDER BY ...} to one page. */
    String limitClause(int batchSize);

    /**
     * Builds the engine's native upsert statement for one batch, parameterized by
     * position: all of {@code allColumns} in order (for the values being written),
     * suitable for a JDBC batch of {@code PreparedStatement} executions.
     */
    String upsertSql(TableRef table, List<String> naturalKeyColumns, List<String> allColumns);

    default String qualify(TableRef table) {
        StringBuilder sb = new StringBuilder();
        if (table.catalog() != null && !table.catalog().isBlank()) {
            sb.append(quoteIdentifier(table.catalog())).append('.');
        }
        if (table.schema() != null && !table.schema().isBlank()) {
            sb.append(quoteIdentifier(table.schema())).append('.');
        }
        return sb.append(quoteIdentifier(table.table())).toString();
    }

    default String createTableSql(TableDescriptor table) {
        String columns = table.columns().stream()
                .map(this::columnDefinition)
                .collect(Collectors.joining(", "));
        String pk = table.primaryKeyColumns().isEmpty()
                ? ""
                : ", PRIMARY KEY (" + table.primaryKeyColumns().stream()
                        .map(this::quoteIdentifier)
                        .collect(Collectors.joining(", ")) + ")";
        return "CREATE TABLE " + qualify(table.ref()) + " (" + columns + pk + ")";
    }

    private String columnDefinition(ColumnDescriptor column) {
        String nullability = column.nullable() ? "" : " NOT NULL";
        return quoteIdentifier(column.name()) + " " + ddlTypeLiteral(column.nativeType()) + nullability;
    }
}
