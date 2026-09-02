package com.migration.batch;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.migration.connector.api.RowStream;
import com.migration.connector.api.SourceConnector;
import com.migration.domain.ExtractCursor;
import com.migration.domain.Row;
import com.migration.domain.TableRef;
import com.migration.domain.exception.MigrationException;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.ItemStreamReader;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads one table's rows via {@link SourceConnector#extract}, one keyset page (chunk) at
 * a time. Persists its cursor into the step's {@link ExecutionContext} on every commit
 * and restores it in {@link #open}, so a restarted step resumes from the last committed
 * row rather than re-scanning the table from the start (design doc §7 step 9–10). Tracks
 * cursor position by {@code keyColumns} — the table's own primary key, which must match
 * whatever column(s) the connector's {@code extract()} implementation paginates by
 * internally (every connector in this app orders by primary key).
 */
public class KeysetPaginatingItemReader implements ItemStreamReader<Row> {

    private static final String CURSOR_CONTEXT_KEY = "keyset.cursor.json";

    private final SourceConnector source;
    private final TableRef table;
    private final List<String> keyColumns;
    private final int batchSize;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private ExtractCursor cursor = ExtractCursor.START;
    private Iterator<Row> currentPage = List.<Row>of().iterator();
    private boolean exhausted = false;

    public KeysetPaginatingItemReader(SourceConnector source, TableRef table, List<String> keyColumns, int batchSize) {
        this.source = source;
        this.table = table;
        this.keyColumns = keyColumns;
        this.batchSize = batchSize;
    }

    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        if (executionContext.containsKey(CURSOR_CONTEXT_KEY)) {
            try {
                Map<String, Object> lastKeyValues = objectMapper.readValue(
                        executionContext.getString(CURSOR_CONTEXT_KEY), new TypeReference<>() {
                        });
                cursor = new ExtractCursor(lastKeyValues);
            } catch (Exception e) {
                throw new ItemStreamException("failed to restore keyset cursor for " + table.qualifiedName(), e);
            }
        }
    }

    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        try {
            executionContext.putString(CURSOR_CONTEXT_KEY, objectMapper.writeValueAsString(cursor.lastKeyValues()));
        } catch (Exception e) {
            throw new ItemStreamException("failed to persist keyset cursor for " + table.qualifiedName(), e);
        }
    }

    @Override
    public void close() {
        // no held resources between pages: each page's RowStream is closed as soon as it's drained
    }

    @Override
    public Row read() {
        if (exhausted) {
            return null;
        }
        if (!currentPage.hasNext()) {
            fetchNextPage();
            if (!currentPage.hasNext()) {
                exhausted = true;
                return null;
            }
        }
        Row row = currentPage.next();
        Map<String, Object> keyValues = new LinkedHashMap<>();
        for (String keyColumn : keyColumns) {
            keyValues.put(keyColumn, row.get(keyColumn));
        }
        cursor = new ExtractCursor(keyValues);
        return row;
    }

    private void fetchNextPage() {
        List<Row> rows = new ArrayList<>();
        try (RowStream stream = source.extract(table, cursor, batchSize)) {
            for (Row row : stream) {
                rows.add(row);
            }
        } catch (RuntimeException e) {
            throw new MigrationException("failed to read next page for " + table.qualifiedName(), e);
        }
        currentPage = rows.iterator();
    }
}
