package com.migration.connector.oracle;

import com.migration.connector.api.ConnectorFor;
import com.migration.connector.jdbc.AbstractJdbcDestinationConnector;
import com.migration.connector.jdbc.TypeMappingMatrix;
import com.migration.domain.exception.ConnectorException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Oracle as a migration destination. Oracle natively supports deferrable FK constraints
 * via the ANSI {@code SET CONSTRAINTS ALL DEFERRED} statement, so
 * {@link #supportsDeferredConstraints()} reports {@code true}; in this codebase that's
 * currently a capability declaration rather than something exercised end-to-end, since
 * destination FK constraints aren't emitted by DDL reconciliation (see
 * {@code JdbcDdlReconciler}'s scope note).
 */
@Component
@ConnectorFor("oracle")
@ConditionalOnProperty(name = "migration.destination.type", havingValue = "oracle")
public class OracleDestinationConnector extends AbstractJdbcDestinationConnector {

    public OracleDestinationConnector(@Qualifier("destinationDataSource") DataSource dataSource,
                                       OracleSqlDialect dialect) {
        super(dataSource, new OracleTypeMapper(TypeMappingMatrix.loadFromClasspath("oracle-type-mapping.yml")),
                dialect);
    }

    @Override
    public boolean supportsDeferredConstraints() {
        return true;
    }

    @Override
    protected void setDeferredConstraints(Connection connection) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET CONSTRAINTS ALL DEFERRED");
        } catch (SQLException e) {
            throw new ConnectorException("failed to defer constraints on oracle destination", e);
        }
    }
}
