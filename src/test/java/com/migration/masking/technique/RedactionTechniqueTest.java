package com.migration.masking.technique;

import com.migration.domain.TableRef;
import com.migration.masking.MaskedValue;
import com.migration.masking.MaskingContext;
import com.migration.masking.MaskingTechniqueId;
import com.migration.plan.MaskingRule;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedactionTechniqueTest {

    private final TableRef table = TableRef.of("payment_methods");
    private final byte[] key = new byte[] {1, 2, 3};

    @Test
    void rejects_construction_for_a_non_redaction_technique_id() {
        assertThatThrownBy(() -> new RedactionTechnique(MaskingTechniqueId.HASH_HMAC))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void full_redaction_always_replaces_with_a_fixed_token() {
        RedactionTechnique technique = new RedactionTechnique(MaskingTechniqueId.REDACT_FULL);

        MaskedValue a = technique.apply("4111111111111111", context(null));
        MaskedValue b = technique.apply("some other value", context(null));

        assertThat(a.value()).isEqualTo("REDACTED");
        assertThat(b.value()).isEqualTo("REDACTED");
    }

    @Test
    void full_redaction_is_not_injective() {
        assertThat(new RedactionTechnique(MaskingTechniqueId.REDACT_FULL).isInjective()).isFalse();
    }

    @Test
    void partial_redaction_keeps_the_configured_number_of_trailing_characters() {
        RedactionTechnique technique = new RedactionTechnique(MaskingTechniqueId.REDACT_PARTIAL);
        String pan = "4111111111111111";
        String expected = "*".repeat(pan.length() - 4) + "1111";

        MaskedValue result = technique.apply(pan, context(4));

        assertThat(result.value()).isEqualTo(expected);
    }

    @Test
    void partial_redaction_defaults_to_keeping_last_4_when_keepLast_is_unset() {
        RedactionTechnique technique = new RedactionTechnique(MaskingTechniqueId.REDACT_PARTIAL);
        String pan = "4111111111111111";
        String expected = "*".repeat(pan.length() - 4) + "1111";

        MaskedValue result = technique.apply(pan, context(null));

        assertThat(result.value()).isEqualTo(expected);
    }

    @Test
    void partial_redaction_never_reveals_more_than_the_original_length() {
        RedactionTechnique technique = new RedactionTechnique(MaskingTechniqueId.REDACT_PARTIAL);

        MaskedValue result = technique.apply("12", context(4));

        assertThat(result.value()).isEqualTo("12");
    }

    @Test
    void null_input_masks_to_null_for_both_variants() {
        assertThat(new RedactionTechnique(MaskingTechniqueId.REDACT_FULL).apply(null, context(null)).value()).isNull();
        assertThat(new RedactionTechnique(MaskingTechniqueId.REDACT_PARTIAL).apply(null, context(4)).value()).isNull();
    }

    private MaskingContext context(Integer keepLast) {
        MaskingRule rule = keepLast == null ? MaskingRule.technique("REDACT_PARTIAL") : MaskingRule.redactPartial(keepLast);
        return new MaskingContext(table, "pan", key, rule);
    }
}
