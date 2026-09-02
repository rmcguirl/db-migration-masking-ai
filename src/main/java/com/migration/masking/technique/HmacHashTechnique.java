package com.migration.masking.technique;

import com.migration.domain.exception.MigrationException;
import com.migration.masking.MaskedValue;
import com.migration.masking.MaskingContext;
import com.migration.masking.MaskingTechnique;
import com.migration.masking.MaskingTechniqueId;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * HMAC-SHA-256(secret key, canonicalized value), the one injective technique in the
 * palette (design doc §4) — satisfies PCI-DSS 3.4's "render PAN unreadable via one-way
 * hash" and is GDPR pseudonymization (consistent, re-identifiable if the key + candidate
 * plaintext are both known — not anonymization on its own). A pure function of
 * {@code (value, key)}, so parent and child rows can be masked independently of load
 * order (design doc §5) — this is what makes it the only technique eligible for
 * primary/foreign/natural-key columns ({@link #isInjective()} = {@code true}).
 */
@Component
public class HmacHashTechnique implements MaskingTechnique {

    private static final String ALGORITHM = "HmacSHA256";

    @Override
    public MaskingTechniqueId id() {
        return MaskingTechniqueId.HASH_HMAC;
    }

    @Override
    public boolean isInjective() {
        return true;
    }

    @Override
    public MaskedValue apply(Object rawValue, MaskingContext ctx) {
        if (rawValue == null) {
            return MaskedValue.of(null);
        }
        byte[] digest = hmac(ctx.key(), canonicalize(rawValue));
        String encoding = ctx.rule() != null && ctx.rule().encoding() != null ? ctx.rule().encoding() : "BASE64URL";
        return MaskedValue.of(encode(digest, encoding));
    }

    /** Exposed for {@code SyntheticSubstitutionTechnique}, which seeds its generator from this same digest. */
    public byte[] digest(Object rawValue, byte[] key) {
        return hmac(key, canonicalize(rawValue));
    }

    private String canonicalize(Object rawValue) {
        return String.valueOf(rawValue);
    }

    private byte[] hmac(byte[] key, String canonicalValue) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac.doFinal(canonicalValue.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new MigrationException("failed to compute HMAC", e);
        }
    }

    private String encode(byte[] digest, String encoding) {
        return switch (encoding.toUpperCase()) {
            case "HEX" -> HexFormat.of().formatHex(digest);
            case "BASE64URL" -> Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
            default -> throw new MigrationException("unknown HASH_HMAC encoding: " + encoding);
        };
    }
}
