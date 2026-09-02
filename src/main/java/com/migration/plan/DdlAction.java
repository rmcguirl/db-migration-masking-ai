package com.migration.plan;

import com.migration.domain.ColumnDescriptor;
import com.migration.domain.TableDescriptor;
import com.migration.domain.TableRef;

/**
 * One additive-only DDL action to apply at the destination. {@code applyDdl(DdlPlan)}
 * receives no other context, so each action carries everything needed to execute it
 * standalone: {@code CREATE_SCHEMA} only sets {@code schemaName}; {@code CREATE_TABLE}
 * sets {@code table} and the full {@code tableDescriptor} to build from;
 * {@code ADD_COLUMN} sets {@code table} and the single {@code columnDescriptor} to add.
 */
public record DdlAction(DdlActionType type, TableRef table, String schemaName, TableDescriptor tableDescriptor,
                         ColumnDescriptor columnDescriptor) {

    public static DdlAction createSchema(String schemaName) {
        return new DdlAction(DdlActionType.CREATE_SCHEMA, null, schemaName, null, null);
    }

    public static DdlAction createTable(TableDescriptor table) {
        return new DdlAction(DdlActionType.CREATE_TABLE, table.ref(), null, table, null);
    }

    public static DdlAction addColumn(TableRef table, ColumnDescriptor column) {
        return new DdlAction(DdlActionType.ADD_COLUMN, table, null, null, column);
    }
}
