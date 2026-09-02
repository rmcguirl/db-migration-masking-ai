package com.migration.connector.api;

import com.migration.domain.Row;

/**
 * A closeable stream of one extraction chunk's rows. Backed by a JDBC {@code ResultSet}
 * or a Mongo cursor depending on the connector; callers must close it (try-with-resources)
 * so the underlying cursor/statement is released even if iteration is abandoned early.
 */
public interface RowStream extends Iterable<Row>, AutoCloseable {

    @Override
    void close();
}
