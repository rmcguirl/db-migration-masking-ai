package com.migration.audit;

import com.migration.domain.TableRef;
import com.migration.domain.exception.MigrationException;
import com.migration.orchestrator.AuditLogService;
import com.migration.orchestrator.RunContext;
import com.migration.orchestrator.RunResult;
import com.migration.orchestrator.TableRunOutcome;
import com.migration.plan.ColumnPlan;
import com.migration.plan.DdlConflict;
import com.migration.plan.DdlPlan;
import com.migration.plan.MigrationPlan;
import com.migration.plan.TablePlan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * Appends structured, queryable audit records to the control-plane store (design doc
 * §10): run start/end + outcome, per-table row counts and errors, and — via
 * {@link #recordPlanAndDdl}, called by the orchestrator once the plan and DDL diff are
 * known (the {@link AuditLogService} interface's own methods don't carry that data) —
 * per-column masking technique/regulatory-basis/decision-source/confidence and any DDL
 * conflicts. This is the evidence base for GDPR/HIPAA/PCI-DSS compliance review.
 */
@Component
public class JdbcAuditLogService implements AuditLogService {

    private final DataSource dataSource;

    public JdbcAuditLogService(@Qualifier("controlPlaneDataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void recordRunStart(RunContext ctx) {
        String sql = "INSERT INTO audit_run (run_id, schema_fingerprint, mode, started_at) VALUES (?, ?, ?, ?)";
        execute(sql, statement -> {
            statement.setString(1, ctx.runId());
            statement.setString(2, ctx.schemaFingerprint());
            statement.setString(3, ctx.mode().name());
            statement.setTimestamp(4, Timestamp.from(ctx.startedAt()));
        });
    }

    @Override
    public void recordTableResult(RunContext ctx, TableRef table, TableRunOutcome outcome) {
        String sql = "INSERT INTO audit_table_result "
                + "(run_id, source_table, destination_table, outcome, rows_inserted, rows_updated, error_message) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        execute(sql, statement -> {
            statement.setString(1, ctx.runId());
            statement.setString(2, table.table());
            statement.setString(3, table.table());
            statement.setString(4, outcome.status().name());
            statement.setLong(5, outcome.rowsInserted());
            statement.setLong(6, outcome.rowsUpdated());
            statement.setString(7, outcome.errorMessage());
        });
    }

    @Override
    public void recordRunEnd(RunContext ctx, RunResult result) {
        String sql = "UPDATE audit_run SET ended_at = ?, outcome = ? WHERE run_id = ?";
        execute(sql, statement -> {
            statement.setTimestamp(1, Timestamp.from(Instant.now()));
            statement.setString(2, result.outcome().name());
            statement.setString(3, ctx.runId());
        });
    }

    /** Persists per-column masking decisions and DDL conflicts once the plan/diff are known. */
    public void recordPlanAndDdl(RunContext ctx, MigrationPlan plan, DdlPlan ddlDiff) {
        for (TablePlan table : plan.tables()) {
            for (ColumnPlan column : table.columns()) {
                if (!column.sensitive()) {
                    continue;
                }
                String sql = "INSERT INTO audit_column_masking "
                        + "(run_id, table_name, column_name, technique, regulatory_basis, decision_source, confidence) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)";
                execute(sql, statement -> {
                    statement.setString(1, ctx.runId());
                    statement.setString(2, table.sourceTable());
                    statement.setString(3, column.source());
                    statement.setString(4, column.masking() != null ? column.masking().technique() : null);
                    statement.setString(5, String.join(",", column.regulatoryBasis()));
                    statement.setString(6, column.decisionSource().wireValue());
                    statement.setDouble(7, column.confidence());
                });
            }
        }
        for (DdlConflict conflict : ddlDiff.conflicts()) {
            String sql = "INSERT INTO audit_ddl_conflict "
                    + "(run_id, table_name, column_name, expected_type, actual_type, reason) VALUES (?, ?, ?, ?, ?, ?)";
            execute(sql, statement -> {
                statement.setString(1, ctx.runId());
                statement.setString(2, conflict.table().qualifiedName());
                statement.setString(3, conflict.column());
                statement.setString(4, String.valueOf(conflict.expectedType()));
                statement.setString(5, String.valueOf(conflict.actualType()));
                statement.setString(6, conflict.reason());
            });
        }
    }

    private void execute(String sql, SqlBinder binder) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new MigrationException("failed to write audit record", e);
        }
    }

    @FunctionalInterface
    private interface SqlBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
