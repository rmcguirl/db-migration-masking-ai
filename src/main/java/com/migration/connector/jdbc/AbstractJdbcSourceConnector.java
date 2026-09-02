package com.migration.connector.jdbc;

import com.migration.connector.api.ReferentialMetadataCapable;
import com.migration.connector.api.RowStream;
import com.migration.connector.api.SourceConnector;
import com.migration.connector.api.TypeMapper;
import com.migration.domain.ColumnDescriptor;
import com.migration.domain.DbType;
import com.migration.domain.ExtractCursor;
import com.migration.domain.ForeignKeyRef;
import com.migration.domain.SchemaModel;
import com.migration.domain.TableDescriptor;
import com.migration.domain.TableRef;
import com.migration.domain.exception.ConnectorException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Shared scaffolding for every JDBC-backed relational {@link SourceConnector}: connection
 * testing, {@code DatabaseMetaData}-based introspection ({@link JdbcSchemaIntrospector}),
 * FK introspection, and keyset-paginated extraction ordered by the table's primary key
 * (never {@code OFFSET}, so a resumed step continues correctly — design doc §7, §8).
 * Engine-specific syntax is delegated to a {@link SqlDialect}; per-engine subclasses (task
 * 8–11) supply the {@link DataSource}, {@link TypeMapper}, and dialect.
 */
public abstract class AbstractJdbcSourceConnector implements SourceConnector, ReferentialMetadataCapable {

    protected final DataSource dataSource;
    protected final TypeMapper typeMapper;
    protected final SqlDialect dialect;
    protected final JdbcSchemaIntrospector introspector = new JdbcSchemaIntrospector();

    protected AbstractJdbcSourceConnector(DataSource dataSource, TypeMapper typeMapper, SqlDialect dialect) {
        this.dataSource = dataSource;
        this.typeMapper = typeMapper;
        this.dialect = dialect;
    }

    @Override
    public DbType type() {
        return dialect.type();
    }

    @Override
    public TypeMapper typeMapper() {
        return typeMapper;
    }

    @Override
    public void testConnection() {
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.isValid(5)) {
                throw new ConnectorException("connection to " + type() + " source failed validation");
            }
        } catch (SQLException e) {
            throw new ConnectorException("failed to connect to " + type() + " source", e);
        }
    }

    @Override
    public SchemaModel introspectSchema() {
        try (Connection connection = dataSource.getConnection()) {
            return introspector.introspectSchema(connection, catalogFilter(connection), schemaFilter(connection),
                    typeMapper);
        } catch (SQLException e) {
            throw new ConnectorException("failed to introspect " + type() + " source schema", e);
        }
    }

    @Override
    public List<ForeignKeyRef> introspectForeignKeys() {
        return introspectSchema().foreignKeys();
    }

    @Override
    public RowStream extract(TableRef table, ExtractCursor cursor, int batchSize) {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            TableDescriptor descriptor = introspector.introspectTable(connection, table, typeMapper);
            List<String> keyColumns = descriptor.primaryKeyColumns();
            if (keyColumns.isEmpty()) {
                throw new ConnectorException("table " + table.qualifiedName()
                        + " has no primary key; keyset extraction requires one");
            }
            List<String> columnNames = descriptor.columns().stream()
                    .map(ColumnDescriptor::name)
                    .collect(Collectors.toList());

            String sql = buildKeysetSelectSql(table, columnNames, keyColumns, cursor, batchSize);
            PreparedStatement statement = connection.prepareStatement(sql);
            bindKeysetParameters(statement, keyColumns, cursor);
            ResultSet resultSet = statement.executeQuery();
            return new JdbcRowStream(connection, statement, resultSet, table, columnNames);
        } catch (SQLException e) {
            closeQuietly(connection);
            throw new ConnectorException("failed to extract from " + table.qualifiedName(), e);
        } catch (RuntimeException e) {
            closeQuietly(connection);
            throw e;
        }
    }

    /** Scopes introspection to one catalog; null (the driver default) unless a subclass overrides. */
    protected String catalogFilter(Connection connection) throws SQLException {
        return null;
    }

    /** Scopes introspection to one schema; null (the driver default) unless a subclass overrides. */
    protected String schemaFilter(Connection connection) throws SQLException {
        return null;
    }

    private String buildKeysetSelectSql(TableRef table, List<String> columnNames, List<String> keyColumns,
                                         ExtractCursor cursor, int batchSize) {
        String selectList = columnNames.stream().map(dialect::quoteIdentifier).collect(Collectors.joining(", "));
        String orderBy = keyColumns.stream().map(dialect::quoteIdentifier).collect(Collectors.joining(", "));
        StringBuilder sql = new StringBuilder("SELECT ").append(selectList)
                .append(" FROM ").append(dialect.qualify(table));
        if (!cursor.isStart()) {
            sql.append(" WHERE ").append(keysetPredicate(keyColumns));
        }
        sql.append(" ORDER BY ").append(orderBy).append(' ').append(dialect.limitClause(batchSize));
        return sql.toString();
    }

    /**
     * A portable (no row-value-constructor dependency) keyset predicate for composite
     * keys: {@code (k1 > ?) OR (k1 = ? AND k2 > ?) OR (k1 = ? AND k2 = ? AND k3 > ?) ...}
     */
    private String keysetPredicate(List<String> keyColumns) {
        List<String> orTerms = new ArrayList<>();
        for (int i = 0; i < keyColumns.size(); i++) {
            StringBuilder term = new StringBuilder("(");
            for (int j = 0; j < i; j++) {
                term.append(dialect.quoteIdentifier(keyColumns.get(j))).append(" = ? AND ");
            }
            term.append(dialect.quoteIdentifier(keyColumns.get(i))).append(" > ?)");
            orTerms.add(term.toString());
        }
        return String.join(" OR ", orTerms);
    }

    private void bindKeysetParameters(PreparedStatement statement, List<String> keyColumns, ExtractCursor cursor)
            throws SQLException {
        if (cursor.isStart()) {
            return;
        }
        int paramIndex = 1;
        for (int i = 0; i < keyColumns.size(); i++) {
            for (int j = 0; j <= i; j++) {
                statement.setObject(paramIndex++, cursor.lastKeyValues().get(keyColumns.get(j)));
            }
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // best-effort cleanup
        }
    }
}
