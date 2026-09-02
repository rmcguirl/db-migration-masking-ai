package com.migration.connector.api;

/** Row counts from one {@code upsert()} call, for progress tracking and the audit log. */
public record UpsertResult(long inserted, long updated) {

    public static UpsertResult zero() {
        return new UpsertResult(0, 0);
    }

    public long total() {
        return inserted + updated;
    }

    public UpsertResult plus(UpsertResult other) {
        return new UpsertResult(inserted + other.inserted, updated + other.updated);
    }
}
