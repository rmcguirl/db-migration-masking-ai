package com.migration.masking;

/** The strictly one-way masking technique palette (design doc §4) — no vault, no reversible mapping, ever. */
public enum MaskingTechniqueId {
    HASH_HMAC,
    REDACT_FULL,
    REDACT_PARTIAL,
    GENERALIZE_BUCKET,
    SYNTHETIC_SUBSTITUTION
}
