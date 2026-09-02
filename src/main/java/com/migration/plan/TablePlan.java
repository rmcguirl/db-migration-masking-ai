package com.migration.plan;

import java.util.List;

/**
 * The resolved plan for one table: its natural key, its position/dependencies in load
 * order, its per-column masking assignments, and the DDL actions needed to create it at
 * the destination (design doc §9b).
 *
 * @param sourceTable      source table/collection name
 * @param destinationTable destination table/collection name (may differ from source)
 * @param naturalKey       natural key column(s), used for upsert
 * @param loadOrder        this table's position in the topologically-sorted load order
 * @param dependsOn        names of tables this one's FKs point to (parents)
 * @param columns          per-column plans
 * @param ddlActions       additive DDL needed to bring the destination table up to date
 */
public record TablePlan(
        String sourceTable,
        String destinationTable,
        List<String> naturalKey,
        int loadOrder,
        List<String> dependsOn,
        List<ColumnPlan> columns,
        List<DdlAction> ddlActions) {

    public TablePlan {
        if (sourceTable == null || sourceTable.isBlank()) {
            throw new IllegalArgumentException("sourceTable must not be blank");
        }
        if (destinationTable == null || destinationTable.isBlank()) {
            throw new IllegalArgumentException("destinationTable must not be blank");
        }
        naturalKey = List.copyOf(naturalKey == null ? List.of() : naturalKey);
        dependsOn = List.copyOf(dependsOn == null ? List.of() : dependsOn);
        columns = List.copyOf(columns == null ? List.of() : columns);
        ddlActions = List.copyOf(ddlActions == null ? List.of() : ddlActions);
    }

    public ColumnPlan column(String sourceColumnName) {
        return columns.stream()
                .filter(c -> c.source().equalsIgnoreCase(sourceColumnName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "no such column '" + sourceColumnName + "' in table plan for " + sourceTable));
    }
}
