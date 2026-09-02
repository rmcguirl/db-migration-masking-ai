package com.migration.referential;

import com.migration.domain.ColumnDescriptor;
import com.migration.domain.CanonicalType;
import com.migration.domain.CanonicalTypeKind;
import com.migration.domain.ForeignKeyRef;
import com.migration.domain.SchemaModel;
import com.migration.domain.TableDescriptor;
import com.migration.domain.TableRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers design doc §5's referential-integrity requirements: composite keys and a
 * self-referencing FK don't block topological sort, and a genuine multi-table cycle is
 * detected as a strongly-connected component.
 */
class DefaultReferentialGraphBuilderTest {

    private final DefaultReferentialGraphBuilder builder = new DefaultReferentialGraphBuilder();

    @Test
    void orders_a_simple_parent_child_chain_so_the_parent_loads_first() {
        SchemaModel schema = schemaWithForeignKeys(
                tables("customers", "orders", "order_items"),
                List.of(
                        fk("orders", List.of("customer_id"), "customers", List.of("id")),
                        fk("order_items", List.of("order_id"), "orders", List.of("id"))));

        DependencyGraph graph = builder.build(schema, List.of());
        List<TableRef> order = graph.topologicalLoadOrder();

        assertThat(indexOf(order, "customers")).isLessThan(indexOf(order, "orders"));
        assertThat(indexOf(order, "orders")).isLessThan(indexOf(order, "order_items"));
    }

    @Test
    void tables_with_no_interdependency_land_in_the_same_layer() {
        SchemaModel schema = schemaWithForeignKeys(tables("products", "categories"), List.of());

        DependencyGraph graph = builder.build(schema, List.of());
        List<List<TableRef>> layers = graph.loadLayers();

        assertThat(layers).hasSize(1);
        assertThat(layers.get(0)).hasSize(2);
    }

    @Test
    void a_self_referencing_fk_does_not_create_a_cycle_or_block_ordering() {
        SchemaModel schema = schemaWithForeignKeys(
                tables("employees"),
                List.of(fk("employees", List.of("manager_id"), "employees", List.of("id"))));

        DependencyGraph graph = builder.build(schema, List.of());

        assertThat(graph.detectCycles()).isEmpty();
        assertThat(graph.topologicalLoadOrder()).containsExactly(TableRef.of("employees"));
    }

    @Test
    void composite_fk_columns_are_carried_through_to_the_edge_without_needing_concatenation() {
        SchemaModel schema = schemaWithForeignKeys(
                tables("tenants_customers", "tenants_orders"),
                List.of(fk("tenants_orders", List.of("tenant_id", "customer_id"),
                        "tenants_customers", List.of("tenant_id", "customer_id"))));

        DependencyGraph graph = builder.build(schema, List.of());
        List<TableRef> order = graph.topologicalLoadOrder();

        assertThat(indexOf(order, "tenants_customers")).isLessThan(indexOf(order, "tenants_orders"));
    }

    @Test
    void a_true_two_table_cycle_is_reported_as_a_strongly_connected_component() {
        SchemaModel schema = schemaWithForeignKeys(
                tables("employees_cyc", "departments_cyc"),
                List.of(
                        fk("employees_cyc", List.of("dept_id"), "departments_cyc", List.of("id")),
                        fk("departments_cyc", List.of("manager_emp_id"), "employees_cyc", List.of("id"))));

        DependencyGraph graph = builder.build(schema, List.of());
        List<List<TableRef>> cycles = graph.detectCycles();

        assertThat(cycles).hasSize(1);
        assertThat(cycles.get(0)).containsExactlyInAnyOrder(TableRef.of("employees_cyc"), TableRef.of("departments_cyc"));
    }

    @Test
    void transitive_dependents_reaches_every_downstream_table_not_just_direct_children() {
        SchemaModel schema = schemaWithForeignKeys(
                tables("a", "b", "c"),
                List.of(
                        fk("b", List.of("a_id"), "a", List.of("id")),
                        fk("c", List.of("b_id"), "b", List.of("id"))));

        DependencyGraph graph = builder.build(schema, List.of());
        Set<TableRef> dependents = graph.transitiveDependents(TableRef.of("a"));

        assertThat(dependents).containsExactlyInAnyOrder(TableRef.of("b"), TableRef.of("c"));
    }

    @Test
    void a_table_with_no_fk_metadata_still_appears_in_its_own_layer() {
        SchemaModel schema = schemaWithForeignKeys(tables("orphan_table"), List.of());

        DependencyGraph graph = builder.build(schema, List.of());

        assertThat(graph.topologicalLoadOrder()).containsExactly(TableRef.of("orphan_table"));
    }

    private int indexOf(List<TableRef> order, String table) {
        return order.indexOf(TableRef.of(table));
    }

    private List<TableDescriptor> tables(String... names) {
        return List.of(names).stream()
                .map(name -> new TableDescriptor(TableRef.of(name),
                        List.of(new ColumnDescriptor("id", CanonicalType.of(CanonicalTypeKind.LONG), null, false, true, null)),
                        List.of("id"), null))
                .toList();
    }

    private ForeignKeyRef fk(String childTable, List<String> childColumns, String parentTable, List<String> parentColumns) {
        return new ForeignKeyRef(TableRef.of(childTable), childColumns, TableRef.of(parentTable), parentColumns);
    }

    private SchemaModel schemaWithForeignKeys(List<TableDescriptor> tables, List<ForeignKeyRef> foreignKeys) {
        return SchemaModel.of(tables, foreignKeys);
    }
}
