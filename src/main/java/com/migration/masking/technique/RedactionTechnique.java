package com.migration.masking.technique;

import com.migration.masking.MaskedValue;
import com.migration.masking.MaskingContext;
import com.migration.masking.MaskingTechnique;
import com.migration.masking.MaskingTechniqueId;

/**
 * Replaces a value with a fixed token ({@code REDACT_FULL}) or a partial mask keeping a
 * fixed number of trailing characters ({@code REDACT_PARTIAL}, e.g. last 4 of a PAN).
 * PCI-DSS 3.4 explicitly permits truncation (max first 6 / last 4 digits) as an
 * acceptable rendering-unreadable method; also implements HIPAA Safe Harbor identifier
 * removal (design doc §4).
 *
 * <p>One class serves both {@code MaskingTechniqueId} values (see
 * {@link RedactionTechniqueConfig}, which registers one bean per id) rather than one
 * {@code MaskingTechnique} instance per id, since the only difference is which branch of
 * {@link #apply} runs.
 */
public class RedactionTechnique implements MaskingTechnique {

    private static final int DEFAULT_KEEP_LAST = 4;

    private final MaskingTechniqueId id;

    public RedactionTechnique(MaskingTechniqueId id) {
        if (id != MaskingTechniqueId.REDACT_FULL && id != MaskingTechniqueId.REDACT_PARTIAL) {
            throw new IllegalArgumentException("RedactionTechnique only serves REDACT_FULL/REDACT_PARTIAL, got " + id);
        }
        this.id = id;
    }

    @Override
    public MaskingTechniqueId id() {
        return id;
    }

    @Override
    public boolean isInjective() {
        return false;
    }

    @Override
    public MaskedValue apply(Object rawValue, MaskingContext ctx) {
        if (rawValue == null) {
            return MaskedValue.of(null);
        }
        if (id == MaskingTechniqueId.REDACT_FULL) {
            return MaskedValue.of("REDACTED");
        }
        String value = String.valueOf(rawValue);
        int keepLast = ctx.rule() != null && ctx.rule().keepLast() != null ? ctx.rule().keepLast() : DEFAULT_KEEP_LAST;
        int keep = Math.min(keepLast, value.length());
        String masked = "*".repeat(value.length() - keep) + value.substring(value.length() - keep);
        return MaskedValue.of(masked);
    }
}
