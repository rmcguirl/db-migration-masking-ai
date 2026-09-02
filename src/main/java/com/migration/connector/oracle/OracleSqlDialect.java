package com.migration.connector.oracle;

import com.migration.connector.jdbc.SqlDialect;
import com.migration.domain.ColumnDescriptor;
import com.migration.domain.DbType;
import com.migration.domain.NativeTypeDescriptor;
import com.migration.domain.TableRef;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Oracle syntax: double-quoted identifiers, no {@code CREATE SCHEMA} DDL (a schema is a
 * user account, provisioned out-of-band), {@code ALTER TABLE ... ADD (...)} for new
 * columns, {@code FETCH FIRST n ROWS ONLY} for keyset paging, and {@code MERGE} for
 * upsert (design doc §3, §8).
 */
@Component
public class OracleSqlDialect implements SqlDialect {

    @Override
    public DbType type() {
        return DbType.of("oracle");
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
        throw new UnsupportedOperationException(
                "Oracle has no CREATE SCHEMA DDL statement; a schema is a user account, "
                        + "provisioned out-of-band, not something this additive-only tool creates");
    }

    @Override
    public String addColumnSql(TableRef table, ColumnDescriptor column) {
        return "ALTER TABLE " + qualify(table) + " ADD (" + quoteIdentifier(column.name())
                + " " + ddlTypeLiteral(column.nativeType()) + (column.nullable() ? "" : " NOT NULL") + ")";
    }

    @Override
    public String limitClause(int batchSize) {
        return "FETCH FIRST " + batchSize + " ROWS ONLY";
    }

    @Override
    public String upsertSql(TableRef table, List<String> naturalKeyColumns, List<String> allColumns) {
        String usingSelectList = allColumns.stream()
                .map(c -> "? AS " + quoteIdentifier(c))
                .collect(Collectors.joining(", "));
        String onClause = naturalKeyColumns.stream()
                .map(c -> "t." + quoteIdentifier(c) + " = s." + quoteIdentifier(c))
                .collect(Collectors.joining(" AND "));

        List<String> updateColumns = allColumns.stream().filter(c -> !naturalKeyColumns.contains(c)).toList();

        StringBuilder sql = new StringBuilder();
        sql.append("MERGE INTO ").append(qualify(table)).append(" t")
                .append(" USING (SELECT ").append(usingSelectList).append(" FROM dual) s")
                .append(" ON (").append(onClause).append(")");

        if (!updateColumns.isEmpty()) {
            String setClause = updateColumns.stream()
                    .map(c -> "t." + quoteIdentifier(c) + " = s." + quoteIdentifier(c))
                    .collect(Collectors.joining(", "));
            sql.append(" WHEN MATCHED THEN UPDATE SET ").append(setClause);
        }

        String insertColumnList = allColumns.stream().map(this::quoteIdentifier).collect(Collectors.joining(", "));
        String insertValueList = allColumns.stream().map(c -> "s." + quoteIdentifier(c))
                .collect(Collectors.joining(", "));
        sql.append(" WHEN NOT MATCHED THEN INSERT (").append(insertColumnList)
                .append(") VALUES (").append(insertValueList).append(")");

        return sql.toString();
    }
}
