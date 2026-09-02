package com.migration.connector.mysql;

import com.migration.connector.api.ConnectorFor;
import com.migration.connector.jdbc.AbstractJdbcDestinationConnector;
import com.migration.connector.jdbc.TypeMappingMatrix;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * MySQL as a migration destination. MySQL has no equivalent of deferrable FK constraint
 * checking within a transaction, so this connector relies on the base class's
 * {@code supportsDeferredConstraints() == false} default rather than overriding it
 * (design doc §5).
 */
@Component
@ConnectorFor("mysql")
@ConditionalOnProperty(name = "migration.destination.type", havingValue = "mysql")
public class MySqlDestinationConnector extends AbstractJdbcDestinationConnector {

    public MySqlDestinationConnector(@Qualifier("destinationDataSource") DataSource dataSource,
                                      MySqlSqlDialect dialect) {
        super(dataSource, TypeMappingMatrix.loadFromClasspath("mysql-type-mapping.yml"), dialect);
    }
}
