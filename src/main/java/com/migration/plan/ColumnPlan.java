package com.migration.plan;

import com.migration.domain.CanonicalType;

import java.util.List;

/**
 * The resolved plan for one column: whether it's sensitive, how confident that verdict
 * is, which regulatory framework(s) justify it, and (if sensitive) the masking rule to
 * apply. {@code masking} is null when {@code sensitive} is false.
 *
 * @param source          source column name
 * @param destination     destination column name (may differ if AI suggested a field mapping)
 * @param canonicalType   the mapped canonical type
 * @param sensitive       final sensitivity verdict: OR of AI and pattern-rule signals (design doc §6)
 * @param confidence      the AI classification's confidence, or 1.0 for a rule/override-only verdict
 * @param decisionSource  why this verdict was reached
 * @param regulatoryBasis GDPR/HIPAA/PCI_DSS tags justifying the classification; empty if not sensitive
 * @param masking         the assigned technique and its parameters; null if not sensitive
 */
public record ColumnPlan(
        String source,
        String destination,
        CanonicalType canonicalType,
        boolean sensitive,
        double confidence,
        DecisionSource decisionSource,
        List<String> regulatoryBasis,
        MaskingRule masking) {

    public ColumnPlan {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        if (destination == null || destination.isBlank()) {
            throw new IllegalArgumentException("destination must not be blank");
        }
        regulatoryBasis = List.copyOf(regulatoryBasis == null ? List.of() : regulatoryBasis);
        if (sensitive && masking == null) {
            throw new IllegalArgumentException("sensitive column '" + source + "' must have a masking rule");
        }
    }
}
