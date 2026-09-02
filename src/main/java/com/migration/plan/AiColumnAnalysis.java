package com.migration.plan;

import java.util.List;

/**
 * One column's AI-classified verdict, before it's merged with pattern signals and
 * overrides into a {@link ColumnPlan} (design doc §6).
 *
 * @param column             column name
 * @param sensitive          AI's sensitivity verdict for this column alone
 * @param confidence         AI's confidence in that verdict, in [0,1]
 * @param regulatoryBasis    GDPR/HIPAA/PCI_DSS tag(s) justifying the classification
 * @param suggestedTechnique a {@code MaskingTechniqueId} wire value; constrained by the
 *                           provider to the one-way palette, and to {@code HASH_HMAC} for
 *                           any column flagged as PK/FK/candidate-key
 */
public record AiColumnAnalysis(
        String column,
        boolean sensitive,
        double confidence,
        List<String> regulatoryBasis,
        String suggestedTechnique) {

    public AiColumnAnalysis {
        regulatoryBasis = List.copyOf(regulatoryBasis == null ? List.of() : regulatoryBasis);
    }
}
