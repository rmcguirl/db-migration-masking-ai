package com.migration.plan.ai;

/** One column's metadata as sent to the AI provider — schema shape only, never a data value. */
public record AiColumnMetadata(String name, String canonicalType, boolean nullable, boolean primaryKey, String comment) {
}
