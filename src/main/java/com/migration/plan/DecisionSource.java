package com.migration.plan;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Why a column ended up with its sensitivity/technique verdict — recorded on every
 * {@link ColumnPlan} so {@code --plan-only} review and the audit log always show why a
 * column was, or wasn't, masked (design doc §6).
 */
public enum DecisionSource {
    AI("ai"),
    RULE("rule"),
    AI_AND_RULE("ai+rule"),
    OVERRIDE("override"),
    OVERRIDE_KEY_COLUMN("override-key-column"),
    LOW_CONFIDENCE_FALLBACK("low-confidence-fallback");

    private static final Map<String, DecisionSource> BY_WIRE_VALUE = Stream.of(values())
            .collect(Collectors.toMap(DecisionSource::wireValue, s -> s));

    private final String wireValue;

    DecisionSource(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static DecisionSource fromWireValue(String value) {
        DecisionSource match = BY_WIRE_VALUE.get(value);
        if (match == null) {
            throw new IllegalArgumentException("unknown decision source: " + value);
        }
        return match;
    }
}
