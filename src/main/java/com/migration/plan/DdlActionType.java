package com.migration.plan;

/** The three additive-only DDL actions this app is ever allowed to emit (design doc §3, §5). */
public enum DdlActionType {
    CREATE_SCHEMA,
    CREATE_TABLE,
    ADD_COLUMN
}
