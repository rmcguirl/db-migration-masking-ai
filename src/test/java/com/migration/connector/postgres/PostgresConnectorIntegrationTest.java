package com.migration.connector.postgres;

import com.migration.connector.api.RowStream;
import com.migration.connector.api.UpsertResult;
import com.migration.domain.CanonicalType;
import com.migration.domain.CanonicalTypeKind;
import com.migration.domain.ColumnDescriptor;
import com.migration.domain.ExtractCursor;
import com.migration.domain.NativeTypeDescriptor;
import com.migration.domain.Row;
import com.migration.domain.SchemaModel;
import com.migration.domain.TableDescriptor;
import com.migration.domain.TableRef;
import com.migration.plan.DdlPlan;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link PostgresSourceConnector}/{@link PostgresDestinationConnector} against
 * a real Postgres instance (design doc §10's connector contract test: introspection
 * accuracy, additive-only DDL, upsert idempotency) — the pattern every other engine's
 * contract test follows, just with a different Testcontainers module and JDBC URL.
 *
 * <p><b>Requires Docker.</b> Run with {@code mvn test -Dtest=PostgresConnectorIntegrationTest}
 * on a machine with Docker.
 */
@Testcontainers
class PostgresConnectorIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static HikariDataSource dataSource;
    private static PostgresSourceConnector sourceConnector;
    private static PostgresDestinationConnector destinationConnector;

    @BeforeAll
    static void setUp() throws Exception {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(POSTGRES.getJdbcUrl());
        hikariConfig.setUsername(POSTGRES.getUsername());
        hikariConfig.setPassword(POSTGRES.getPassword());
        dataSource = new HikariDataSource(hikariConfig);

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE customers (id BIGINT PRIMARY KEY, external_id VARCHAR(64) UNIQUE, email VARCHAR(255))");
            statement.execute("INSERT INTO customers (id, external_id, email) VALUES "
                    + "(1, 'ext-1', 'a@example.com'), (2, 'ext-2', 'b@example.com'), (3, 'ext-3', 'c@example.com')");
        }

        PostgresSqlDialect dialect = new PostgresSqlDialect();
        sourceConnector = new PostgresSourceConnector(dataSource, dialect);
        destinationConnector = new PostgresDestinationConnector(dataSource, dialect);
    }

    @AfterAll
    static void tearDown() {
        dataSource.close();
    }

    @Test
    void introspection_reports_the_seeded_table_and_its_columns() {
        SchemaModel schema = sourceConnector.introspectSchema();

        TableDescriptor customers = schema.table(new TableRef(null, "public", "customers")).orElseThrow();
        assertThat(customers.columns()).extracting(ColumnDescriptor::name)
                .containsExactlyInAnyOrder("id", "external_id", "email");
        assertThat(customers.primaryKeyColumns()).containsExactly("id");
    }

    @Test
    void extract_reads_back_every_seeded_row_via_keyset_pagination() {
        TableRef table = new TableRef(null, "public", "customers");
        List<Row> rows = new ArrayList<>();
        ExtractCursor cursor = ExtractCursor.START;
        List<Row> page;
        do {
            page = new ArrayList<>();
            try (RowStream stream = sourceConnector.extract(table, cursor, 2)) {
                stream.forEach(page::add);
            }
            rows.addAll(page);
            if (!page.isEmpty()) {
                cursor = ExtractCursor.of(Map.of("id", page.get(page.size() - 1).get("id")));
            }
        } while (!page.isEmpty());

        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(r -> r.get("external_id")).containsExactlyInAnyOrder("ext-1", "ext-2", "ext-3");
    }

    @Test
    void additive_ddl_creates_a_missing_table_without_touching_existing_ones() {
        TableRef newTable = new TableRef(null, "public", "loyalty_accounts");
        TableDescriptor desired = new TableDescriptor(newTable, List.of(
                new ColumnDescriptor("id", CanonicalType.of(CanonicalTypeKind.LONG),
                        new NativeTypeDescriptor("bigint", null, null, null), false, true, null)),
                List.of("id"), null);

        DdlPlan ddlPlan = destinationConnector.diffSchema(SchemaModel.of(List.of(desired)),
                destinationConnector.introspectSchema());
        destinationConnector.applyDdl(ddlPlan);

        SchemaModel afterApply = destinationConnector.introspectSchema();
        assertThat(afterApply.table(newTable)).isPresent();
    }

    @Test
    void upserting_the_same_batch_twice_is_idempotent() {
        TableRef target = new TableRef(null, "public", "customers");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", 999L);
        values.put("external_id", "ext-999");
        values.put("email", "z@example.com");
        Row row = new Row(target, values);

        UpsertResult first = destinationConnector.upsert(target, List.of("external_id"), List.of(row));
        UpsertResult second = destinationConnector.upsert(target, List.of("external_id"), List.of(row));

        assertThat(first.total()).isEqualTo(1);
        assertThat(second.total()).isEqualTo(1);
    }
}
