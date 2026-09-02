package com.migration.connector.jdbc;

import com.migration.domain.CanonicalType;
import com.migration.domain.CanonicalTypeKind;
import com.migration.domain.NativeTypeDescriptor;
import com.migration.domain.TableRef;
import com.migration.plan.ColumnPlan;
import com.migration.plan.DdlConflict;
import com.migration.plan.DecisionSource;
import com.migration.plan.MaskingRule;
import com.migration.plan.TablePlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Covers design doc §3's DDL-conflict handling: exclude the column, or the whole table if a key column conflicts. */
class DdlConflictResolverTest {

    private final DdlConflictResolver resolver = new DdlConflictResolver();

    @Test
    void a_table_with_no_conflicts_passes_through_unchanged() {
        TablePlan plan = tablePlan(column("ssn", true), column("loyalty_tier", false));

        Optional<TablePlan> result = resolver.resolve(plan, List.of(), Set.of("id"));

        assertThat(result).contains(plan);
    }

    @Test
    void a_conflict_on_a_non_key_column_excludes_just_that_column_others_still_load() {
        TablePlan plan = tablePlan(column("ssn", true), column("loyalty_tier", false));
        DdlConflict conflict = conflict("loyalty_tier");

        Optional<TablePlan> result = resolver.resolve(plan, List.of(conflict), Set.of("id"));

        assertThat(result).isPresent();
        assertThat(result.get().columns()).extracting(ColumnPlan::source).containsExactly("ssn");
    }

    @Test
    void a_conflict_on_a_key_column_excludes_the_entire_table() {
        TablePlan plan = tablePlan(column("id", true), column("loyalty_tier", false));
        DdlConflict conflict = conflict("id");

        Optional<TablePlan> result = resolver.resolve(plan, List.of(conflict), Set.of("id"));

        assertThat(result).isEmpty();
    }

    private TablePlan tablePlan(ColumnPlan... columns) {
        return new TablePlan("customers", "customers", List.of("id"), 1, List.of(), List.of(columns), List.of());
    }

    private ColumnPlan column(String name, boolean sensitive) {
        MaskingRule rule = sensitive ? MaskingRule.technique("HASH_HMAC") : null;
        return new ColumnPlan(name, name, CanonicalType.of(CanonicalTypeKind.STRING), sensitive, 1.0,
                DecisionSource.RULE, List.of(), rule);
    }

    private DdlConflict conflict(String column) {
        return new DdlConflict(TableRef.of("customers"), column, CanonicalType.of(CanonicalTypeKind.STRING),
                new NativeTypeDescriptor("int", null, null, null), "type mismatch");
    }
}
