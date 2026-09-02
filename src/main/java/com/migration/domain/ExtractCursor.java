package com.migration.domain;

import java.util.Map;

/**
 * An opaque, keyset-pagination cursor: the primary/natural key column values of the last
 * row read in the previous chunk. Extraction is ordered by key, never {@code OFFSET}, so
 * a resumed step can re-derive exactly where to continue (design doc §7, §8) without
 * re-scanning already-read rows.
 *
 * @param lastKeyValues key column name to value, for the last row of the previous chunk;
 *                      empty for the start of a table
 */
public record ExtractCursor(Map<String, Object> lastKeyValues) {

    public static final ExtractCursor START = new ExtractCursor(Map.of());

    public ExtractCursor {
        lastKeyValues = Map.copyOf(lastKeyValues == null ? Map.of() : lastKeyValues);
    }

    public boolean isStart() {
        return lastKeyValues.isEmpty();
    }

    public static ExtractCursor of(Map<String, Object> lastKeyValues) {
        return new ExtractCursor(lastKeyValues);
    }
}
