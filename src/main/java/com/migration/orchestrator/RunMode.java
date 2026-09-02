package com.migration.orchestrator;

/** The two run modes (design doc §7, §11): a full load, or stop after plan/DDL-diff generation for review. */
public enum RunMode {
    FULL,
    PLAN_ONLY
}
