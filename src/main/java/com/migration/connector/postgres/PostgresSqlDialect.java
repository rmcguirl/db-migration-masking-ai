package com.migration.connector.postgres;

import com.migration.connector.jdbc.SqlDialect;
import com.migration.domain.ColumnDescriptor;
import com.migration.domain.DbType;
import com.migration.domain.NativeTypeDescriptor;
import com.migration.domain.TableRef;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/** Postgres syntax: double-quoted identifiers, {@code ON CONFLICT} upsert (design doc §3). */
@Component
public class PostgresSqlDialect implements SqlDialect {

    @Override
    public DbType type() {
        return DbType.of("postgres");
    }

    @Override
    public String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    @Override
    public String ddlTypeLiteral(NativeTypeDescriptor nativeType) {
        if (nativeType.precision() != null && nativeType.scale() != null) {
            return nativeType.typeName() + "(" + nativeType.precision() + "," + nativeType.scale() + ")";
        }
        if (nativeType.length() != null) {
            return nativeType.typeName() + "(" + nativeType.length() + ")";
        }
        return nativeType.typeName();
    }

    @Override
    public String createSchemaIfNotExistsSql(String schemaName) {
        return "CREATE SCHEMA IF NOT EXISTS " + quoteIdentifier(schemaName);
    }

    @Override
    public String addColumnSql(TableRef table, ColumnDescriptor column) {
        String nullability = column.nullable() ? "" : " NOT NULL";
        return "ALTER TABLE " + qualify(table) + " ADD COLUMN " + quoteIdentifier(column.name())
                + " " + ddlTypeLiteral(column.nativeType()) + nullability;
    }

    @Override
    public String limitClause(int batchSize) {
        return "LIMIT " + batchSize;
    }

    @Override
    public String upsertSql(TableRef table, List<String> naturalKeyColumns, List<String> allColumns) {
        String columnList = allColumns.stream().map(this::quoteIdentifier).collect(Collectors.joining(", "));
        String placeholders = allColumns.stream().map(c -> "?").collect(Collectors.joining(", "));
        String conflictTarget = naturalKeyColumns.stream().map(this::quoteIdentifier)
                .collect(Collectors.joining(", "));

        List<String> updateColumns = allColumns.stream().filter(c -> !naturalKeyColumns.contains(c)).toList();
        String sql = "INSERT INTO " + qualify(table) + " (" + columnList + ") VALUES (" + placeholders + ") "
                + "ON CONFLICT (" + conflictTarget + ") ";
        if (updateColumns.isEmpty()) {
            return sql + "DO NOTHING";
        }
        String setClause = updateColumns.stream()
                .map(c -> quoteIdentifier(c) + " = EXCLUDED." + quoteIdentifier(c))
                .collect(Collectors.joining(", "));
        return sql + "DO UPDATE SET " + setClause;
    }
}
