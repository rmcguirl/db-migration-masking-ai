package com.migration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The single deployable's entry point (design doc §2). {@code @ConfigurationPropertiesScan}
 * registers every {@code @ConfigurationProperties} record under {@code com.migration}
 * (just {@link com.migration.config.MigrationConfig} and its nested tree) without listing
 * each one explicitly.
 *
 * <p>Excludes {@link MongoAutoConfiguration}: Spring Boot activates it whenever the Mongo
 * driver class is merely on the classpath (true here, since a MongoDB connector is
 * always compiled in), independent of whether {@code mongodb} is actually the configured
 * engine for this run — left enabled, it opens its own default {@code MongoClient}
 * against {@code localhost:27017} on every startup regardless of {@code migration.source}/
 * {@code destination.type}. This app manages its own role-scoped {@code MongoDatabase}
 * beans ({@code connector.mongodb.MongoConnectionConfig}) instead.
 */
@SpringBootApplication(exclude = MongoAutoConfiguration.class)
@ConfigurationPropertiesScan
public class MigrationApplication {

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(MigrationApplication.class, args)));
    }
}
