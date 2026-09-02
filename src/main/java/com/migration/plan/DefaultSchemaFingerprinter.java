package com.migration.plan;

import com.migration.domain.ColumnDescriptor;
import com.migration.domain.ForeignKeyRef;
import com.migration.domain.SchemaModel;
import com.migration.domain.TableDescriptor;
import com.migration.domain.exception.MigrationException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * Hashes a canonical (sort-order-independent) textual serialization of a
 * {@link SchemaModel}'s tables, columns, and FK edges — never their introspection
 * order — so the fingerprint only changes when the schema's actual shape does (design
 * doc §2). Used to key the plan cache: a re-run against an unchanged source schema
 * reuses the cached plan instead of re-invoking AI analysis (design doc §6).
 */
@Component
public class DefaultSchemaFingerprinter implements SchemaFingerprinter {

    @Override
    public String fingerprint(SchemaModel model) {
        StringBuilder canonical = new StringBuilder();

        List<TableDescriptor> tables = model.tables().stream()
                .sorted(Comparator.comparing(t -> t.ref().qualifiedName()))
                .toList();
        for (TableDescriptor table : tables) {
            canonical.append("TABLE:").append(table.ref().qualifiedName()).append('\n');
            List<ColumnDescriptor> columns = table.columns().stream()
                    .sorted(Comparator.comparing(ColumnDescriptor::name))
                    .toList();
            for (ColumnDescriptor column : columns) {
                canonical.append("  COL:").append(column.name())
                        .append(':').append(column.canonicalType().kind())
                        .append(':').append(column.nullable())
                        .append(':').append(column.primaryKey())
                        .append('\n');
            }
            canonical.append("  PK:").append(String.join(",", table.primaryKeyColumns())).append('\n');
        }

        List<ForeignKeyRef> foreignKeys = model.foreignKeys().stream()
                .sorted(Comparator.<ForeignKeyRef, String>comparing(fk -> fk.childTable().qualifiedName())
                        .thenComparing(fk -> fk.parentTable().qualifiedName()))
                .toList();
        for (ForeignKeyRef fk : foreignKeys) {
            canonical.append("FK:").append(fk.childTable().qualifiedName()).append(fk.childColumns())
                    .append("->").append(fk.parentTable().qualifiedName()).append(fk.parentColumns())
                    .append('\n');
        }

        byte[] digest = sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
        return "sha256:" + HexFormat.of().formatHex(digest);
    }

    private byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new MigrationException("SHA-256 not available", e);
        }
    }
}
