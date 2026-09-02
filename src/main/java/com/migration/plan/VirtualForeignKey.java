package com.migration.plan;

import com.migration.domain.ForeignKeyRef;
import com.migration.domain.TableRef;

import java.util.List;

/**
 * A human-declared logical FK, resolved from {@code config.VirtualForeignKeyConfig}'s
 * plain table-name strings into qualified {@link TableRef}s. Participates in
 * {@code ReferentialGraphBuilder} construction identically to an introspected FK
 * (design doc §5); {@link #toForeignKeyRef()} is how it joins that graph.
 */
public record VirtualForeignKey(TableRef childTable, List<String> childColumns, TableRef parentTable,
                                 List<String> parentColumns) {

    public VirtualForeignKey {
        childColumns = List.copyOf(childColumns == null ? List.of() : childColumns);
        parentColumns = List.copyOf(parentColumns == null ? List.of() : parentColumns);
    }

    public ForeignKeyRef toForeignKeyRef() {
        return new ForeignKeyRef(childTable, childColumns, parentTable, parentColumns);
    }
}
