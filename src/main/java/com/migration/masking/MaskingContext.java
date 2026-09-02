package com.migration.masking;

import com.migration.domain.TableRef;
import com.migration.plan.MaskingRule;

/**
 * Everything one {@link MaskingTechnique#apply} call needs beyond the raw value itself:
 * which column it's masking (for deterministic per-column salting where a technique
 * wants it), the secret key, and the technique-specific parameters from the plan's
 * {@link MaskingRule} (encoding, granularity, keepLast, corpus — design doc §4).
 *
 * @param key never logged or persisted; {@code MaskingKeyProvider} zeroes it after use
 */
public record MaskingContext(TableRef table, String column, byte[] key, MaskingRule rule) {
}
