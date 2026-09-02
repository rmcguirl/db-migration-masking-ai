package com.migration.masking;

/** The output of applying one {@link MaskingTechnique} to one raw value. */
public record MaskedValue(Object value) {

    public static MaskedValue of(Object value) {
        return new MaskedValue(value);
    }
}
