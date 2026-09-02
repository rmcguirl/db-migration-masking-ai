package com.migration.connector.mongodb;

import com.migration.connector.api.ConnectorFor;
import com.migration.connector.api.RowStream;
import com.migration.connector.api.SourceConnector;
import com.migration.connector.api.TypeMapper;
import com.migration.connector.jdbc.TypeMappingMatrix;
import com.migration.domain.DbType;
import com.migration.domain.ExtractCursor;
import com.migration.domain.SchemaModel;
import com.migration.domain.TableRef;
import com.migration.domain.exception.ConnectorException;
import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * MongoDB as a migration source. Schema introspection is sampling-based inference, not
 * authoritative fact (design doc's non-relational-support flag, §3) — see
 * {@link MongoSchemaIntrospector}. {@code _id} is always treated as the primary/natural
 * key, since every valid Mongo document has one.
 *
 * <p>Deliberately implements only {@link SourceConnector} — no
 * {@code ReferentialMetadataCapable} (Mongo has no FK constraints to introspect; only
 * config-declared virtual FKs apply here, design doc §3, §5).
 */
@Component
@ConnectorFor("mongodb")
@ConditionalOnProperty(name = "migration.source.type", havingValue = "mongodb")
public class MongoSourceConnector implements SourceConnector {

    private final MongoDatabase database;
    private final TypeMapper typeMapper;
    private final MongoSchemaIntrospector introspector = new MongoSchemaIntrospector();

    public MongoSourceConnector(@Qualifier("sourceMongoDatabase") MongoDatabase database) {
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
            throw new ConnectorException("failed to connect to mongodb source", e);
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
            throw new ConnectorException("failed to introspect mongodb source schema", e);
        }
    }

    @Override
    public RowStream extract(TableRef table, ExtractCursor cursor, int batchSize) {
        try {
            MongoCollection<Document> collection = database.getCollection(table.table());
            Document filter = cursor.isStart()
                    ? new Document()
                    : new Document("_id", new Document("$gt", cursor.lastKeyValues().get("_id")));
            MongoCursor<Document> mongoCursor = collection.find(filter)
                    .sort(new Document("_id", 1))
                    .limit(batchSize)
                    .iterator();
            return new MongoRowStream(mongoCursor, table);
        } catch (MongoException e) {
            throw new ConnectorException("failed to extract from mongodb collection " + table.qualifiedName(), e);
        }
    }
}
