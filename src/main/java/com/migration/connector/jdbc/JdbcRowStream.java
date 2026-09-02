package com.migration.connector.jdbc;

import com.migration.connector.api.RowStream;
import com.migration.domain.Row;
import com.migration.domain.TableRef;
import com.migration.domain.exception.ConnectorException;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Wraps one extraction chunk's JDBC resources (connection, statement, result set) as a
 * lazily-iterated {@link RowStream}. Closing releases all three regardless of whether
 * iteration ran to completion.
 */
final class JdbcRowStream implements RowStream {

    private final Connection connection;
    private final Statement statement;
    private final ResultSet resultSet;
    private final TableRef table;
    private final List<String> columnNames;
    private boolean closed = false;

    JdbcRowStream(Connection connection, Statement statement, ResultSet resultSet, TableRef table,
                  List<String> columnNames) {
        this.connection = connection;
        this.statement = statement;
        this.resultSet = resultSet;
        this.table = table;
        this.columnNames = columnNames;
    }

    @Override
    public Iterator<Row> iterator() {
        return new Iterator<>() {
            private Boolean hasNextCache;

            @Override
            public boolean hasNext() {
                if (hasNextCache == null) {
                    try {
                        hasNextCache = resultSet.next();
                    } catch (SQLException e) {
                        throw new ConnectorException("failed reading result set for " + table.qualifiedName(), e);
                    }
                }
                return hasNextCache;
            }

            @Override
            public Row next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                hasNextCache = null;
                try {
                    Map<String, Object> values = new LinkedHashMap<>();
                    for (String column : columnNames) {
                        values.put(column, resultSet.getObject(column));
                    }
                    return new Row(table, values);
                } catch (SQLException e) {
                    throw new ConnectorException("failed reading row for " + table.qualifiedName(), e);
                }
            }
        };
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            resultSet.close();
        } catch (SQLException ignored) {
            // best-effort cleanup
        }
        try {
            statement.close();
        } catch (SQLException ignored) {
            // best-effort cleanup
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // best-effort cleanup
        }
    }
}
