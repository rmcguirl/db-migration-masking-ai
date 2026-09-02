package com.migration.plan;

import com.migration.domain.TableRef;

/**
 * A deterministic, local pattern-match result for one column, produced by
 * {@code PatternRuleEngine} from a small sample of actual values and sent to the AI
 * provider as a count/label summary — never the raw values themselves (design doc §6).
 *
 * @param table          the table the column belongs to
 * @param column         column name
 * @param matchRate      fraction of sampled values matching {@code matchedPattern}, in [0,1]
 * @param matchedPattern the pattern library entry matched (e.g. "US_SSN", "EMAIL"); null if none matched
 */
public record PatternSignal(TableRef table, String column, double matchRate, String matchedPattern) {
}
