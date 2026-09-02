package com.migration.connector.sqlserver;

import com.migration.connector.api.ConnectorFor;
import com.migration.connector.jdbc.AbstractJdbcDestinationConnector;
import com.migration.connector.jdbc.TypeMappingMatrix;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * SQL Server as a migration destination. SQL Server has no equivalent of deferrable FK
 * constraint checking, so this leaves {@link AbstractJdbcDestinationConnector}'s
 * {@code supportsDeferredConstraints()}/{@code setDeferredConstraints} defaults
 * (unsupported/no-op) untouched.
 */
@Component
@ConnectorFor("sqlserver")
@ConditionalOnProperty(name = "migration.destination.type", havingValue = "sqlserver")
public class SqlServerDestinationConnector extends AbstractJdbcDestinationConnector {

    public SqlServerDestinationConnector(@Qualifier("destinationDataSource") DataSource dataSource,
                                          SqlServerSqlDialect dialect) {
        super(dataSource, TypeMappingMatrix.loadFromClasspath("sqlserver-type-mapping.yml"), dialect);
    }
}
