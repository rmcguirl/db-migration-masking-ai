package com.migration.connector.mysql;

import com.migration.connector.jdbc.SqlDialect;
import com.migration.domain.ColumnDescriptor;
import com.migration.domain.DbType;
import com.migration.domain.NativeTypeDescriptor;
import com.migration.domain.TableRef;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/** MySQL syntax: backtick-quoted identifiers, {@code ON DUPLICATE KEY UPDATE} upsert (design doc §3). */
@Component
public class MySqlSqlDialect implements SqlDialect {

    @Override
    public DbType type() {
        return DbType.of("mysql");
    }

    @Override
    public String quoteIdentifier(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    @Override
    public String ddlTypeLiteral(NativeTypeDescriptor nativeType) {
        if (nativeType.precision() != null && nativeType.scale() != null) {
            return nativeType.typeName() + "(" + nativeType.precision() + "," + nativeType.scale() + ")";
        }
        if (nativeType.length() != null) {
            return nativeType.typeName() + "(" + nativeType.length() + ")";
        }
        // "tinyint" with no length is rendered bare rather than defaulting to "(1)" display
        // width; the BOOLEAN canonical type maps here and plain "tinyint" is functionally
        // equivalent MySQL DDL (design doc §8).
        return nativeType.typeName();
    }

    @Override
    public String createSchemaIfNotExistsSql(String schemaName) {
        // MySQL treats SCHEMA as an alias for DATABASE, so this syntax is valid.
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

        List<String> updateColumns = allColumns.stream().filter(c -> !naturalKeyColumns.contains(c)).toList();
        String setClause;
        if (updateColumns.isEmpty()) {
            // MySQL has no DO NOTHING; a self-assignment on the natural key is the standard
            // harmless no-op idiom for ON DUPLICATE KEY UPDATE.
            String key = quoteIdentifier(naturalKeyColumns.get(0));
            setClause = key + " = " + key;
        } else {
            setClause = updateColumns.stream()
                    .map(c -> quoteIdentifier(c) + " = VALUES(" + quoteIdentifier(c) + ")")
                    .collect(Collectors.joining(", "));
        }
        return "INSERT INTO " + qualify(table) + " (" + columnList + ") VALUES (" + placeholders + ") "
                + "ON DUPLICATE KEY UPDATE " + setClause;
    }
}
