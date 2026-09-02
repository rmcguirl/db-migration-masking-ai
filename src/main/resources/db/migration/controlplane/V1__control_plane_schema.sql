-- Control-plane schema (design doc §2, §9b, §10): plan cache and audit log.
-- Targets the default embedded H2 datastore. Spring Batch's own JobRepository tables
-- (BATCH_JOB_INSTANCE, BATCH_JOB_EXECUTION, ...) are NOT created here — they're
-- initialized separately by Spring Boot's built-in batch schema initializer against this
-- same datastore (see batch.BatchInfrastructureConfig), reusing Spring Batch's own
-- official schema scripts rather than hand-copying them into this migration.

CREATE TABLE plan_cache (
    schema_fingerprint VARCHAR(128) PRIMARY KEY,
    generated_at       TIMESTAMP NOT NULL,
    plan_json          TEXT NOT NULL
);

CREATE TABLE audit_run (
    run_id              VARCHAR(64) PRIMARY KEY,
    schema_fingerprint  VARCHAR(128) NOT NULL,
    mode                VARCHAR(16) NOT NULL,
    started_at          TIMESTAMP NOT NULL,
    ended_at            TIMESTAMP,
    outcome             VARCHAR(32)
);

CREATE TABLE audit_table_result (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id              VARCHAR(64) NOT NULL,
    source_table        VARCHAR(256) NOT NULL,
    destination_table   VARCHAR(256) NOT NULL,
    outcome             VARCHAR(32) NOT NULL,
    rows_inserted       BIGINT NOT NULL DEFAULT 0,
    rows_updated        BIGINT NOT NULL DEFAULT 0,
    ddl_actions         TEXT,
    error_message       TEXT,
    CONSTRAINT fk_audit_table_result_run FOREIGN KEY (run_id) REFERENCES audit_run (run_id)
);

CREATE INDEX idx_audit_table_result_run ON audit_table_result (run_id);

CREATE TABLE audit_column_masking (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id              VARCHAR(64) NOT NULL,
    table_name          VARCHAR(256) NOT NULL,
    column_name         VARCHAR(256) NOT NULL,
    technique           VARCHAR(64),
    regulatory_basis    VARCHAR(128),
    decision_source     VARCHAR(32) NOT NULL,
    confidence          DOUBLE,
    CONSTRAINT fk_audit_column_masking_run FOREIGN KEY (run_id) REFERENCES audit_run (run_id)
);

CREATE INDEX idx_audit_column_masking_run ON audit_column_masking (run_id);
-- Supports "show every column masked for PCI-DSS reasons across the last run" (§10) via
-- a LIKE query against regulatory_basis (a comma-joined tag list, e.g. "GDPR,PCI_DSS").
CREATE INDEX idx_audit_column_masking_basis ON audit_column_masking (regulatory_basis);

CREATE TABLE audit_ddl_conflict (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id              VARCHAR(64) NOT NULL,
    table_name          VARCHAR(256) NOT NULL,
    column_name         VARCHAR(256) NOT NULL,
    expected_type       VARCHAR(128),
    actual_type         VARCHAR(128),
    reason              TEXT,
    CONSTRAINT fk_audit_ddl_conflict_run FOREIGN KEY (run_id) REFERENCES audit_run (run_id)
);

CREATE INDEX idx_audit_ddl_conflict_run ON audit_ddl_conflict (run_id);
