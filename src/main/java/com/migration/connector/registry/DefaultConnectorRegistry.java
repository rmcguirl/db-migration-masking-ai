package com.migration.connector.registry;

import com.migration.connector.api.Connector;
import com.migration.connector.api.ConnectorFor;
import com.migration.connector.api.ConnectorRegistry;
import com.migration.connector.api.DestinationConnector;
import com.migration.connector.api.SourceConnector;
import com.migration.domain.exception.ConfigurationException;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects every {@link Connector} bean via constructor injection and indexes it by its
 * {@link ConnectorFor} annotation value — separately per role (source/destination), since
 * the same engine (e.g. Postgres) can legitimately be used as both source and
 * destination in one run, each pointed at a different database and needing its own
 * connection pool. A connector class that implements both {@link SourceConnector} and
 * {@link DestinationConnector} is indexed into both maps under the same key; engines
 * needing separate connection details per role (the common case) instead register
 * separate, role-specific {@code @ConnectorFor}-annotated beans, one active only when
 * {@code migration.source.type} matches and one only when
 * {@code migration.destination.type} matches (design doc §3, §8).
 *
 * <p>No {@code if/else} or {@code switch} on database type exists anywhere in this class
 * or in orchestration code — resolution is a single map lookup per role.
 */
@Component
public class DefaultConnectorRegistry implements ConnectorRegistry {

    private final Map<String, SourceConnector> sourceConnectorsByType;
    private final Map<String, DestinationConnector> destinationConnectorsByType;

    public DefaultConnectorRegistry(List<Connector> candidates) {
        Map<String, SourceConnector> sources = new HashMap<>();
        Map<String, DestinationConnector> destinations = new HashMap<>();
        for (Connector candidate : candidates) {
            ConnectorFor annotation = AnnotationUtils.findAnnotation(candidate.getClass(), ConnectorFor.class);
            if (annotation == null) {
                throw new ConfigurationException(
                        "connector bean " + candidate.getClass().getName() + " is missing a @ConnectorFor annotation");
            }
            String key = annotation.value().trim().toLowerCase();
            if (candidate instanceof SourceConnector source) {
                putUnique(sources, key, source, "SourceConnector");
            }
            if (candidate instanceof DestinationConnector destination) {
                putUnique(destinations, key, destination, "DestinationConnector");
            }
        }
        this.sourceConnectorsByType = Map.copyOf(sources);
        this.destinationConnectorsByType = Map.copyOf(destinations);
    }

    @Override
    public SourceConnector resolveSource(String dbType) {
        return lookup(sourceConnectorsByType, dbType, "source");
    }

    @Override
    public DestinationConnector resolveDestination(String dbType) {
        return lookup(destinationConnectorsByType, dbType, "destination");
    }

    private static <T> void putUnique(Map<String, T> index, String key, T value, String roleLabel) {
        T existing = index.putIfAbsent(key, value);
        if (existing != null) {
            throw new ConfigurationException("duplicate " + roleLabel + " @ConnectorFor(\"" + key + "\") on "
                    + existing.getClass().getName() + " and " + value.getClass().getName());
        }
    }

    private static <T> T lookup(Map<String, T> index, String dbType, String role) {
        if (dbType == null || dbType.isBlank()) {
            throw new ConfigurationException("db type must not be blank");
        }
        T connector = index.get(dbType.trim().toLowerCase());
        if (connector == null) {
            throw new ConfigurationException("no " + role + " connector registered for type '" + dbType
                    + "'; known types: " + index.keySet());
        }
        return connector;
    }
}
