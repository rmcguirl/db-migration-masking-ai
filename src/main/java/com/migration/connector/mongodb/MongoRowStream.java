package com.migration.connector.mongodb;

import com.migration.connector.api.RowStream;
import com.migration.domain.Row;
import com.migration.domain.TableRef;
import com.mongodb.client.MongoCursor;
import org.bson.Document;

import java.util.Iterator;
import java.util.LinkedHashMap;

/** Wraps one extraction chunk's {@link MongoCursor} as a lazily-iterated {@link RowStream}. */
final class MongoRowStream implements RowStream {

    private final MongoCursor<Document> cursor;
    private final TableRef collection;

    MongoRowStream(MongoCursor<Document> cursor, TableRef collection) {
        this.cursor = cursor;
        this.collection = collection;
    }

    @Override
    public Iterator<Row> iterator() {
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return cursor.hasNext();
            }

            @Override
            public Row next() {
                Document document = cursor.next();
                return new Row(collection, new LinkedHashMap<>(document));
            }
        };
    }

    @Override
    public void close() {
        cursor.close();
    }
}
