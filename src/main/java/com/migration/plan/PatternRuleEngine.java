package com.migration.plan;

import com.migration.domain.TableRef;

import java.util.List;

/**
 * Deterministic, local regex/heuristic PII/PCI/PHI pattern detection over a sample of a
 * column's actual values. Fully independent of AI: runs regardless of AI availability or
 * opinion, and can only push a column toward "sensitive," never pull one out (design doc
 * §6). Sample values never leave the process at this step.
 */
public interface PatternRuleEngine {

    List<PatternSignal> scan(TableRef table, List<Object> sampleValues);
}
