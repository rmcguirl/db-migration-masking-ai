package com.migration.connector.mysql;

import com.migration.connector.api.ConnectorFor;
import com.migration.connector.jdbc.AbstractJdbcSourceConnector;
import com.migration.connector.jdbc.TypeMappingMatrix;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/** MySQL as a migration source: authoritative introspection via {@code DatabaseMetaData}. */
@Component
@ConnectorFor("mysql")
@ConditionalOnProperty(name = "migration.source.type", havingValue = "mysql")
public class MySqlSourceConnector extends AbstractJdbcSourceConnector {

    public MySqlSourceConnector(@Qualifier("sourceDataSource") DataSource dataSource, MySqlSqlDialect dialect) {
        super(dataSource, TypeMappingMatrix.loadFromClasspath("mysql-type-mapping.yml"), dialect);
    }
}
