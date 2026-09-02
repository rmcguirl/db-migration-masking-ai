package com.migration.masking.technique;

import com.migration.domain.TableRef;
import com.migration.masking.MaskedValue;
import com.migration.masking.MaskingContext;
import com.migration.masking.MaskingTechniqueId;
import com.migration.plan.MaskingRule;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeneralizationTechniqueTest {

    private final GeneralizationTechnique technique = new GeneralizationTechnique();
    private final TableRef table = TableRef.of("customers");
    private final byte[] key = new byte[] {9};

    @Test
    void id_and_injectivity() {
        assertThat(technique.id()).isEqualTo(MaskingTechniqueId.GENERALIZE_BUCKET);
        assertThat(technique.isInjective()).isFalse();
    }

    @Test
    void year_granularity_reduces_a_date_of_birth_to_its_birth_year() {
        MaskedValue result = technique.apply(LocalDate.of(1990, 7, 14), context("YEAR"));

        assertThat(result.value()).isEqualTo(1990);
    }

    @Test
    void year_granularity_accepts_an_iso_date_string() {
        MaskedValue result = technique.apply("1990-07-14", context("YEAR"));

        assertThat(result.value()).isEqualTo(1990);
    }

    @Test
    void zip3_keeps_only_the_first_three_digits() {
        MaskedValue result = technique.apply("94107-1234", context("ZIP3"));

        assertThat(result.value()).isEqualTo("941");
    }

    @Test
    void zip3_leaves_a_shorter_value_unchanged() {
        MaskedValue result = technique.apply("94", context("ZIP3"));

        assertThat(result.value()).isEqualTo("94");
    }

    @Test
    void age_90_plus_aggregates_ages_at_or_above_90_per_hipaa_safe_harbor() {
        assertThat(technique.apply(90, context("AGE_90_PLUS")).value()).isEqualTo("90+");
        assertThat(technique.apply(101, context("AGE_90_PLUS")).value()).isEqualTo("90+");
        assertThat(technique.apply(89, context("AGE_90_PLUS")).value()).isEqualTo("89");
    }

    @Test
    void age_5year_band_buckets_into_a_5_year_range() {
        assertThat(technique.apply(42, context("AGE_5YEAR_BAND")).value()).isEqualTo("40-44");
        assertThat(technique.apply(45, context("AGE_5YEAR_BAND")).value()).isEqualTo("45-49");
    }

    @Test
    void null_input_masks_to_null() {
        assertThat(technique.apply(null, context("YEAR")).value()).isNull();
    }

    @Test
    void missing_granularity_fails_fast_rather_than_leaving_the_value_unmasked() {
        MaskingContext ctx = new MaskingContext(table, "dob", key, MaskingRule.technique("GENERALIZE_BUCKET"));

        assertThatThrownBy(() -> technique.apply(LocalDate.now(), ctx)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void unknown_granularity_fails_fast() {
        assertThatThrownBy(() -> technique.apply("x", context("NOT_A_REAL_GRANULARITY")))
                .isInstanceOf(RuntimeException.class);
    }

    private MaskingContext context(String granularity) {
        return new MaskingContext(table, "field", key, MaskingRule.bucket(granularity));
    }
}
