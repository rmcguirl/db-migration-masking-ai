package com.migration.connector.jdbc;

import com.migration.connector.api.UpsertResult;
import com.migration.domain.Row;
import com.migration.domain.TableRef;
import com.migration.domain.exception.ConnectorException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Upserts a batch of masked rows using the connector's native upsert SQL
 * ({@code ON CONFLICT} / {@code MERGE} / {@code ON DUPLICATE KEY UPDATE}, per
 * {@link SqlDialect#upsertSql}), keyed by the table's natural key (design doc §7 step 8).
 *
 * <p>Insert-vs-update attribution from a JDBC batch result is inherently
 * engine-approximate: per JDBC's {@code executeBatch()} contract, a per-statement count
 * of 1 is treated as an insert, 2 as an update (matches MySQL's
 * {@code ON DUPLICATE KEY UPDATE} with {@code useAffectedRows=true}), and
 * {@code SUCCESS_NO_INFO} (returned by Postgres's {@code ON CONFLICT} and by MERGE
 * statements on Oracle/SQL Server, which don't expose the distinction over JDBC) is
 * counted as an insert. Total row count is exact either way; the insert/update split is
 * a best-effort audit signal, not a correctness-critical value.
 */
public class JdbcNaturalKeyUpsertWriter {

    private final DataSource dataSource;
    private final SqlDialect dialect;

    public JdbcNaturalKeyUpsertWriter(DataSource dataSource, SqlDialect dialect) {
        this.dataSource = dataSource;
        this.dialect = dialect;
    }

    /** Opens and closes its own connection/transaction — for a standalone (non-deferred-cycle) write. */
    public UpsertResult write(TableRef table, List<String> naturalKeyColumns, List<Row> rows) {
        if (rows.isEmpty()) {
            return UpsertResult.zero();
        }
        try (Connection connection = dataSource.getConnection()) {
            return writeUsing(connection, table, naturalKeyColumns, rows);
        } catch (SQLException e) {
            throw new ConnectorException("failed to upsert into " + table.qualifiedName(), e);
        }
    }

    /**
     * Writes using a caller-supplied, caller-managed connection — for a
     * {@code DEFERRED_TRANSACTION} FK cycle, where several tables must share one
     * in-flight transaction (design doc §5).
     */
    public UpsertResult writeUsing(Connection connection, TableRef table, List<String> naturalKeyColumns,
                                    List<Row> rows) throws SQLException {
        if (rows.isEmpty()) {
            return UpsertResult.zero();
        }
        List<String> allColumns = new ArrayList<>(rows.get(0).values().keySet());
        String sql = dialect.upsertSql(table, naturalKeyColumns, allColumns);

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Row row : rows) {
                int paramIndex = 1;
                for (String column : allColumns) {
                    statement.setObject(paramIndex++, row.get(column));
                }
                statement.addBatch();
            }
            int[] counts = statement.executeBatch();
            long inserted = 0;
            long updated = 0;
            for (int count : counts) {
                if (count == 2) {
                    updated++;
                } else {
                    inserted++;
                }
            }
            return new UpsertResult(inserted, updated);
        }
    }
}
