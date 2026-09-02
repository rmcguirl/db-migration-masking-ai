package com.migration.plan.ai;

import com.migration.plan.PatternSignal;

import java.util.List;

/** One table's request payload: schema metadata plus local pattern-rule signals — counts and pattern names, never raw values. */
public record AiTableRequest(String tableName, List<AiColumnMetadata> columns, List<PatternSignal> patternSignals) {
}
