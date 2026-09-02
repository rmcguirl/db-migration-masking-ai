package com.migration.connector.jdbc;

import com.migration.connector.api.TypeMapper;
import com.migration.domain.ColumnDescriptor;
import com.migration.domain.ForeignKeyRef;
import com.migration.domain.NativeTypeDescriptor;
import com.migration.domain.SchemaModel;
import com.migration.domain.TableDescriptor;
import com.migration.domain.TableRef;
import com.migration.domain.exception.ConnectorException;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Reads authoritative relational metadata via {@link DatabaseMetaData} —
 * {@code information_schema} equivalent for every JDBC-compliant engine. Shared by every
 * relational connector so table/column/PK/FK introspection is written once (design doc §6).
 */
public class JdbcSchemaIntrospector {

    /** Introspects every user table visible to {@code catalog}/{@code schemaPattern} (either may be null). */
    public SchemaModel introspectSchema(Connection connection, String catalog, String schemaPattern,
                                         TypeMapper typeMapper) {
        try {
            List<TableRef> tableRefs = listTables(connection, catalog, schemaPattern);
            List<TableDescriptor> tables = new ArrayList<>();
            List<ForeignKeyRef> foreignKeys = new ArrayList<>();
            for (TableRef ref : tableRefs) {
                tables.add(introspectTable(connection, ref, typeMapper));
                foreignKeys.addAll(introspectForeignKeys(connection, ref));
            }
            return SchemaModel.of(tables, foreignKeys);
        } catch (SQLException e) {
            throw new ConnectorException("failed to introspect schema", e);
        }
    }

    public TableDescriptor introspectTable(Connection connection, TableRef table, TypeMapper typeMapper) {
        try {
            DatabaseMetaData meta = connection.getMetaData();
            Set<String> primaryKeyColumns = new LinkedHashSet<>();
            try (ResultSet pkRs = meta.getPrimaryKeys(table.catalog(), table.schema(), table.table())) {
                Map<Short, String> byOrdinal = new TreeMap<>();
                while (pkRs.next()) {
                    byOrdinal.put(pkRs.getShort("KEY_SEQ"), pkRs.getString("COLUMN_NAME"));
                }
                primaryKeyColumns.addAll(byOrdinal.values());
            }

            List<ColumnDescriptor> columns = new ArrayList<>();
            try (ResultSet colRs = meta.getColumns(table.catalog(), table.schema(), table.table(), null)) {
                while (colRs.next()) {
                    String name = colRs.getString("COLUMN_NAME");
                    String typeName = colRs.getString("TYPE_NAME");
                    int dataType = colRs.getInt("DATA_TYPE");
                    int columnSize = colRs.getInt("COLUMN_SIZE");
                    boolean columnSizeNull = colRs.wasNull();
                    int decimalDigits = colRs.getInt("DECIMAL_DIGITS");
                    boolean decimalDigitsNull = colRs.wasNull();
                    boolean nullable = colRs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls;
                    String comment = colRs.getString("REMARKS");

                    Integer length = null;
                    Integer precision = null;
                    Integer scale = null;
                    if (!columnSizeNull && columnSize != 0) {
                        if (isCharacterOrBinaryType(dataType)) {
                            length = columnSize;
                        } else if (isExactNumericType(dataType)) {
                            precision = columnSize;
                            scale = decimalDigitsNull ? null : decimalDigits;
                        }
                    }
                    NativeTypeDescriptor nativeType = new NativeTypeDescriptor(typeName, length, precision, scale);

                    columns.add(new ColumnDescriptor(
                            name,
                            typeMapper.toCanonical(nativeType),
                            nativeType,
                            nullable,
                            primaryKeyColumns.contains(name),
                            comment));
                }
            }
            return new TableDescriptor(table, columns, List.copyOf(primaryKeyColumns), null);
        } catch (SQLException e) {
            throw new ConnectorException("failed to introspect table " + table.qualifiedName(), e);
        }
    }

    /** {@code COLUMN_SIZE} means character/byte length for these {@code java.sql.Types}. */
    private static boolean isCharacterOrBinaryType(int dataType) {
        return switch (dataType) {
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
                 Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> true;
            default -> false;
        };
    }

    /** {@code COLUMN_SIZE}/{@code DECIMAL_DIGITS} mean precision/scale only for these {@code java.sql.Types}. */
    private static boolean isExactNumericType(int dataType) {
        return dataType == Types.NUMERIC || dataType == Types.DECIMAL;
    }

    public List<ForeignKeyRef> introspectForeignKeys(Connection connection, TableRef table) {
        try {
            DatabaseMetaData meta = connection.getMetaData();
            // Group by FK_NAME: a single named constraint can span multiple columns (composite FK).
            Map<String, List<String>> childColumnsByFk = new LinkedHashMap<>();
            Map<String, List<String>> parentColumnsByFk = new LinkedHashMap<>();
            Map<String, TableRef> parentTableByFk = new LinkedHashMap<>();

            try (ResultSet fkRs = meta.getImportedKeys(table.catalog(), table.schema(), table.table())) {
                while (fkRs.next()) {
                    String fkName = fkRs.getString("FK_NAME");
                    if (fkName == null) {
                        fkName = "fk_" + fkRs.getString("PKTABLE_NAME") + "_" + fkRs.getRow();
                    }
                    childColumnsByFk.computeIfAbsent(fkName, k -> new ArrayList<>())
                            .add(fkRs.getString("FKCOLUMN_NAME"));
                    parentColumnsByFk.computeIfAbsent(fkName, k -> new ArrayList<>())
                            .add(fkRs.getString("PKCOLUMN_NAME"));
                    parentTableByFk.putIfAbsent(fkName, new TableRef(
                            fkRs.getString("PKTABLE_CAT"), fkRs.getString("PKTABLE_SCHEM"),
                            fkRs.getString("PKTABLE_NAME")));
                }
            }

            List<ForeignKeyRef> result = new ArrayList<>();
            for (String fkName : childColumnsByFk.keySet()) {
                result.add(new ForeignKeyRef(table, childColumnsByFk.get(fkName),
                        parentTableByFk.get(fkName), parentColumnsByFk.get(fkName)));
            }
            return result;
        } catch (SQLException e) {
            throw new ConnectorException("failed to introspect foreign keys for " + table.qualifiedName(), e);
        }
    }

    private List<TableRef> listTables(Connection connection, String catalog, String schemaPattern) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        List<TableRef> refs = new ArrayList<>();
        try (ResultSet rs = meta.getTables(catalog, schemaPattern, "%", new String[] {"TABLE"})) {
            while (rs.next()) {
                refs.add(new TableRef(rs.getString("TABLE_CAT"), rs.getString("TABLE_SCHEM"),
                        rs.getString("TABLE_NAME")));
            }
        }
        return refs;
    }
}
