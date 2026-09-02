package com.migration.masking;

import com.migration.domain.CanonicalType;
import com.migration.domain.CanonicalTypeKind;
import com.migration.domain.exception.PlanValidationException;
import com.migration.masking.technique.GeneralizationTechnique;
import com.migration.masking.technique.HmacHashTechnique;
import com.migration.masking.technique.RedactionTechnique;
import com.migration.plan.ColumnPlan;
import com.migration.plan.DecisionSource;
import com.migration.plan.MaskingRule;
import com.migration.plan.TablePlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyColumnMaskingValidatorTest {

    private final KeyColumnMaskingValidator validator = new KeyColumnMaskingValidator(
            List.of(new HmacHashTechnique(), new GeneralizationTechnique(),
                    new RedactionTechnique(MaskingTechniqueId.REDACT_FULL)));

    @Test
    void rejects_a_non_injective_technique_on_a_key_column() {
        TablePlan plan = tablePlan(sensitiveColumn("customer_ref", "GENERALIZE_BUCKET"));

        assertThatThrownBy(() -> validator.validate(plan, Set.of("customer_ref")))
                .isInstanceOf(PlanValidationException.class)
                .hasMessageContaining("customer_ref");
    }

    @Test
    void accepts_hash_hmac_on_a_key_column() {
        TablePlan plan = tablePlan(sensitiveColumn("customer_ref", "HASH_HMAC"));

        assertThatCode(() -> validator.validate(plan, Set.of("customer_ref"))).doesNotThrowAnyException();
    }

    @Test
    void ignores_columns_that_are_not_sensitive_even_if_they_are_key_columns() {
        TablePlan plan = tablePlan(nonSensitiveColumn("customer_ref"));

        assertThatCode(() -> validator.validate(plan, Set.of("customer_ref"))).doesNotThrowAnyException();
    }

    @Test
    void ignores_non_injective_techniques_on_columns_that_are_not_key_columns() {
        TablePlan plan = tablePlan(sensitiveColumn("email", "GENERALIZE_BUCKET"), sensitiveColumn("customer_ref", "HASH_HMAC"));

        assertThatCode(() -> validator.validate(plan, Set.of("customer_ref"))).doesNotThrowAnyException();
    }

    private TablePlan tablePlan(ColumnPlan... columns) {
        return new TablePlan("customers", "customers", List.of("customer_ref"), 1, List.of(), List.of(columns), List.of());
    }

    private ColumnPlan sensitiveColumn(String name, String technique) {
        MaskingRule rule = technique.equals("GENERALIZE_BUCKET") ? MaskingRule.bucket("YEAR") : MaskingRule.technique(technique);
        return new ColumnPlan(name, name, CanonicalType.of(CanonicalTypeKind.STRING), true, 1.0,
                DecisionSource.OVERRIDE_KEY_COLUMN, List.of(), rule);
    }

    private ColumnPlan nonSensitiveColumn(String name) {
        return new ColumnPlan(name, name, CanonicalType.of(CanonicalTypeKind.STRING), false, 1.0,
                DecisionSource.RULE, List.of(), null);
    }
}
