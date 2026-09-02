package com.migration.connector.jdbc;

import com.migration.connector.api.DdlCapable;
import com.migration.connector.api.DestinationConnector;
import com.migration.connector.api.TransactionalBatchWrite;
import com.migration.connector.api.TypeMapper;
import com.migration.connector.api.UpsertResult;
import com.migration.domain.DbType;
import com.migration.domain.Row;
import com.migration.domain.SchemaModel;
import com.migration.domain.TableRef;
import com.migration.domain.exception.ConnectorException;
import com.migration.plan.DdlPlan;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Supplier;

/**
 * Shared scaffolding for every JDBC-backed relational {@link DestinationConnector}:
 * connection testing, introspection, additive-only DDL (delegated to
 * {@link JdbcDdlReconciler}), natural-key upsert (delegated to
 * {@link JdbcNaturalKeyUpsertWriter}), and deferred-constraint transactions for FK cycles
 * (design doc §3, §5).
 *
 * <p>{@link #inTransaction(Supplier)} binds one JDBC connection to the calling thread for
 * the duration of the unit of work; {@link #upsert} reuses that connection when present
 * (so several tables in a {@code DEFERRED_TRANSACTION} cycle share one transaction)
 * rather than opening a new pooled connection per call.
 */
public abstract class AbstractJdbcDestinationConnector
        implements DestinationConnector, DdlCapable, TransactionalBatchWrite {

    private static final ThreadLocal<Connection> ACTIVE_TRANSACTION = new ThreadLocal<>();

    protected final DataSource dataSource;
    protected final TypeMapper typeMapper;
    protected final SqlDialect dialect;
    protected final JdbcSchemaIntrospector introspector = new JdbcSchemaIntrospector();
    private final JdbcDdlReconciler ddlReconciler;
    private final JdbcNaturalKeyUpsertWriter upsertWriter;

    protected AbstractJdbcDestinationConnector(DataSource dataSource, TypeMapper typeMapper, SqlDialect dialect) {
        this.dataSource = dataSource;
        this.typeMapper = typeMapper;
        this.dialect = dialect;
        this.ddlReconciler = new JdbcDdlReconciler(dataSource, dialect);
        this.upsertWriter = new JdbcNaturalKeyUpsertWriter(dataSource, dialect);
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
                throw new ConnectorException("connection to " + type() + " destination failed validation");
            }
        } catch (SQLException e) {
            throw new ConnectorException("failed to connect to " + type() + " destination", e);
        }
    }

    @Override
    public SchemaModel introspectSchema() {
        try (Connection connection = dataSource.getConnection()) {
            return introspector.introspectSchema(connection, catalogFilter(connection), schemaFilter(connection),
                    typeMapper);
        } catch (SQLException e) {
            throw new ConnectorException("failed to introspect " + type() + " destination schema", e);
        }
    }

    @Override
    public DdlPlan diffSchema(SchemaModel desired, SchemaModel existing) {
        return ddlReconciler.diffSchema(desired, existing);
    }

    @Override
    public void applyDdl(DdlPlan plan) {
        ddlReconciler.applyDdl(plan);
    }

    @Override
    public UpsertResult upsert(TableRef table, List<String> naturalKeyColumns, List<Row> rows) {
        Connection active = ACTIVE_TRANSACTION.get();
        if (active != null) {
            try {
                return upsertWriter.writeUsing(active, table, naturalKeyColumns, rows);
            } catch (SQLException e) {
                throw new ConnectorException("failed to upsert into " + table.qualifiedName(), e);
            }
        }
        return upsertWriter.write(table, naturalKeyColumns, rows);
    }

    @Override
    public boolean supportsDeferredConstraints() {
        return false;
    }

    @Override
    public <T> T inTransaction(Supplier<T> unitOfWork) {
        boolean isOutermost = ACTIVE_TRANSACTION.get() == null;
        Connection connection = isOutermost ? openTransactionalConnection() : ACTIVE_TRANSACTION.get();
        if (isOutermost) {
            ACTIVE_TRANSACTION.set(connection);
        }
        try {
            T result = unitOfWork.get();
            if (isOutermost) {
                connection.commit();
            }
            return result;
        } catch (RuntimeException e) {
            if (isOutermost) {
                rollbackQuietly(connection);
            }
            throw e;
        } catch (SQLException e) {
            if (isOutermost) {
                rollbackQuietly(connection);
            }
            throw new ConnectorException("failed to commit transaction on " + type() + " destination", e);
        } finally {
            if (isOutermost) {
                ACTIVE_TRANSACTION.remove();
                closeQuietly(connection);
            }
        }
    }

    private Connection openTransactionalConnection() {
        try {
            Connection connection = dataSource.getConnection();
            connection.setAutoCommit(false);
            if (supportsDeferredConstraints()) {
                setDeferredConstraints(connection);
            }
            return connection;
        } catch (SQLException e) {
            throw new ConnectorException("failed to open transaction on " + type() + " destination", e);
        }
    }

    /** Issues the engine-specific statement that defers FK constraint checking to commit time. */
    protected void setDeferredConstraints(Connection connection) throws SQLException {
        // no-op by default; overridden by engines that support deferred constraints (design doc §5)
    }

    /** Scopes introspection to one catalog; null (the driver default) unless a subclass overrides. */
    protected String catalogFilter(Connection connection) throws SQLException {
        return null;
    }

    /** Scopes introspection to one schema; null (the driver default) unless a subclass overrides. */
    protected String schemaFilter(Connection connection) throws SQLException {
        return null;
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // best-effort cleanup
        }
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // best-effort cleanup
        }
    }
}
