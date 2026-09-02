package com.migration.plan;

import com.migration.domain.TableRef;

import java.util.Map;

/** The AI provider's full response for one plan-generation call: one analysis per table. */
public record AiAnalysisResult(Map<TableRef, AiTableAnalysis> tables) {

    public AiAnalysisResult {
        tables = Map.copyOf(tables == null ? Map.of() : tables);
    }
}
