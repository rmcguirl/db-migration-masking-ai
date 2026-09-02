package com.migration.connector.mongodb;

import com.migration.connector.api.TypeMapper;
import com.migration.domain.CanonicalType;
import com.migration.domain.ColumnDescriptor;
import com.migration.domain.NativeTypeDescriptor;
import com.migration.domain.SchemaModel;
import com.migration.domain.TableDescriptor;
import com.migration.domain.TableRef;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sampling-based schema inference shared by {@link MongoSourceConnector} and
 * {@link MongoDestinationConnector}: infers each collection's field shape by sampling up
 * to {@link #SAMPLE_SIZE} documents, majority-voting the BSON type per field, and marking
 * a field nullable unless present on every sampled document (design doc §3).
 */
final class MongoSchemaIntrospector {

    private static final int SAMPLE_SIZE = 1000;

    SchemaModel introspectSchema(MongoDatabase database, TypeMapper typeMapper) {
        List<TableDescriptor> tables = new ArrayList<>();
        for (String collectionName : database.listCollectionNames()) {
            tables.add(sampleCollection(database, collectionName, typeMapper));
        }
        return SchemaModel.of(tables);
    }

    TableDescriptor sampleCollection(MongoDatabase database, String collectionName, TypeMapper typeMapper) {
        MongoCollection<Document> collection = database.getCollection(collectionName);
        Map<String, Map<String, Integer>> typeCountsByField = new LinkedHashMap<>();
        Map<String, Integer> presenceCountByField = new LinkedHashMap<>();
        int sampled = 0;

        try (MongoCursor<Document> cursor = collection.find().limit(SAMPLE_SIZE).iterator()) {
            while (cursor.hasNext()) {
                Document document = cursor.next();
                sampled++;
                for (String field : document.keySet()) {
                    presenceCountByField.merge(field, 1, Integer::sum);
                    String bsonType = BsonTypeNames.of(document.get(field));
                    typeCountsByField.computeIfAbsent(field, f -> new LinkedHashMap<>()).merge(bsonType, 1, Integer::sum);
                }
            }
        }

        int finalSampled = sampled;
        List<ColumnDescriptor> columns = new ArrayList<>();
        for (Map.Entry<String, Map<String, Integer>> entry : typeCountsByField.entrySet()) {
            String field = entry.getKey();
            String majorityBsonType = entry.getValue().entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse("string");
            NativeTypeDescriptor nativeType = NativeTypeDescriptor.of(majorityBsonType);
            CanonicalType canonicalType = typeMapper.toCanonical(nativeType);
            boolean nullable = presenceCountByField.getOrDefault(field, 0) < finalSampled;
            boolean primaryKey = field.equals("_id");
            columns.add(new ColumnDescriptor(field, canonicalType, nativeType, nullable, primaryKey, null));
        }

        List<String> primaryKeyColumns = columns.stream().anyMatch(c -> c.name().equals("_id"))
                ? List.of("_id")
                : List.of();
        return new TableDescriptor(TableRef.of(collectionName), columns, primaryKeyColumns, null);
    }
}
