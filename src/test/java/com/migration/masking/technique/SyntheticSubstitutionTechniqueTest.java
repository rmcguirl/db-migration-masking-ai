package com.migration.masking.technique;

import com.migration.domain.TableRef;
import com.migration.masking.MaskedValue;
import com.migration.masking.MaskingContext;
import com.migration.masking.MaskingTechniqueId;
import com.migration.plan.MaskingRule;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SyntheticSubstitutionTechniqueTest {

    private final SyntheticSubstitutionTechnique technique = new SyntheticSubstitutionTechnique(new HmacHashTechnique());
    private final TableRef table = TableRef.of("customers");
    private final byte[] key = "corpus-test-key".getBytes();

    @Test
    void id_and_injectivity() {
        assertThat(technique.id()).isEqualTo(MaskingTechniqueId.SYNTHETIC_SUBSTITUTION);
        assertThat(technique.isInjective()).isFalse();
    }

    @Test
    void same_input_and_key_always_produce_the_same_synthetic_value() {
        MaskedValue first = technique.apply("alice@example.com", context("email"));
        MaskedValue second = technique.apply("alice@example.com", context("email"));

        assertThat(first.value()).isEqualTo(second.value());
    }

    @Test
    void the_synthetic_value_is_drawn_from_the_requested_corpus_and_never_equals_the_input() {
        String input = "alice@example.com";
        MaskedValue result = technique.apply(input, context("email"));

        assertThat((String) result.value()).contains("@").isNotEqualTo(input);
    }

    @Test
    void name_corpus_produces_a_two_word_placeholder_name() {
        MaskedValue result = technique.apply("Alice Example", context("name"));

        assertThat((String) result.value()).contains(" ");
    }

    @Test
    void defaults_to_the_name_corpus_when_none_specified() {
        MaskingContext ctx = new MaskingContext(table, "field", key, MaskingRule.technique("SYNTHETIC_SUBSTITUTION"));

        MaskedValue result = technique.apply("some value", ctx);

        assertThat(result.value()).isNotNull();
    }

    @Test
    void unknown_corpus_fails_fast() {
        assertThatThrownBy(() -> technique.apply("x", context("not-a-real-corpus")))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void null_input_masks_to_null() {
        assertThat(technique.apply(null, context("email")).value()).isNull();
    }

    private MaskingContext context(String corpus) {
        return new MaskingContext(table, "field", key, MaskingRule.syntheticSubstitution(corpus));
    }
}
