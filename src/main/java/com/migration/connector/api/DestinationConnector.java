package com.migration.connector.api;

import com.migration.domain.Row;
import com.migration.domain.SchemaModel;
import com.migration.domain.TableRef;

import java.util.List;

/** A connector usable as a migration destination. */
public interface DestinationConnector extends Connector {

    SchemaModel introspectSchema();

    /** Upserts a batch of already-masked rows into {@code table}, keyed by {@code naturalKeyColumns}. */
    UpsertResult upsert(TableRef table, List<String> naturalKeyColumns, List<Row> rows);
}
