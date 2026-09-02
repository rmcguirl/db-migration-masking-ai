package com.migration.batch;

import com.migration.domain.exception.MigrationException;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Enables Spring Batch, explicitly bound to the control-plane datasource via
 * {@code dataSourceRef} — {@code @EnableBatchProcessing}'s auto-configured
 * {@code JobRepository} otherwise does a literal by-name lookup for a bean called
 * {@code "dataSource"}, which doesn't exist in this app (source/destination/control-plane
 * are all separate, explicitly-named pools), so it must be told which one to use rather
 * than relying on {@code @Primary} or type-based resolution. This is the one added piece
 * of complexity resumability requires, resolved by reusing the same embedded database as
 * the plan cache and audit log rather than adding an external dependency (design doc §8).
 *
 * <p>Schema init is done directly here — a metadata check plus Spring Batch's own bundled
 * {@code schema-h2.sql} — rather than relying on Spring Boot's {@code BatchAutoConfiguration}
 * schema-init property, since that auto-configuration is not guaranteed to still apply
 * once this class defines its own {@code @EnableBatchProcessing}.
 */
@Configuration
@EnableBatchProcessing(dataSourceRef = "controlPlaneDataSource")
public class BatchInfrastructureConfig {

    @Bean
    public BatchSchemaInitialized batchSchemaInitializer(@Qualifier("controlPlaneDataSource") DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            boolean alreadyInitialized;
            try (ResultSet tables = connection.getMetaData().getTables(null, null, "BATCH_JOB_INSTANCE", null)) {
                alreadyInitialized = tables.next();
            }
            if (!alreadyInitialized) {
                ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
                populator.addScript(new ClassPathResource("org/springframework/batch/core/schema-h2.sql"));
                populator.execute(dataSource);
            }
        } catch (SQLException e) {
            throw new MigrationException("failed to initialize Spring Batch schema", e);
        }
        return new BatchSchemaInitialized();
    }

    /** Marker return type so schema init runs as an ordinary eager {@code @Bean} method, once, at startup. */
    public static final class BatchSchemaInitialized {
    }
}
