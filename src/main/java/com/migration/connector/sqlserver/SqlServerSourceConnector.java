package com.migration.connector.sqlserver;

import com.migration.connector.api.ConnectorFor;
import com.migration.connector.jdbc.AbstractJdbcSourceConnector;
import com.migration.connector.jdbc.TypeMappingMatrix;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/** SQL Server as a migration source: authoritative introspection via {@code DatabaseMetaData}. */
@Component
@ConnectorFor("sqlserver")
@ConditionalOnProperty(name = "migration.source.type", havingValue = "sqlserver")
public class SqlServerSourceConnector extends AbstractJdbcSourceConnector {

    public SqlServerSourceConnector(@Qualifier("sourceDataSource") DataSource dataSource, SqlServerSqlDialect dialect) {
        super(dataSource, TypeMappingMatrix.loadFromClasspath("sqlserver-type-mapping.yml"), dialect);
    }
}
