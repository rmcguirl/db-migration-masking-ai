package com.migration.connector.jdbc;

import com.migration.connector.postgres.PostgresSqlDialect;
import com.migration.domain.CanonicalType;
import com.migration.domain.CanonicalTypeKind;
import com.migration.domain.ColumnDescriptor;
import com.migration.domain.NativeTypeDescriptor;
import com.migration.domain.SchemaModel;
import com.migration.domain.TableDescriptor;
import com.migration.domain.TableRef;
import com.migration.plan.DdlActionType;
import com.migration.plan.DdlPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code diffSchema} needs no live connection (only {@code applyDdl} does), so this
 * exercises design doc §3's classification rules directly: missing table/column →
 * create/add; present + compatible → no action; present + incompatible → conflict, never
 * auto-altered.
 */
class JdbcDdlReconcilerTest {

    private final JdbcDdlReconciler reconciler = new JdbcDdlReconciler(null, new PostgresSqlDialect());

    @Test
    void a_missing_table_produces_a_create_table_action() {
        SchemaModel desired = schemaWith(table("customers", column("id", CanonicalTypeKind.LONG, true)));
        SchemaModel existing = SchemaModel.of(List.of());

        DdlPlan plan = reconciler.diffSchema(desired, existing);

        assertThat(plan.actions()).hasSize(1);
        assertThat(plan.actions().get(0).type()).isEqualTo(DdlActionType.CREATE_TABLE);
        assertThat(plan.conflicts()).isEmpty();
    }

    @Test
    void a_missing_column_on_an_existing_table_produces_an_add_column_action() {
        SchemaModel desired = schemaWith(table("customers",
                column("id", CanonicalTypeKind.LONG, true), column("loyalty_tier", CanonicalTypeKind.STRING, false)));
        SchemaModel existing = schemaWith(table("customers", column("id", CanonicalTypeKind.LONG, true)));

        DdlPlan plan = reconciler.diffSchema(desired, existing);

        assertThat(plan.actions()).hasSize(1);
        assertThat(plan.actions().get(0).type()).isEqualTo(DdlActionType.ADD_COLUMN);
        assertThat(plan.actions().get(0).columnDescriptor().name()).isEqualTo("loyalty_tier");
    }

    @Test
    void an_identical_existing_column_produces_no_action() {
        SchemaModel desired = schemaWith(table("customers", column("id", CanonicalTypeKind.LONG, true)));
        SchemaModel existing = schemaWith(table("customers", column("id", CanonicalTypeKind.LONG, true)));

        DdlPlan plan = reconciler.diffSchema(desired, existing);

        assertThat(plan.actions()).isEmpty();
        assertThat(plan.conflicts()).isEmpty();
    }

    @Test
    void integer_widening_to_long_is_treated_as_compatible() {
        SchemaModel desired = schemaWith(table("customers", column("count", CanonicalTypeKind.INTEGER, false)));
        SchemaModel existing = schemaWith(table("customers", column("count", CanonicalTypeKind.LONG, false)));

        DdlPlan plan = reconciler.diffSchema(desired, existing);

        assertThat(plan.actions()).isEmpty();
        assertThat(plan.conflicts()).isEmpty();
    }

    @Test
    void an_incompatible_existing_column_is_flagged_as_a_conflict_never_auto_altered() {
        SchemaModel desired = schemaWith(table("customers", column("ssn", CanonicalTypeKind.STRING, false)));
        SchemaModel existing = schemaWith(table("customers", column("ssn", CanonicalTypeKind.BOOLEAN, false)));

        DdlPlan plan = reconciler.diffSchema(desired, existing);

        assertThat(plan.actions()).isEmpty();
        assertThat(plan.conflicts()).hasSize(1);
        assertThat(plan.conflicts().get(0).column()).isEqualTo("ssn");
    }

    @Test
    void a_new_table_in_a_non_default_schema_also_emits_a_create_schema_action_first() {
        TableDescriptor table = new TableDescriptor(new TableRef(null, "reporting", "customers"),
                List.of(column("id", CanonicalTypeKind.LONG, true)), List.of("id"), null);
        SchemaModel desired = SchemaModel.of(List.of(table));
        SchemaModel existing = SchemaModel.of(List.of());

        DdlPlan plan = reconciler.diffSchema(desired, existing);

        assertThat(plan.actions().get(0).type()).isEqualTo(DdlActionType.CREATE_SCHEMA);
        assertThat(plan.actions().get(0).schemaName()).isEqualTo("reporting");
        assertThat(plan.actions().get(1).type()).isEqualTo(DdlActionType.CREATE_TABLE);
    }

    private SchemaModel schemaWith(TableDescriptor table) {
        return SchemaModel.of(List.of(table));
    }

    private TableDescriptor table(String name, ColumnDescriptor... columns) {
        return new TableDescriptor(TableRef.of(name), List.of(columns), List.of("id"), null);
    }

    private ColumnDescriptor column(String name, CanonicalTypeKind kind, boolean primaryKey) {
        return new ColumnDescriptor(name, CanonicalType.of(kind), new NativeTypeDescriptor("varchar", null, null, null),
                !primaryKey, primaryKey, null);
    }
}
