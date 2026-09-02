package com.migration.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * Builds the control-plane {@link DataSource} — the durable store for the plan cache,
 * audit log, and (separately, in {@code batch.BatchInfrastructureConfig}) Spring Batch's
 * {@code JobRepository} — and runs its Flyway migrations against it eagerly at bean
 * creation, since every other control-plane bean depends on the schema existing (design
 * doc §1 assumption 5, §8).
 *
 * <p>Marked {@code @Primary}: this app has three independent JDBC pools (source,
 * destination, control-plane), and the other two are {@code @Lazy} + only constructed
 * when their role is active for the configured engine, so any framework component doing
 * a plain by-type {@code DataSource} lookup (Spring Batch's {@code @EnableBatchProcessing}
 * auto-detection included) needs an unambiguous, always-present candidate to resolve to —
 * this is it.
 */
@Configuration
public class ControlPlaneDataSourceConfig {

    @Bean(name = "controlPlaneDataSource")
    @Primary
    public DataSource controlPlaneDataSource(MigrationConfig config) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.controlPlane().datastore());
        hikariConfig.setPoolName("controlplane-pool");
        DataSource dataSource = new HikariDataSource(hikariConfig);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/controlplane")
                .load()
                .migrate();

        return dataSource;
    }
}
