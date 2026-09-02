package com.migration.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.migration.domain.exception.MigrationException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;

/**
 * Persists the plan cache as JSON in the control-plane store, keyed by schema
 * fingerprint (design doc §2). The upsert uses H2's {@code MERGE ... KEY} syntax — the
 * one control-plane statement not portable as-is to a non-H2 {@code controlPlane.datastore}
 * (design doc §1 assumption 5's Postgres swap option); adjusting it to
 * {@code INSERT ... ON CONFLICT} is a small, isolated change if that swap is exercised.
 */
@Component
public class JdbcPlanCacheRepository implements PlanCacheRepository {

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public JdbcPlanCacheRepository(@Qualifier("controlPlaneDataSource") DataSource dataSource) {
        this.dataSource = dataSource;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Override
    public Optional<MigrationPlan> findByFingerprint(String schemaFingerprint) {
        String sql = "SELECT plan_json FROM plan_cache WHERE schema_fingerprint = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schemaFingerprint);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(objectMapper.readValue(resultSet.getString("plan_json"), MigrationPlan.class));
            }
        } catch (SQLException | JsonProcessingException e) {
            throw new MigrationException("failed to read cached plan for fingerprint " + schemaFingerprint, e);
        }
    }

    @Override
    public void save(MigrationPlan plan) {
        String json;
        try {
            json = objectMapper.writeValueAsString(plan);
        } catch (JsonProcessingException e) {
            throw new MigrationException("failed to serialize plan for fingerprint " + plan.schemaFingerprint(), e);
        }
        String sql = "MERGE INTO plan_cache (schema_fingerprint, generated_at, plan_json) KEY (schema_fingerprint) "
                + "VALUES (?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, plan.schemaFingerprint());
            statement.setTimestamp(2, Timestamp.from(plan.generatedAt()));
            statement.setString(3, json);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new MigrationException("failed to save plan for fingerprint " + plan.schemaFingerprint(), e);
        }
    }
}
