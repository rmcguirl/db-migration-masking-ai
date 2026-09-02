package com.migration.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One extracted (or masked) row of data, addressed by column name. Order of
 * {@code values} follows insertion order (a {@code LinkedHashMap} is always used
 * internally) so writers that care about column order can rely on it.
 *
 * @param table  the table this row belongs to
 * @param values column name to value, in column order; values may be null
 */
public record Row(TableRef table, Map<String, Object> values) {

    public Row {
        if (table == null) {
            throw new IllegalArgumentException("table must not be null");
        }
        values = values == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public Object get(String column) {
        return values.get(column);
    }

    /** Returns a new {@code Row} with a single column value replaced; used by the masking engine. */
    public Row withValue(String column, Object newValue) {
        Map<String, Object> copy = new LinkedHashMap<>(values);
        copy.put(column, newValue);
        return new Row(table, copy);
    }
}
