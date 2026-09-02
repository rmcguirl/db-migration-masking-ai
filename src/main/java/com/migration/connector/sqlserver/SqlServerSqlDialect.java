package com.migration.connector.sqlserver;

import com.migration.connector.jdbc.SqlDialect;
import com.migration.domain.ColumnDescriptor;
import com.migration.domain.DbType;
import com.migration.domain.NativeTypeDescriptor;
import com.migration.domain.TableRef;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** SQL Server syntax: bracket-quoted identifiers, {@code MERGE} upsert (design doc §3). */
@Component
public class SqlServerSqlDialect implements SqlDialect {

    private static final Set<String> UNBOUNDED_LENGTH_TYPES = Set.of("nvarchar", "varchar", "varbinary");

    @Override
    public DbType type() {
        return DbType.of("sqlserver");
    }

    @Override
    public String quoteIdentifier(String identifier) {
        return "[" + identifier.replace("]", "]]") + "]";
    }

    @Override
    public String ddlTypeLiteral(NativeTypeDescriptor nativeType) {
        // nvarchar/varchar/varbinary with no explicit length silently truncate to length 1
        // on SQL Server, so an unbounded native type must render as "(max)", not bare.
        if (UNBOUNDED_LENGTH_TYPES.contains(nativeType.typeName()) && nativeType.length() == null) {
            return nativeType.typeName() + "(max)";
        }
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
        return "IF NOT EXISTS (SELECT * FROM sys.schemas WHERE name = '" + schemaName.replace("'", "''") + "') "
                + "EXEC('CREATE SCHEMA " + quoteIdentifier(schemaName) + "')";
    }

    @Override
    public String addColumnSql(TableRef table, ColumnDescriptor column) {
        // T-SQL's ALTER TABLE ... ADD does not accept the COLUMN keyword (unlike
        // Postgres/MySQL); ADD is followed directly by the column definition.
        String nullability = column.nullable() ? "" : " NOT NULL";
        return "ALTER TABLE " + qualify(table) + " ADD " + quoteIdentifier(column.name())
                + " " + ddlTypeLiteral(column.nativeType()) + nullability;
    }

    @Override
    public String limitClause(int batchSize) {
        // SQL Server 2012+ requires an OFFSET clause before FETCH NEXT, even when the
        // offset is 0.
        return "OFFSET 0 ROWS FETCH NEXT " + batchSize + " ROWS ONLY";
    }

    @Override
    public String upsertSql(TableRef table, List<String> naturalKeyColumns, List<String> allColumns) {
        String columnList = allColumns.stream().map(this::quoteIdentifier).collect(Collectors.joining(", "));
        String sourceAssignments = allColumns.stream()
                .map(c -> "? AS " + quoteIdentifier(c))
                .collect(Collectors.joining(", "));
        String onClause = naturalKeyColumns.stream()
                .map(c -> "t." + quoteIdentifier(c) + " = s." + quoteIdentifier(c))
                .collect(Collectors.joining(" AND "));
        String sourceValues = allColumns.stream()
                .map(c -> "s." + quoteIdentifier(c))
                .collect(Collectors.joining(", "));

        List<String> updateColumns = allColumns.stream().filter(c -> !naturalKeyColumns.contains(c)).toList();

        StringBuilder sql = new StringBuilder();
        sql.append("MERGE INTO ").append(qualify(table)).append(" AS t ")
                .append("USING (SELECT ").append(sourceAssignments).append(") AS s (").append(columnList).append(") ")
                .append("ON (").append(onClause).append(") ");
        if (!updateColumns.isEmpty()) {
            String setClause = updateColumns.stream()
                    .map(c -> "t." + quoteIdentifier(c) + " = s." + quoteIdentifier(c))
                    .collect(Collectors.joining(", "));
            sql.append("WHEN MATCHED THEN UPDATE SET ").append(setClause).append(" ");
        }
        sql.append("WHEN NOT MATCHED THEN INSERT (").append(columnList).append(") VALUES (")
                .append(sourceValues).append(");");
        return sql.toString();
    }
}
