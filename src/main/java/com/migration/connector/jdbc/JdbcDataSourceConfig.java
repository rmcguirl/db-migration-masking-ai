package com.migration.connector.jdbc;

import com.migration.config.ConnectionConfig;
import com.migration.config.MigrationConfig;
import com.migration.config.PoolConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import javax.sql.DataSource;
import java.util.Map;

/**
 * Builds the two role-scoped connection pools — {@code sourceDataSource} and
 * {@code destinationDataSource} — from {@link MigrationConfig}. Kept as one generic
 * factory (driver class name looked up by engine id) rather than duplicated per engine,
 * so adding a JDBC-backed engine only means adding one map entry here plus its connector
 * package (design doc §8). Each bean is {@code @Lazy}: it's only actually constructed
 * when a role-active relational connector (gated by
 * {@code @ConditionalOnProperty(migration.<role>.type=...)}) injects it — so no pool is
 * opened for a role whose engine isn't JDBC-backed (e.g. MongoDB) or isn't in this run.
 */
@Configuration
public class JdbcDataSourceConfig {

    private static final Map<String, String> DRIVER_CLASS_NAMES = Map.of(
            "postgres", "org.postgresql.Driver",
            "mysql", "com.mysql.cj.jdbc.Driver",
            "oracle", "oracle.jdbc.OracleDriver",
            "sqlserver", "com.microsoft.sqlserver.jdbc.SQLServerDriver");

    @Bean(name = "sourceDataSource")
    @Lazy
    public DataSource sourceDataSource(MigrationConfig config) {
        return dataSource(config.source().type(), config.source().connection(), config.source().pool());
    }

    @Bean(name = "destinationDataSource")
    @Lazy
    public DataSource destinationDataSource(MigrationConfig config) {
        return dataSource(config.destination().type(), config.destination().connection(), config.destination().pool());
    }

    private DataSource dataSource(String dbType, ConnectionConfig connection, PoolConfig pool) {
        String driverClassName = DRIVER_CLASS_NAMES.get(dbType.trim().toLowerCase());
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(connection.url());
        hikariConfig.setUsername(connection.username());
        hikariConfig.setPassword(connection.password());
        if (driverClassName != null) {
            hikariConfig.setDriverClassName(driverClassName);
        }
        hikariConfig.setMaximumPoolSize(pool.maxSize());
        hikariConfig.setPoolName(dbType + "-pool");
        // Don't fail application startup just because the first connection attempt fails
        // (e.g. a transient network blip, or the destination not provisioned yet) — defer
        // that to Connector.testConnection(), which the orchestrator already handles as a
        // graceful HARD_FAILURE rather than a JVM-crashing startup exception.
        hikariConfig.setInitializationFailTimeout(-1);
        return new HikariDataSource(hikariConfig);
    }
}
