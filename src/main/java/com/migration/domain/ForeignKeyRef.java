package com.migration.domain;

import java.util.List;

/**
 * One foreign-key relationship, introspected or config-declared as a
 * {@code virtualForeignKey} (design doc §5). Column lists support composite keys; each
 * column is masked independently under the same technique/key so equality is preserved
 * column-by-column between parent and child.
 *
 * @param childTable    the table holding the foreign key
 * @param childColumns  the FK column(s), in the same order as {@code parentColumns}
 * @param parentTable   the referenced table
 * @param parentColumns the referenced key column(s)
 */
public record ForeignKeyRef(TableRef childTable, List<String> childColumns, TableRef parentTable,
                             List<String> parentColumns) {

    public ForeignKeyRef {
        if (childTable == null || parentTable == null) {
            throw new IllegalArgumentException("childTable and parentTable must not be null");
        }
        childColumns = List.copyOf(childColumns == null ? List.of() : childColumns);
        parentColumns = List.copyOf(parentColumns == null ? List.of() : parentColumns);
        if (childColumns.isEmpty() || childColumns.size() != parentColumns.size()) {
            throw new IllegalArgumentException(
                    "childColumns and parentColumns must be non-empty and of equal size");
        }
    }
}
