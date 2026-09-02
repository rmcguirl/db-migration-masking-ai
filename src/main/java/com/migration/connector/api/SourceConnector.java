package com.migration.connector.api;

import com.migration.domain.ExtractCursor;
import com.migration.domain.SchemaModel;
import com.migration.domain.TableRef;

/**
 * A connector usable as a migration source. {@code introspectSchema()} is authoritative
 * metadata for a relational engine, or a sampling-based inference for a document store
 * (design doc's non-relational-support flag) — callers never need to know which.
 */
public interface SourceConnector extends Connector {

    SchemaModel introspectSchema();

    /**
     * Reads one chunk of rows ordered by the table's primary/natural key, starting after
     * {@code cursor} (keyset pagination, never {@code OFFSET}, so restart is stable).
     */
    RowStream extract(TableRef table, ExtractCursor cursor, int batchSize);
}
