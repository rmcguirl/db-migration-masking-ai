package com.migration.connector.mongodb;

import com.migration.config.ConnectionConfig;
import com.migration.config.MigrationConfig;
import com.migration.config.PoolConfig;
import com.migration.domain.exception.ConfigurationException;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Builds the two role-scoped {@link MongoDatabase} handles — {@code sourceMongoDatabase}
 * and {@code destinationMongoDatabase} — from {@link MigrationConfig}. Mongo has no JDBC
 * {@code DataSource}, so this is a separate, {@code @Lazy} counterpart to
 * {@code JdbcDataSourceConfig}: only constructed when a role-active Mongo connector
 * (gated by {@code @ConditionalOnProperty(migration.<role>.type=mongodb)}) injects it.
 * Pool sizing goes through the driver's native {@code MongoClientSettings} connection
 * pool (design doc §8), not HikariCP.
 */
@Configuration
public class MongoConnectionConfig {

    @Bean(name = "sourceMongoDatabase")
    @Lazy
    public MongoDatabase sourceMongoDatabase(MigrationConfig config) {
        return mongoDatabase(config.source().connection(), config.source().pool());
    }

    @Bean(name = "destinationMongoDatabase")
    @Lazy
    public MongoDatabase destinationMongoDatabase(MigrationConfig config) {
        return mongoDatabase(config.destination().connection(), config.destination().pool());
    }

    private MongoDatabase mongoDatabase(ConnectionConfig connection, PoolConfig pool) {
        ConnectionString connectionString = new ConnectionString(connection.url());
        String databaseName = connectionString.getDatabase();
        if (databaseName == null || databaseName.isBlank()) {
            throw new ConfigurationException(
                    "mongo connection url must include a database name, e.g. mongodb://host:27017/mydb");
        }
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(connectionString)
                .applyToConnectionPoolSettings(b -> b.maxSize(pool.maxSize()))
                .build();
        MongoClient client = MongoClients.create(settings);
        return client.getDatabase(databaseName);
    }
}
