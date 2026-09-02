package com.migration.connector.oracle;

import com.migration.connector.api.ConnectorFor;
import com.migration.connector.jdbc.AbstractJdbcSourceConnector;
import com.migration.connector.jdbc.TypeMappingMatrix;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/** Oracle as a migration source: authoritative introspection via {@code DatabaseMetaData}. */
@Component
@ConnectorFor("oracle")
@ConditionalOnProperty(name = "migration.source.type", havingValue = "oracle")
public class OracleSourceConnector extends AbstractJdbcSourceConnector {

    public OracleSourceConnector(@Qualifier("sourceDataSource") DataSource dataSource, OracleSqlDialect dialect) {
        super(dataSource, new OracleTypeMapper(TypeMappingMatrix.loadFromClasspath("oracle-type-mapping.yml")),
                dialect);
    }
}
