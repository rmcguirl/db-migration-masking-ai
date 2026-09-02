package com.migration.connector.mongodb;

import com.migration.connector.api.ConnectorFor;
import com.migration.connector.api.DestinationConnector;
import com.migration.connector.api.TypeMapper;
import com.migration.connector.api.UpsertResult;
import com.migration.connector.jdbc.TypeMappingMatrix;
import com.migration.domain.DbType;
import com.migration.domain.Row;
import com.migration.domain.SchemaModel;
import com.migration.domain.TableRef;
import com.migration.domain.exception.ConnectorException;
import com.mongodb.MongoException;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOneModel;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.WriteModel;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * MongoDB as a migration destination. Deliberately implements only
 * {@link DestinationConnector} — no {@code DdlCapable} (collections are created on first
 * write and fields are additive by nature, so the additive-only safety property is
 * trivially satisfied — design doc §1), no {@code TransactionalBatchWrite} (a
 * {@code DEFERRED_TRANSACTION} FK-cycle strategy is meaningless here since Mongo has no
 * FK constraints to violate at all — any cycle touching a Mongo destination must resolve
 * via {@code DECLARED_ORDER}).
 */
@Component
@ConnectorFor("mongodb")
@ConditionalOnProperty(name = "migration.destination.type", havingValue = "mongodb")
public class MongoDestinationConnector implements DestinationConnector {

    private final MongoDatabase database;
    private final TypeMapper typeMapper;
    private final MongoSchemaIntrospector introspector = new MongoSchemaIntrospector();

    public MongoDestinationConnector(@Qualifier("destinationMongoDatabase") MongoDatabase database) {
        this.database = database;
        this.typeMapper = TypeMappingMatrix.loadFromClasspath("mongodb-type-mapping.yml");
    }

    @Override
    public DbType type() {
        return DbType.of("mongodb");
    }

    @Override
    public void testConnection() {
        try {
            database.runCommand(new Document("ping", 1));
        } catch (MongoException e) {
            throw new ConnectorException("failed to connect to mongodb destination", e);
        }
    }

    @Override
    public TypeMapper typeMapper() {
        return typeMapper;
    }

    @Override
    public SchemaModel introspectSchema() {
        try {
            return introspector.introspectSchema(database, typeMapper);
        } catch (MongoException e) {
            throw new ConnectorException("failed to introspect mongodb destination schema", e);
        }
    }

    @Override
    public UpsertResult upsert(TableRef table, List<String> naturalKeyColumns, List<Row> rows) {
        if (rows.isEmpty()) {
            return UpsertResult.zero();
        }
        try {
            MongoCollection<Document> collection = database.getCollection(table.table());
            List<WriteModel<Document>> models = new ArrayList<>(rows.size());
            for (Row row : rows) {
                Bson filter = naturalKeyColumns.size() == 1
                        ? Filters.eq(naturalKeyColumns.get(0), row.get(naturalKeyColumns.get(0)))
                        : Filters.and(naturalKeyColumns.stream().map(c -> Filters.eq(c, row.get(c))).toList());
                Document replacement = new Document(row.values());
                models.add(new ReplaceOneModel<>(filter, replacement, new ReplaceOptions().upsert(true)));
            }
            BulkWriteResult result = collection.bulkWrite(models);
            long inserted = result.getUpserts().size();
            long updated = result.getModifiedCount();
            return new UpsertResult(inserted, updated);
        } catch (MongoException e) {
            throw new ConnectorException("failed to upsert into mongodb collection " + table.qualifiedName(), e);
        }
    }
}
