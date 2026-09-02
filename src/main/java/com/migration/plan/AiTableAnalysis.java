package com.migration.plan;

import java.util.List;
import java.util.Map;

/**
 * AI's analysis for one table: per-column verdicts, a suggested natural key, and — when
 * source and destination schemas differ — suggested field name mappings (design doc §6).
 *
 * @param columns                  per-column analysis
 * @param suggestedNaturalKey      AI-suggested natural key column(s); empty if none suggested
 * @param suggestedFieldMappings   source column name to destination column name, only for
 *                                 columns AI suggests renaming; empty otherwise
 */
public record AiTableAnalysis(
        List<AiColumnAnalysis> columns,
        List<String> suggestedNaturalKey,
        Map<String, String> suggestedFieldMappings) {

    public AiTableAnalysis {
        columns = List.copyOf(columns == null ? List.of() : columns);
        suggestedNaturalKey = List.copyOf(suggestedNaturalKey == null ? List.of() : suggestedNaturalKey);
        suggestedFieldMappings = Map.copyOf(suggestedFieldMappings == null ? Map.of() : suggestedFieldMappings);
    }
}
