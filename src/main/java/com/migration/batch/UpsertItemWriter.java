package com.migration.batch;

import com.migration.connector.api.DestinationConnector;
import com.migration.connector.api.UpsertResult;
import com.migration.domain.Row;
import com.migration.domain.TableRef;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Upserts one committed chunk of masked rows to the destination, by natural key.
 * Accumulates running insert/update totals ({@link #totals()}) since Spring Batch's own
 * step metrics only track a single write count, not the insert/update split
 * {@code UpsertResult} provides.
 */
public class UpsertItemWriter implements ItemWriter<Row> {

    private final DestinationConnector destination;
    private final TableRef destinationTable;
    private final List<String> naturalKeyColumns;
    private final AtomicLong totalInserted = new AtomicLong();
    private final AtomicLong totalUpdated = new AtomicLong();

    public UpsertItemWriter(DestinationConnector destination, TableRef destinationTable, List<String> naturalKeyColumns) {
        this.destination = destination;
        this.destinationTable = destinationTable;
        this.naturalKeyColumns = naturalKeyColumns;
    }

    @Override
    public void write(Chunk<? extends Row> chunk) {
        UpsertResult result = destination.upsert(destinationTable, naturalKeyColumns, new ArrayList<>(chunk.getItems()));
        totalInserted.addAndGet(result.inserted());
        totalUpdated.addAndGet(result.updated());
    }

    public UpsertResult totals() {
        return new UpsertResult(totalInserted.get(), totalUpdated.get());
    }
}
