package com.migration.connector.postgres;

import com.migration.connector.api.ConnectorFor;
import com.migration.connector.jdbc.AbstractJdbcSourceConnector;
import com.migration.connector.jdbc.TypeMappingMatrix;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/** Postgres as a migration source: authoritative introspection via {@code DatabaseMetaData}. */
@Component
@ConnectorFor("postgres")
@ConditionalOnProperty(name = "migration.source.type", havingValue = "postgres")
public class PostgresSourceConnector extends AbstractJdbcSourceConnector {

    public PostgresSourceConnector(@Qualifier("sourceDataSource") DataSource dataSource, PostgresSqlDialect dialect) {
        super(dataSource, TypeMappingMatrix.loadFromClasspath("postgres-type-mapping.yml"), dialect);
    }
}
