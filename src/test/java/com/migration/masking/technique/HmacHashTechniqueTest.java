package com.migration.masking.technique;

import com.migration.domain.TableRef;
import com.migration.masking.MaskedValue;
import com.migration.masking.MaskingContext;
import com.migration.masking.MaskingTechniqueId;
import com.migration.plan.MaskingRule;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class HmacHashTechniqueTest {

    private final HmacHashTechnique technique = new HmacHashTechnique();
    private final byte[] key = "test-secret-key".getBytes(StandardCharsets.UTF_8);
    private final byte[] otherKey = "different-secret-key".getBytes(StandardCharsets.UTF_8);
    private final TableRef table = TableRef.of("customers");

    @Test
    void id_is_hash_hmac() {
        assertThat(technique.id()).isEqualTo(MaskingTechniqueId.HASH_HMAC);
    }

    @Test
    void is_injective() {
        assertThat(technique.isInjective()).isTrue();
    }

    @Test
    void same_input_and_key_always_produce_the_same_output() {
        MaskingContext ctx = context(key, "BASE64URL");

        MaskedValue first = technique.apply("123-45-6789", ctx);
        MaskedValue second = technique.apply("123-45-6789", ctx);

        assertThat(first.value()).isEqualTo(second.value());
    }

    @Test
    void different_inputs_produce_different_outputs() {
        MaskingContext ctx = context(key, "BASE64URL");

        MaskedValue a = technique.apply("123-45-6789", ctx);
        MaskedValue b = technique.apply("987-65-4321", ctx);

        assertThat(a.value()).isNotEqualTo(b.value());
    }

    @Test
    void different_keys_produce_different_outputs_for_the_same_input() {
        MaskedValue withKey = technique.apply("123-45-6789", context(key, "BASE64URL"));
        MaskedValue withOtherKey = technique.apply("123-45-6789", context(otherKey, "BASE64URL"));

        assertThat(withKey.value()).isNotEqualTo(withOtherKey.value());
    }

    @Test
    void null_input_masks_to_null() {
        MaskedValue result = technique.apply(null, context(key, "BASE64URL"));

        assertThat(result.value()).isNull();
    }

    @Test
    void base64url_encoding_never_contains_padding_or_url_unsafe_characters() {
        MaskedValue result = technique.apply("sensitive-value", context(key, "BASE64URL"));

        assertThat((String) result.value()).matches(Pattern.compile("^[A-Za-z0-9_-]+$"));
    }

    @Test
    void hex_encoding_is_a_64_character_lowercase_hex_string_for_sha256() {
        MaskedValue result = technique.apply("sensitive-value", context(key, "HEX"));

        assertThat((String) result.value()).matches(Pattern.compile("^[0-9a-f]{64}$"));
    }

    @Test
    void output_never_contains_the_original_plaintext_value() {
        String secret = "super-secret-original-value";
        MaskedValue result = technique.apply(secret, context(key, "BASE64URL"));

        assertThat((String) result.value()).doesNotContain(secret);
    }

    private MaskingContext context(byte[] key, String encoding) {
        return new MaskingContext(table, "ssn", key, MaskingRule.hmac(encoding));
    }
}
