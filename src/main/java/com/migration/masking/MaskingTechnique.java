package com.migration.masking;

/**
 * One strictly one-way masking technique. {@link #isInjective()} gates eligibility for
 * primary/foreign/natural-key columns (design doc §4): only an injective technique
 * guarantees two distinct source values never collide into the same masked value, which
 * is required for a masked FK to reliably equal its masked parent PK.
 */
public interface MaskingTechnique {

    MaskingTechniqueId id();

    boolean isInjective();

    MaskedValue apply(Object rawValue, MaskingContext ctx);
}
