package com.migration.plan;

/**
 * The masking assignment for one sensitive column. {@code technique} is a
 * {@code MaskingTechniqueId} wire value (kept as a String here so the plan model has no
 * compile-time dependency on the masking package); the remaining fields are
 * technique-specific and left null when not applicable (design doc §4):
 * {@code encoding} for {@code HASH_HMAC}, {@code granularity} for
 * {@code GENERALIZE_BUCKET}, {@code keepLast} for {@code REDACT_PARTIAL}, {@code corpus}
 * for {@code SYNTHETIC_SUBSTITUTION}.
 */
public record MaskingRule(String technique, String encoding, String granularity, Integer keepLast, String corpus) {

    public static MaskingRule technique(String technique) {
        return new MaskingRule(technique, null, null, null, null);
    }

    public static MaskingRule hmac(String encoding) {
        return new MaskingRule("HASH_HMAC", encoding, null, null, null);
    }

    public static MaskingRule bucket(String granularity) {
        return new MaskingRule("GENERALIZE_BUCKET", null, granularity, null, null);
    }

    public static MaskingRule redactPartial(int keepLast) {
        return new MaskingRule("REDACT_PARTIAL", null, null, keepLast, null);
    }

    public static MaskingRule syntheticSubstitution(String corpus) {
        return new MaskingRule("SYNTHETIC_SUBSTITUTION", null, null, null, corpus);
    }
}
