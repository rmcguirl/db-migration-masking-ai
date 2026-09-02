package com.migration.connector.jdbc;

import com.migration.domain.CanonicalType;
import com.migration.domain.CanonicalTypeKind;
import com.migration.domain.ColumnDescriptor;
import com.migration.domain.SchemaModel;
import com.migration.domain.TableDescriptor;
import com.migration.domain.exception.ConnectorException;
import com.migration.plan.DdlAction;
import com.migration.plan.DdlConflict;
import com.migration.plan.DdlPlan;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Implements the additive-only DDL diff/apply contract shared by every JDBC destination
 * connector (design doc §3): classifies each desired table/column as missing (create),
 * present-and-compatible (no action), or present-but-different (conflict, never
 * auto-altered). Composed into {@link AbstractJdbcDestinationConnector} rather than
 * implementing {@code DdlCapable} itself, so it stays unit-testable without a live
 * connection.
 *
 * <p>Scope note: {@code diffSchema}/{@code applyDdl} create tables and columns only, per
 * the {@code DdlCapable} contract (§11), which takes a {@code SchemaModel} — table/column
 * structure, not FK relationships. Destination FK <em>constraints</em> are intentionally
 * not emitted here: per design doc §5, correctness (masked child FK values equal masked
 * parent PK values) never depends on the destination enforcing them — only Spring Batch's
 * dependency-graph-ordered load does — so destination constraints would be a
 * defense-in-depth nicety, not a correctness requirement, and are left out to avoid
 * fragile re-run idempotency handling (re-adding a constraint that already exists).
 */
public class JdbcDdlReconciler {

    private final DataSource dataSource;
    private final SqlDialect dialect;

    public JdbcDdlReconciler(DataSource dataSource, SqlDialect dialect) {
        this.dataSource = dataSource;
        this.dialect = dialect;
    }

    public DdlPlan diffSchema(SchemaModel desired, SchemaModel existing) {
        List<DdlAction> actions = new ArrayList<>();
        List<DdlConflict> conflicts = new ArrayList<>();
        Set<String> schemasNeeded = new LinkedHashSet<>();

        for (TableDescriptor desiredTable : desired.tables()) {
            Optional<TableDescriptor> existingTable = existing.table(desiredTable.ref());
            if (existingTable.isEmpty()) {
                if (desiredTable.ref().schema() != null && !desiredTable.ref().schema().isBlank()) {
                    schemasNeeded.add(desiredTable.ref().schema());
                }
                actions.add(DdlAction.createTable(desiredTable));
                continue;
            }
            for (ColumnDescriptor desiredColumn : desiredTable.columns()) {
                Optional<ColumnDescriptor> existingColumn = existingTable.get().columns().stream()
                        .filter(c -> c.name().equalsIgnoreCase(desiredColumn.name()))
                        .findFirst();
                if (existingColumn.isEmpty()) {
                    actions.add(DdlAction.addColumn(desiredTable.ref(), desiredColumn));
                    continue;
                }
                if (!isCompatible(desiredColumn.canonicalType(), existingColumn.get().canonicalType())) {
                    conflicts.add(new DdlConflict(desiredTable.ref(), desiredColumn.name(),
                            desiredColumn.canonicalType(), existingColumn.get().nativeType(),
                            "existing column type " + existingColumn.get().canonicalType().kind()
                                    + " is not compatible with desired type " + desiredColumn.canonicalType().kind()));
                }
            }
        }

        List<DdlAction> ordered = new ArrayList<>();
        schemasNeeded.forEach(schema -> ordered.add(DdlAction.createSchema(schema)));
        ordered.addAll(actions);
        return new DdlPlan(ordered, conflicts);
    }

    public void applyDdl(DdlPlan plan) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (DdlAction action : plan.actions()) {
                try {
                    String sql = switch (action.type()) {
                        case CREATE_SCHEMA -> dialect.createSchemaIfNotExistsSql(action.schemaName());
                        case CREATE_TABLE -> dialect.createTableSql(action.tableDescriptor());
                        case ADD_COLUMN -> dialect.addColumnSql(action.table(), action.columnDescriptor());
                    };
                    statement.execute(sql);
                } catch (UnsupportedOperationException notSupported) {
                    // e.g. Oracle has no CREATE SCHEMA DDL statement (a schema is a user account);
                    // treated as a no-op since the default/unqualified schema always exists.
                }
            }
        } catch (SQLException e) {
            throw new ConnectorException("failed to apply DDL", e);
        }
    }

    /**
     * Canonical-type compatibility per design doc §3: identical type is always safe;
     * INTEGER widening to LONG is the one documented safe cross-kind widening. Anything
     * else (including a narrower existing type) is a conflict, never auto-altered.
     */
    private boolean isCompatible(CanonicalType desired, CanonicalType existing) {
        if (desired.kind() == existing.kind()) {
            return true;
        }
        return desired.kind() == CanonicalTypeKind.INTEGER && existing.kind() == CanonicalTypeKind.LONG;
    }
}
