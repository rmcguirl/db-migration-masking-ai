# Design: Universal Data Migration & Masking Tool

## 1. Assumptions

**Assumptions made to resolve ambiguity:**

1. "Destination schema" for a non-relational engine (e.g., MongoDB) means "the set of collections and the field shapes the app expects to write" — there is no DDL in the SQL sense. The additive-only constraint (req. 9) is honored trivially there: collections are created on first write, fields are additive by nature, and there is nothing to "flag as different" except type-shape drift, which is logged but not enforced.
2. "Small data samples" for AI analysis (AI Usage section) means the app sends **pattern-match signal summaries**, not raw values, to the AI provider by default (see §6). Sending raw sensitive values to an external LLM is itself a data-processing event that would need its own legal basis under GDPR/HIPAA/PCI-DSS, which conflicts with the spirit of a masking tool. Raw-sample sending is supported only as an explicit opt-in (`ai.allow-data-sampling: true`) for use with a private/enterprise AI endpoint under the org's own DPA. **Resolved:** the design supports both public-API and VPC-hosted/private deployments as pluggable `AiPlanAnalyzer` implementations (see §6), selected via `ai.provider` — the same extensibility pattern as connectors (§3). The opt-in raw-sample flag is honored identically regardless of which implementation is active; it never changes the redaction default.
3. The masking HMAC key is a single, versioned secret per environment, not per-table/per-column. Key rotation is out of scope for this design (see below) but the config format reserves a `keyVersion` field so it can be added later without a schema break.
4. "Periodically re-run" (req. 7) means re-invocations are manually or externally scheduled (e.g., cron, CI job) — the app itself has no built-in scheduler, consistent with the "batch, not CDC" framing.
5. The control plane (job state, plan cache, audit log — see §2) needs its own durable, always-relational store, independent of source/destination types, because the destination may be non-relational (Mongo) or read-only-accessible, and the plan cache must survive between a `--plan-only` run and the later load run, possibly on different machines/CI runners. I assume an embedded, file-backed relational database (H2, upgradeable to a real Postgres instance via config) is acceptable as this control store, keeping the app a single deployable with zero required external infrastructure by default.
6. "Natural key" (req. 7) is assumed to be a stable business identifier that exists in the source data (e.g., `external_id`, `email`, a legacy system's ID) and is either declared by the user or suggested by AI — it is distinct from the source's technical primary key, though it may coincide with it.
7. No numeric performance/scale target was given, so §10 proposes design-time guidelines derived from numbers already implied elsewhere in this doc (default `batchSize`/`parallelism`/pool sizes in §9a, the table-count scaling discussion in §6) rather than an arbitrary figure. These are defaults to tune per deployment, not a contractual SLA — flag if the actual target (rows/hour, max schema size, acceptable `--plan-only` latency) is known and materially different.

**Flag: non-relational support changes the architecture.** This holds true in three specific places:
- **Schema introspection becomes inference, not fact.** A relational connector reads authoritative metadata (`information_schema`, `DatabaseMetaData`). A document-store connector must *infer* a schema by sampling documents and unioning observed field shapes — this is probabilistic and can miss rarely-populated fields. Both share the same `introspectSchema()` contract (declared on `SourceConnector`/`DestinationConnector` directly, §3) but differ entirely in implementation — one reads authoritative catalog metadata, the other runs a sampling/inference pass — so callers never need to know which strategy backs a given connector.
- **DDL/reconciliation becomes near-vacuous.** There is no `ALTER`/`CREATE TABLE` equivalent to guard for MongoDB; the additive-only safety property (req. 9) is trivially satisfied because there's nothing to overwrite. The `DdlCapable` capability interface is simply not implemented by document-store connectors, and the orchestrator skips that step for them.
- **Referential integrity becomes app-level only.** Document stores generally don't enforce FKs. The design still computes and applies deterministic masking consistently across "logical" parent/child relationships (config-declared, since there's no FK metadata to introspect — see §5), but there is no destination-side constraint to violate, so load-order sequencing is a best-practice, not a correctness requirement, for a Mongo destination.

**Resolved decisions:**
- **AI provider/deployment:** pluggable. `AiPlanAnalyzer` is a capability-style interface (§6) with a provider registry (`@AiProviderFor`, mirroring `@ConnectorFor` in §3); `ai.provider` in config selects the active implementation. v1 ships two reference implementations — a public hosted-API client and a VPC/private-endpoint client. Adding a provider later is a new `@Component`, no core-code change.
- **Control-plane store:** confirmed as originally designed — embedded H2 by default, swappable to managed Postgres via `controlPlane.datastore` config, same code path either way.
- **Confidence threshold default:** raised from 0.7 to **0.85** (more conservative — fewer AI-classified columns escape a fallback technique). Stays a per-deployment config knob (`ai.confidenceThreshold`).
- **Cyclic FK dependencies:** The deferred-constraint transaction path (§5) is implemented, gated by connector-reported `supportsDeferredConstraints()`; where that's unavailable, a human-declared `referentialIntegrity.cycles[].strategy: DECLARED_ORDER` config entry is required (see §9a), or the cycle is blocked as `UNSUPPORTED` by default.

## 2. High-Level Architecture

Single Spring Boot deployable. Components below are Spring-managed beans/modules; arrows describe data flow for one run.

| Component | Responsibility | Inputs | Outputs |
|---|---|---|---|
| **MigrationRunner** (`CommandLineRunner`) | Entry point; parses CLI args (`--plan-only`), loads `MigrationConfig`, invokes orchestrator | CLI args, `application.yml` / external config file | Process exit code |
| **MigrationOrchestrator** | Drives the end-to-end sequence for both run modes; owns top-level error handling | `MigrationConfig` | Run result/report |
| **ConnectorRegistry** | Resolves the configured `type:` string to a concrete `SourceConnector`/`DestinationConnector` bean | `MigrationConfig.source.type`, `.destination.type` | Connector instances |
| **SchemaIntrospectionService**¹ | Calls the source/destination connectors' introspection capability, normalizes to `SchemaModel` | Connector | `SchemaModel` (source), `SchemaModel` (existing destination) |
| **SchemaFingerprinter** | Computes a stable hash over a `SchemaModel` to key the plan cache | `SchemaModel` (source) | fingerprint string |
| **PlanCacheRepository** | Reads/writes cached `MigrationPlan`s in the control-plane store, keyed by fingerprint | fingerprint, `MigrationPlan` | `Optional<MigrationPlan>` |
| **PatternRuleEngine** | Deterministic regex/heuristic PII/PCI/PHI pattern detection over column names + sample values | source sample data (local only, never leaves process for this step) | `PatternSignal` per column |
| **AiPlanAnalyzer** | Calls the AI provider with schema metadata + pattern signals (not raw values by default) to classify sensitivity, pick masking technique, suggest natural keys/field mappings | `SchemaModel`, `PatternSignal`s | `AiAnalysisResult` |
| **PlanGenerationService** | Orchestrates: check cache → else run `PatternRuleEngine` + `AiPlanAnalyzer` → merge with user overrides → apply confidence fallback policy → produce and cache `MigrationPlan` | `SchemaModel`, `MigrationConfig.overrides`, cached plan (if any) | `MigrationPlan` |
| **ReferentialGraphBuilder** | Builds FK dependency graph (from introspected FKs + config-declared virtual FKs), topologically sorts for load order, detects cycles | `SchemaModel`, `MigrationConfig` virtual-FK overrides | `DependencyGraph` (load order, cycle report) |
| **DdlReconciler** | Diffs desired schema (derived from `MigrationPlan` + type mapping) against existing destination schema; produces additive-only DDL actions; flags conflicts | `MigrationPlan`, destination `SchemaModel` | `DdlPlan` (creates/adds/conflicts) |
| **MaskingEngine** | Applies the configured one-way technique per column to each extracted row | `Row`, `TablePlan` (from `MigrationPlan`), masking key | Masked `Row` |
| **MaskingKeyProvider** | Resolves the HMAC secret from its configured source (env var, secrets manager) | `MigrationConfig.maskingKey` | key bytes (never logged/persisted) |
| **NaturalKeyUpsertWriter** | Builds and executes the engine-specific upsert/merge for a batch of masked rows, keyed by the table's natural key | masked rows, `TablePlan.naturalKey`, `DestinationConnector` | `UpsertResult` (counts) |
| **MigrationJobService** (Spring Batch) | Runs one `Step` per table in dependency order, chunk-oriented read→mask→write, tracks restart state | `DependencyGraph`, `MigrationPlan`, connectors | Job execution status, per-step counts |
| **AuditLogService** | Appends structured audit records (what was masked, DDL applied, row counts, errors) to the control-plane store | events from all of the above | Audit trail (queryable) |
| **PlanReportWriter** | For `--plan-only`, renders the `MigrationPlan` to a human-readable + machine-readable report | `MigrationPlan` | file(s) / stdout |

¹ Listed as a distinct responsibility for clarity, but it's a thin wrapper (two method calls: `source.introspectSchema()`, `destination.introspectSchema()`) with no state or interesting logic of its own — it's implemented as private helper methods on `MigrationOrchestrator` (§11) rather than as a separate Spring bean/interface.

**Data flow, default run:** `MigrationRunner` → `MigrationOrchestrator` → introspect source → fingerprint → `PlanGenerationService` (cache hit, or pattern+AI analysis) → `ReferentialGraphBuilder` → `DdlReconciler` (apply additive DDL) → `MigrationJobService` runs one Spring Batch `Step` per table in dependency order, each step: extract chunk → `MaskingEngine` → `NaturalKeyUpsertWriter` → commit chunk → `AuditLogService` records the run.

**Data flow, `--plan-only`:** identical up through `PlanGenerationService` and `DdlReconciler`'s *dry-run diff* (no DDL applied), then `PlanReportWriter` emits the plan for review and the process exits — no `MigrationJobService` invocation.

## 3. Connector Abstraction

The core problem this section solves: relational engines, and document stores, support different subsets of "schema introspection," "DDL," and "transactional write." Rather than one fat interface every connector must fully implement (with no-ops for what doesn't apply), the design splits the contract into a required minimal interface plus optional capability interfaces the orchestrator checks for at runtime. (Exact signatures are the single canonical copy in §11's Connectors block — summarized here by name only, to avoid two copies drifting out of sync.)

- `Connector` — base contract every connector implements: `type()`, `testConnection()`, `typeMapper()`.
- `SourceConnector extends Connector` — adds `introspectSchema()`, `extract(...)`.
- `DestinationConnector extends Connector` — adds `introspectSchema()`, `upsert(...)`.
- `DdlCapable` *(optional capability)* — `diffSchema(...)`, `applyDdl(...)`; implemented only where the engine supports DDL.
- `TransactionalBatchWrite` *(optional capability)* — `inTransaction(...)`, `supportsDeferredConstraints()` (the latter drives the FK-cycle handling in §5).
- `ReferentialMetadataCapable` *(optional capability)* — `introspectForeignKeys()`.

- A JDBC-backed relational connector (Postgres, MySQL, Oracle, SQL Server) implements `SourceConnector`, `DestinationConnector`, `DdlCapable`, `TransactionalBatchWrite`, and `ReferentialMetadataCapable`, using `DatabaseMetaData`/`information_schema` for introspection and dialect-specific SQL (via a small per-dialect `SqlDialect` strategy for DDL/upsert syntax — e.g., `INSERT ... ON CONFLICT` for Postgres/SQLite, `MERGE` for Oracle/SQL Server, `INSERT ... ON DUPLICATE KEY UPDATE` for MySQL) for upsert generation.
- A document-store connector (MongoDB) implements `SourceConnector` and `DestinationConnector` only. `introspectSchema()` is sampling-based (configurable sample size, default e.g. 1,000 docs/collection) and infers a `SchemaModel` with a per-field observed-type union. It does **not** implement `DdlCapable` (nothing to reconcile) or `ReferentialMetadataCapable` (no FK constraints exist); the orchestrator checks `instanceof DdlCapable` / `ReferentialMetadataCapable` and skips those steps cleanly rather than calling a no-op.
- `TypeMapper` is the per-connector type-mapping hook (detailed in §8/type table): `CanonicalType toCanonical(NativeTypeDescriptor t)` and `NativeTypeDescriptor fromCanonical(CanonicalType t)`.

**Adding a new database type requires no core-code changes:**
1. Implement `SourceConnector`/`DestinationConnector` (+ applicable capability interfaces) as a Spring `@Component`, annotated e.g. `@ConnectorFor("db2")`.
2. Provide a `TypeMapper` implementation with the native↔canonical mapping table for that engine (a config-driven table, not conditional code — see §8).
3. `ConnectorRegistry` auto-discovers all `Connector` beans via `List<Connector> candidates` injection and indexes them by their `@ConnectorFor` value; `MigrationConfig.source.type: "db2"` resolves to it at startup. No `if/else` or `switch` on database type exists anywhere in orchestration code — every dispatch point either uses the registry or checks a capability interface.

DDL generation contract (`DdlCapable.diffSchema`): compares the desired `SchemaModel` (derived from source schema + `TypeMapper` + `MigrationPlan` field mappings) against the *actually introspected* existing destination schema, and classifies each table/column as:
- **Missing** → `CREATE_TABLE` / `ADD_COLUMN` action (emitted).
- **Present, canonical-type-compatible** (identical canonical type, or a same-family widening the type matrix marks safe, e.g. `INTEGER → LONG`) → no action.
- **Present, but different** (narrower, incompatible, or unrecognized native type) → **conflict**, recorded in the `DdlPlan.conflicts` list with the expected vs. actual type; **never** auto-altered. A conflicted column is excluded from load for that run (the row's other columns still load); if the conflicted column is part of the table's natural key or a PK/FK chain, the *entire table* is skipped for that run and the conflict is surfaced as a run-blocking warning, since correctness can't be guaranteed. A table skipped this way is treated identically to a table whose load failed outright (§7): every table in its `DependencyGraph.transitiveDependents(...)` set is also skipped for the run (their children could otherwise violate a destination FK constraint against a parent row that was never written), while tables outside that dependent set are unaffected and still load normally. All conflicts and their resulting skips are written to the audit log and, in `--plan-only` mode, to the human-readable plan report.

## 4. Masking Engine Design

All techniques are strictly one-way — no vault, no key-to-plaintext lookup table, ever.

| Technique | Mechanism | Regulatory basis | Residual risk / notes |
|---|---|---|---|
| `HASH_HMAC` | HMAC-SHA-256(secret key, canonicalized value), encoded (Base64URL or hex), optionally truncated | Satisfies PCI-DSS 3.4 ("render PAN unreadable... one-way hashes... of the entire PAN"). Under GDPR this is **pseudonymization**, not anonymization — the mapping is consistent and re-identifiable if an attacker can guess/enumerate candidate inputs, so hashed data remains "personal data" in GDPR's scope unless combined with generalization (below) | **Brute-forceable for low-entropy inputs** even without the key being compromised is *not* the risk (the key is secret); the real risk is a *known-key or guessed-plaintext* dictionary attack against a low-cardinality domain (e.g., a 9-digit SSN space, or DOB) is only as hard as the keyspace of the *input*, not the hash — an attacker with the key and a candidate list can confirm matches. Mitigate by combining with generalization/truncation on low-entropy fields (e.g., hash *and* bucket DOB to year) rather than hashing alone. |
| `REDACT_FULL` / `REDACT_PARTIAL` | Replace with fixed token (`REDACTED`) or partial mask keeping a fixed number of characters (e.g., last 4 of PAN) | PCI-DSS 3.4 explicitly permits truncation (max first 6 / last 4 digits) as an acceptable rendering-unreadable method; HIPAA Safe Harbor identifier removal | If both a truncated fragment *and* an HMAC of the full value are stored for the same record, the fragment narrows the brute-force search space for the hash — avoid emitting both for the same sensitive field unless justified. |
| `GENERALIZE_BUCKET` | Reduce precision: DOB → birth year, ZIP → first 3 digits, exact age → 5-year band | Directly implements HIPAA Safe Harbor's specific rules (dates limited to year; geographic subdivision to first 3 digits of ZIP when population ≥ 20,000; ages ≥ 90 aggregated). Also a GDPR anonymization technique when the resulting equivalence class is large enough to prevent singling-out | Bucket size must be chosen so re-identification via the bucket + other quasi-identifiers stays unlikely (k-anonymity judgment call) — flagged as a per-column tuning decision, not fully automatable. |
| `SYNTHETIC_SUBSTITUTION` | Deterministically seed a synthetic-value generator (e.g., a fake-name/fake-email corpus) from `HASH_HMAC(value)` so the same input always yields the same synthetic output, with no path back to the original | GDPR anonymization when the corpus has no linkage back to the source; useful where downstream systems need realistic-looking but non-reversible test data | Corpus must be independent of any real-value dictionary that could be inverted by frequency-matching; treat as best used for display-only fields (names, emails), not identifiers used for joins. |

**Determinism for FK-referenced columns:** because a masked FK must equal its masked parent PK, any column that participates in a primary key, a foreign key, or a declared natural key is **restricted to `HASH_HMAC`** (or left unmasked, if not sensitive) — never `GENERALIZE_BUCKET` or `SYNTHETIC_SUBSTITUTION`. Bucketing/substitution are not injective; applying them to a key column could map two distinct source entities to the same destination key, silently merging rows on upsert. The `MaskingEngine` enforces this as a hard validation rule when building a `TablePlan`: if the plan (AI-suggested or user-overridden) assigns a non-injective technique to a key column, plan generation fails fast by throwing `PlanValidationException` (§11) rather than proceeding. Because the technique is a pure function of `(value, key)` — not of load order or which rows have already loaded — parent and child rows can be masked independently and in any order; §5 explains why this also decouples masking correctness from load ordering.

**Config schema (per-column masking rule, part of the generated `MigrationPlan`, §9):**

```yaml
masking:
  keySource: "env:MASKING_HMAC_KEY"
  keyVersion: 1
  rules:
    - table: customers
      column: ssn
      technique: HASH_HMAC
      encoding: BASE64URL
    - table: customers
      column: date_of_birth
      technique: GENERALIZE_BUCKET
      granularity: YEAR
    - table: customers
      column: email
      technique: SYNTHETIC_SUBSTITUTION
      corpus: email
    - table: payment_methods
      column: pan
      technique: REDACT_PARTIAL
      keepLast: 4
```

## 5. Referential Integrity Strategy

**Graph construction:** `ReferentialGraphBuilder` reads FKs from `ReferentialMetadataCapable.introspectForeignKeys()` where the connector supports it, merged with any `virtualForeignKeys` declared in config (for engines/tables with no enforced constraints — see below). Each edge is `(childTable, childColumns[], parentTable, parentColumns[])`, supporting composite keys as a column list rather than a single column.

**Load ordering:** Kahn's-algorithm topological sort over the table graph produces `DependencyGraph.loadOrder()` — a linear sequence in which every parent table precedes its children. Tables with no interdependency form "layers" that can load in parallel (used by §10's parallelism strategy). `DependencyGraph` also exposes `transitiveDependents(TableRef)` — every table reachable by following child edges from a given table — which §7 uses to decide what to skip when a table's load fails or is blocked by a DDL conflict, without aborting unrelated branches of the graph.

**Why deterministic hashing decouples correctness from ordering (confirming req. 6's premise):** `HASH_HMAC` is a pure function of the source value and the shared secret key — it does not depend on whether the parent row has already been written. So `HASH_HMAC(parent.pk)` computed while processing the parent table and `HASH_HMAC(child.fk)` computed while processing the child table always agree, regardless of which table is processed first. This means load order is required **only** to satisfy the *destination's* FK constraint enforcement at insert time (so a relational destination doesn't reject a child row whose parent isn't there yet) — it is not required for the masked values themselves to match. For a destination with no FK enforcement (e.g., MongoDB), load order is therefore a best practice for readability/debuggability, not a correctness requirement.

**Composite keys:** each column of a composite key is hashed independently with the same technique and key; equality is preserved column-by-column, so no special concatenation logic is needed — a composite parent key `(tenant_id, customer_id)` and a composite child FK `(tenant_id, customer_id)` each hash to the same pair of values.

**Self-referencing FKs** (e.g., `employee.manager_id → employee.id`): handled without special-casing, precisely because of the ordering-independence property above — the masked `manager_id` is computed the same way regardless of whether that employee's manager row has been written yet in the same table's own chunked load.

**Cross-schema (and cross-catalog) FKs:** a `TableRef` carries an optional `schema`/`catalog` qualifier alongside its table name, so a graph node is really `(catalog, schema, table)` — an FK from `sales.orders.customer_id` to `crm.customers.id` is just an edge between two differently-qualified `TableRef`s and requires no special handling in `ReferentialGraphBuilder`, topological sort, or masking (the hashing argument above is qualifier-agnostic). Two things *do* need to account for the qualifier: (a) `DdlReconciler` must create the target schema itself (still additive-only — `CREATE SCHEMA IF NOT EXISTS`) before creating tables inside it, and (b) some engines don't support a true FK *constraint* across catalogs/databases (only within one database across schemas, e.g., Postgres) — where the source or destination engine reports that limitation, the edge is still tracked for load-ordering and masking-consistency purposes, but no FK constraint is emitted at the destination for that edge (the `DdlPlan` simply omits it), which is safe under requirement 10's best-effort model since the data itself is still made consistent even without the constraint enforcing it.

**Cycles between tables (in scope for v1):** true bidirectional table-level cycles (rare, but possible with deferred FKs) cannot be linearized by topological sort. `DependencyGraph.detectCycles()` identifies strongly-connected components of size > 1 and reports them. The orchestrator never attempts *automatic* resolution (e.g., temporarily nulling a column and back-filling) since that risks a partial/inconsistent state under the best-effort per-table consistency model — every cycle must resolve via one of two explicit, config-declared strategies (`referentialIntegrity.cycles[]`, §9a):
- **`DEFERRED_TRANSACTION`** — requires every connector involved in the cycle to be `TransactionalBatchWrite`-capable and report `supportsDeferredConstraints() == true`; the cyclic tables are then loaded together under a single deferred-constraint transaction. Plan generation fails fast by throwing `PlanValidationException` (§11) if this strategy is declared for a cycle whose connectors don't support it.
- **`DECLARED_ORDER`** — a human-declared linear load order among the cyclic tables (config `loadOrder`); the FK edge(s) that would violate that order are simply omitted from `DdlPlan`'s constraint emission for that cycle (never enforced at the destination), the same technique already used for the cross-catalog no-constraint case above. Data itself stays consistent (masking is order-independent, per the hashing argument above); only destination-side enforcement of that one edge is dropped.

A detected cycle with no matching `referentialIntegrity.cycles[]` entry defaults to **`UNSUPPORTED`**: flagged in the plan report and audit log, run-blocking for the tables involved, never silently handled.

**Missing/unreliable FK metadata:** common with MyISAM tables, logical-only relationships never declared as constraints, or any non-relational source. Config supports declaring **virtual foreign keys** explicitly:

```yaml
virtualForeignKeys:
  - childTable: orders
    childColumns: [customer_ref]
    parentTable: customers
    parentColumns: [external_id]
```

These participate in graph construction identically to introspected FKs. If neither introspected nor virtual FK information exists for a table, it is simply treated as having no dependencies (loads in the first available layer); its columns are still masked deterministically, so any relationship it happens to have with another table remains consistent in the masked output even if the graph doesn't know about it — the graph only affects *ordering*, not *hash agreement*.

## 6. AI Integration Points

**Provider abstraction:** `AiPlanAnalyzer` is implemented per AI provider/deployment, following the same extensibility pattern as connectors (§3): each implementation is a Spring `@Component` annotated `@AiProviderFor("<provider-id>")`, and an `AiProviderRegistry` resolves `ai.provider` from config to the matching bean at startup — no `if/else` on provider anywhere in `PlanGenerationService`. v1 ships two reference implementations sharing a common prompt-construction/HTTP base class: a public hosted-API client and a VPC/private-endpoint client. Adding a new provider later is a new `@Component` + its credentials config, not a core-code change. The `ai.allowDataSampling` opt-in and the redaction-by-default policy below are enforced identically by every implementation — the provider abstraction only changes *where* the payload is sent, never what's in it.

**Exactly where AI runs:** inside `PlanGenerationService`, invoked only when `PlanCacheRepository.findByFingerprint(schemaFingerprint)` misses — i.e., the first run against a given source schema, or the first run after that schema's fingerprint changes. This is true for both the default run and a `--plan-only` run; a `--plan-only` run with a cache hit goes straight to `PlanReportWriter` and never touches the AI client. Actual data extraction, masking, and loading (`MigrationJobService` and everything it calls) never invoke the AI client — that dependency is not even on the classpath of the batch-execution path, only of `PlanGenerationService`.

**What it's given:**
- **Schema metadata**, always: table/column names, native types (mapped to canonical types), nullability, declared constraints, any column/table comments — this is metadata, not data.
- **Pattern-signal summaries**, always: the local, non-AI `PatternRuleEngine` first scans a small sample of each column's *actual* values (configurable, default 20 rows) against a library of regex/format checks (SSN shape, email, credit-card with Luhn check, phone formats, national-ID formats, etc.) and produces a summary like `{table: customers, column: ssn, matchRate: 0.95, matchedPattern: "US_SSN"}` — **counts and pattern names, never the raw values themselves**, are what gets sent to the AI.
- **Raw data samples**, only if `ai.allowDataSampling: true` is explicitly set (default `false`) — an opt-in for organizations using a private/enterprise AI deployment under their own data agreement (flagged in §1 as needing a human decision on which AI deployment that implies).

**What it returns:** a structured `AiAnalysisResult` per table — for each column, a sensitivity classification with confidence score, **which regulatory framework(s) justify that classification** (one or more of `GDPR`/`HIPAA`/`PCI_DSS` — e.g., a PAN is tagged `PCI_DSS`, a medical record number `HIPAA`, a general email address `GDPR`), a suggested masking technique (constrained to the one-way palette, and to `HASH_HMAC` for anything the column-role detector flags as PK/FK/candidate-key), a suggested natural key for the table, and — when source and destination schemas differ — suggested field name mappings. The regulatory tag is carried straight through into the persisted `ColumnPlan` (§9b) rather than discarded after classification, specifically so the audit trail in §10 can answer "which columns were masked for PCI-DSS reasons" as a direct query instead of only "which columns were masked."

**Fallback/safety-net policy (no human gate, so this carries the real weight):**
- The `PatternRuleEngine` is a fully independent, deterministic signal — it runs regardless of AI availability and regardless of AI's opinion.
- Final sensitivity = **OR** of (AI says sensitive) and (pattern rule matched above a match-rate threshold). Rules can only push a column *into* "sensitive," never pull one *out* — the asymmetry is deliberate: a false negative (missing real PII) is the dangerous direction, a false positive (over-masking a non-sensitive column) is merely inconvenient.
- Any AI classification with confidence below the configured threshold (`ai.confidenceThreshold`, default `0.85` — deliberately conservative) is treated as "sensitive, technique unknown" and defaults to the most conservative available technique for that column's inferred type (`HASH_HMAC` for key-shaped/identifier-shaped columns, `REDACT_FULL` otherwise) rather than left unmasked.
- `PlanGenerationService` records the *source* of every decision (`ai`, `rule`, `ai+rule`, `override`, `low-confidence-fallback`) in the plan, so `--plan-only` review and the audit log always show why a column was or wasn't masked.

**Cost/scalability:** one AI call batches all columns of one table (schema + pattern signals for that table in a single prompt); for very large schemas, multiple small tables are batched into one call up to a token budget, so call count scales with **table count**, not row count or column count individually, and is entirely decoupled from migration frequency (cache hit ⇒ zero calls). Expected call volume: ~1 call per table at ≤100 tables; batched at ~15–20 tables/call for 100–1000+ table schemas, keeping total calls in the tens-to-low-hundreds even at the largest schema sizes; this is a one-time cost per schema version because of the fingerprint cache, not a per-run or per-row cost.

## 7. Data Flow for a Run

**Both modes, shared prefix:**
1. `MigrationRunner` loads config, resolves `--plan-only` flag.
2. `MigrationOrchestrator` resolves source/destination connectors via `ConnectorRegistry`.
3. Source `introspectSchema()` → `SchemaModel`; `SchemaFingerprinter` computes fingerprint.
4. `PlanCacheRepository.findByFingerprint(...)`:
   - **Hit** → use cached `MigrationPlan` unchanged, skip straight to step 5.
   - **Miss** → `PatternRuleEngine` scans samples → `AiPlanAnalyzer` called with metadata + pattern signals → `PlanGenerationService` merges AI result with `MigrationConfig.overrides` and confidence-fallback policy → validates key-column technique constraints (§4) → persists new plan keyed by fingerprint.
5. Destination `introspectSchema()` (via `DdlCapable`, where implemented) → `DdlReconciler.diffSchema()` produces a `DdlPlan` (creates, adds, conflicts) against the plan's derived desired schema.

**`--plan-only` stops here:** `DdlReconciler` runs in dry-run mode (diff computed, **not applied**), and `PlanReportWriter` emits the `MigrationPlan` plus the `DdlPlan` diff (proposed tables/columns, per-column masking assignment + confidence/source, natural keys, DDL actions and any conflicts) to a report file (and/or stdout) for human review. No connector write methods are called. Process exits 0. A later, separate invocation without `--plan-only` re-runs steps 1–5 (cache hit this time, since the plan is already cached and the schema presumably hasn't changed) and proceeds to step 6.

**Default run continues:**
6. `DdlReconciler.applyDdl()` executes the additive-only DDL actions against the destination (create-missing-table, add-missing-column only); conflicts are recorded but not resolved (§3).
7. `ReferentialGraphBuilder` builds the `DependencyGraph` and load order from the (possibly just-created) destination-relevant schema plus FK/virtual-FK metadata.
8. `MigrationJobService` launches a Spring Batch `Job` with one `Step` per table, steps wired in dependency order (independent tables may run as parallel step flows — see §10):
   - Each step reads the source table in chunks via a keyset-paginated `ItemReader` (ordered by the table's primary/natural key, not `OFFSET`, so restart is stable);
   - `MaskingEngine` masks each row per the `TablePlan`;
   - `NaturalKeyUpsertWriter` upserts the chunk to the destination by natural key;
   - each chunk commits as one transaction (per-table, per-chunk consistency — req. 10: a table is "consistent" at the point any given chunk has committed, meaning every row in that chunk reflects a fully-masked, fully-upserted state; a table with some chunks committed and others not is an expected, safe intermediate state because later chunks don't causally depend on earlier ones for correctness, only for progress).
9. Errors within a chunk trigger that chunk's rollback; Spring Batch's `JobRepository` (control-plane store) records the step's last successful chunk, and that table's step ends `FAILED`. **This does not abort the whole job**: `MigrationJobService` builds the job as independent parallel `Flow`s per dependency-graph layer/branch rather than one linear step chain, so a failed step only short-circuits its own branch — specifically, its table plus everything in `DependencyGraph.transitiveDependents(...)` of it are marked `SKIPPED` and not attempted this run, while every table outside that dependent set (including the rest of the same layer) continues loading normally. This is the concrete mechanism behind requirement 10's "some tables updated, others not yet reached": the *job* can complete with a mix of `COMPLETED` and `SKIPPED`/`FAILED` table outcomes, and only the overall run status reflects that it was partial. Already-committed chunks/tables remain as-is (safe, since upserts are idempotent).
10. **Resumability:** re-invoking with the same run identity (schema fingerprint + explicit run id, used as Spring Batch `JobParameters`) resumes the `FAILED` job from the first incomplete step/chunk of the failed branch, and also re-attempts every table that was marked `SKIPPED` as a transitive dependent of it (step 9), rather than restarting the whole run. Re-invoking as a *fresh* run (new parameters) simply reprocesses every table from scratch — also safe, just redundant, because natural-key upsert makes every table's load idempotent regardless of how many times a given source row has been processed before.
11. `AuditLogService` records per-table row counts, DDL actions taken, masking techniques applied, conflicts, and errors for the completed (or partially completed) run.

## 8. Java/Spring Boot Architecture Mapping

- **Starters:** `spring-boot-starter` (core + `CommandLineRunner`), `spring-boot-starter-jdbc` (relational connectors, HikariCP pooling out of the box), `spring-boot-starter-batch` (chunk-oriented load, `JobRepository`), `spring-boot-starter-validation` (config binding validation), plus per-engine JDBC drivers (postgresql, mysql-connector-j, ojdbc, mssql-jdbc) and the MongoDB Java driver.
- **Single Maven module, not per-engine submodules:** per the simplicity constraint (req. 12), all connectors live as packages (`com.migration.connector.<engine>`) inside the one deployable module, not as separate Maven submodules — a multi-module split would add build/release complexity (per-module POMs, an aggregator, inter-module versioning) to solve a classpath-size problem that Spring already solves more simply. Each connector's JDBC driver dependency is declared `<optional>true</optional>` in the single POM, and each connector `@Component` is guarded with `@ConditionalOnClass`/`@ConditionalOnProperty` (matching its `migration.source.type`/`migration.destination.type` configuration) so a deployment that only ever targets Postgres and Oracle can simply exclude the mysql/mssql/mongodb driver dependencies at build time without needing a module boundary to enforce it — the same "no code change per combination" property from requirement 4 is preserved either way, this is purely a build-packaging choice.
- **Batch approach — Spring Batch chosen over a hand-rolled JDBC loop:** requirement 10's "checkpointed chunk-by-chunk" and resumable-on-failure language maps directly onto Spring Batch's chunk-oriented `Step` + `JobRepository` restart machinery. Reimplementing chunk checkpointing and restart bookkeeping by hand would reproduce most of what Spring Batch already provides, which the simplicity constraint (req. 12) argues against once a real requirement (resumability) is in play. The one piece of added complexity — a `JobRepository` datastore — is resolved by using the same embedded control-plane database used for the plan cache and audit log (see below), so it doesn't add an external dependency.
- **Connector registration:** each connector is a `@Component` annotated with a custom `@ConnectorFor("postgres")` marker; `ConnectorRegistry` collects `List<Connector>` via constructor injection and indexes by that annotation's value — standard Spring bean discovery, no reflection/classpath scanning beyond what component-scan already does, no SPI/`ServiceLoader` needed within a single deployable.
- **Config binding:** `@ConfigurationProperties(prefix = "migration")` bound to an immutable `MigrationConfig` record tree (source, destination, run, overrides, ai, masking, controlPlane), loaded from `application.yml` plus an externally supplied `--spring.config.additional-location=file:./migration.yml`. Validated with `jakarta.validation` annotations (e.g., `@NotBlank` on connection URLs) so malformed config fails fast at startup, before any connection attempt. Cross-field validation that declarative annotations can't express — an `ai.provider` value with no matching `@AiProviderFor` bean, or a `referentialIntegrity.cycles[]` entry with `strategy: DECLARED_ORDER` missing `loadOrder` — is checked explicitly at startup and throws `ConfigurationException` (§11), so these also fail before any connector is touched, not mid-run.
- **Connection pooling:** HikariCP (Spring Boot's default `DataSource`) for every relational connector, each with its own pool (`source.pool.*`, `destination.pool.*`, `controlPlane.pool.*` — the latter deliberately separate so control-plane bookkeeping never contends with source/destination throughput). MongoDB connectors use the driver's native `MongoClientSettings` connection pool, configured under an analogous `pool.*` block for consistency.
- **Entry point:** a `MigrationRunner implements CommandLineRunner` reads `ApplicationArguments` for `--plan-only`, resolves `MigrationConfig`, and delegates to `MigrationOrchestrator.run(mode)`; the process exit code reflects success/partial-failure/hard-failure so it's script/CI friendly.
- **Masking key management:** `MaskingKeyProvider` is an interface with a default `EnvVarMaskingKeyProvider` (reads a base64 key from an env var named in `masking.keySource`, e.g. `env:MASKING_HMAC_KEY`) and is designed to be swapped for a `VaultMaskingKeyProvider`/`SecretsManagerMaskingKeyProvider` without touching call sites. The key is **never** written to the config file, the plan cache, or any log line — a Logback/SLF4J `TurboFilter`/pattern-layer redaction rule masks any log message matching a configured list of secret property names as a defense-in-depth measure, and `MaskingKeyProvider.currentKey()` returns a byte array that is zeroed after use rather than retained on a long-lived object.

**Type-mapping matrix (representative excerpt — the real matrix is a config resource per connector, not this table):**

| Canonical Type | Postgres | MySQL | Oracle | SQL Server | Mongo (BSON) |
|---|---|---|---|---|---|
| `STRING` | `varchar(n)` | `varchar(n)` | `varchar2(n)` | `nvarchar(n)` | `string` |
| `TEXT` | `text` | `text`/`longtext` | `clob` | `nvarchar(max)` | `string` |
| `INTEGER` | `integer` | `int` | `number(10)` | `int` | `int32` |
| `LONG` | `bigint` | `bigint` | `number(19)` | `bigint` | `int64` |
| `DECIMAL(p,s)` | `numeric(p,s)` | `decimal(p,s)` | `number(p,s)` | `decimal(p,s)` | `decimal128` |
| `BOOLEAN` | `boolean` | `tinyint(1)` | `number(1)` | `bit` | `bool` |
| `DATE` | `date` | `date` | `date` | `date` | `date` (truncated) |
| `TIMESTAMP` | `timestamp` | `datetime` | `timestamp` | `datetime2` | `date` |
| `BINARY` | `bytea` | `blob` | `blob` | `varbinary(max)` | `binData` |
| `UUID` | `uuid` | `char(36)` | `raw(16)` | `uniqueidentifier` | `binData(4)` |

Each connector supplies this table (both directions: native→canonical for introspection, canonical→native for DDL generation) as a Spring-loaded config resource (YAML/properties bean), not as conditional Java logic — adding an engine or a new mapping is a config change.

## 9. Configuration Schema

**(a) User-provided input config** (`migration.yml`):

```yaml
migration:
  source:
    type: oracle
    connection:
      url: jdbc:oracle:thin:@//host:1521/svc
      username: ${SOURCE_DB_USER}
      password: ${SOURCE_DB_PASSWORD}
    pool:
      maxSize: 10
  destination:
    type: postgres
    connection:
      url: jdbc:postgresql://host:5432/warehouse
      username: ${DEST_DB_USER}
      password: ${DEST_DB_PASSWORD}
    pool:
      maxSize: 10
  run:
    batchSize: 5000
    parallelism: 4
  overrides:
    naturalKeys:
      - table: customers
        columns: [external_id]
    masking:
      - table: customers
        column: email
        technique: SYNTHETIC_SUBSTITUTION
    forceSensitive:
      - table: support_notes
        column: free_text
    virtualForeignKeys:
      - childTable: orders
        childColumns: [customer_ref]
        parentTable: customers
        parentColumns: [external_id]
  ai:
    enabled: true
    provider: "private-endpoint"    # resolves to an @AiProviderFor("private-endpoint") bean
    confidenceThreshold: 0.85
    allowDataSampling: false
    sampleSize: 20
  masking:
    keySource: "env:MASKING_HMAC_KEY"
    keyVersion: 1
  referentialIntegrity:
    cycles:
      - tables: [employees, departments]
        strategy: DEFERRED_TRANSACTION   # DEFERRED_TRANSACTION | DECLARED_ORDER
        # DEFERRED_TRANSACTION: every connector in `tables` must report
        #   TransactionalBatchWrite.supportsDeferredConstraints() == true (§5);
        #   plan generation fails fast if not.
      - tables: [categories, category_parents]
        strategy: DECLARED_ORDER
        loadOrder: [categories, category_parents]   # required only for DECLARED_ORDER
        # the FK edge that would violate loadOrder is omitted from DdlPlan's
        # constraint emission for this cycle — never enforced at the destination.
    # any detected cycle with no matching entry above defaults to UNSUPPORTED:
    # flagged in the plan report/audit log and run-blocking for those tables (§5).
  controlPlane:
    datastore: "jdbc:h2:file:./control-plane/migration-control"
```

**(b) Generated/cached `MigrationPlan`** (produced by `PlanGenerationService`, persisted in the control-plane store, consumed directly by the load run, and emitted verbatim by `--plan-only`):

```json
{
  "schemaFingerprint": "sha256:9f2c...e71",
  "generatedAt": "2026-08-30T10:15:00Z",
  "tables": [
    {
      "sourceTable": "customers",
      "destinationTable": "customers",
      "naturalKey": ["external_id"],
      "loadOrder": 1,
      "dependsOn": [],
      "columns": [
        {
          "source": "ssn", "destination": "ssn", "canonicalType": "STRING",
          "sensitive": true, "confidence": 0.95, "decisionSource": "ai+rule",
          "regulatoryBasis": ["GDPR", "PCI_DSS"],
          "masking": { "technique": "HASH_HMAC", "encoding": "BASE64URL" }
        },
        {
          "source": "date_of_birth", "destination": "date_of_birth", "canonicalType": "DATE",
          "sensitive": true, "confidence": 0.88, "decisionSource": "ai",
          "regulatoryBasis": ["HIPAA"],
          "masking": { "technique": "GENERALIZE_BUCKET", "granularity": "YEAR" }
        },
        {
          "source": "loyalty_tier", "destination": "loyalty_tier", "canonicalType": "STRING",
          "sensitive": false, "confidence": 0.99, "decisionSource": "ai"
        }
      ],
      "ddlActions": [
        { "type": "ADD_COLUMN", "column": "loyalty_tier" }
      ]
    },
    {
      "sourceTable": "orders",
      "destinationTable": "orders",
      "naturalKey": ["order_number"],
      "loadOrder": 2,
      "dependsOn": ["customers"],
      "columns": [
        {
          "source": "customer_ref", "destination": "customer_ref", "canonicalType": "STRING",
          "sensitive": true, "confidence": 1.0, "decisionSource": "override-key-column",
          "masking": { "technique": "HASH_HMAC", "encoding": "BASE64URL" }
        }
      ],
      "ddlActions": [ { "type": "CREATE_TABLE" } ]
    }
  ]
}
```

## 10. Non-Functional Considerations

**Credential/secrets handling:** all DB credentials and the masking key are referenced from config via placeholders (`${ENV_VAR}` or `env:VAR` for the masking key specifically), never inlined; resolution happens through Spring's standard property-source mechanism for DB credentials and through the pluggable `MaskingKeyProvider` for the masking key. Nothing secret is ever written into the plan cache (which records *which technique* was used, never the key) or the audit log. A log-redaction filter defends against accidental secret leakage into stack traces or debug logs.

**Audit logging:** `AuditLogService` writes append-only records to the control-plane store for: run start/end + outcome, schema fingerprint and plan version used, per table — row counts inserted/updated, DDL actions taken, per column — technique applied, its `regulatoryBasis` tag(s) (§6/§9b), decision source (ai/rule/override/fallback), and confidence, any DDL conflicts or FK-cycle flags encountered, and any chunk-level errors. This is the evidence base for compliance reviews: it demonstrates *what* was treated as sensitive and *how* it was rendered non-reversible (supports GDPR Art. 5(2)/30 accountability and records-of-processing expectations), that PHI-adjacent fields were de-identified per a documented method (supports a HIPAA Safe Harbor audit trail), and that PANs were never stored in the clear at the destination (supports PCI-DSS logging/evidence expectations). Because each record carries its regulatory basis, the audit store — queryable directly, since it's a relational schema, rather than only log-file based — can answer both "what happened to column X in table Y across all runs" and "show every column masked for PCI-DSS reasons across the last run," which a bare sensitive/not-sensitive flag couldn't support.

**Performance/parallelism:** cross-table parallelism is bounded by the DAG — tables in the same topological "layer" (no dependency between them) run as concurrent Spring Batch step flows, up to `run.parallelism` concurrent steps; FK-dependent layers remain sequential. Within one large table, the chunked `ItemReader` can be partitioned by natural/primary-key range (Spring Batch partitioning) for additional intra-table parallelism, bounded by the source/destination connection pool sizes so parallelism never exceeds available connections.

**Performance targets (design-time guideline, §1 assumption 7 — tune via `run.*`/`ai.*` config per deployment, not a contractual SLA):**
- **Load throughput:** sustained chunk-commit rate at the default `run.batchSize` (5,000) and `run.parallelism` (4) should move a single 10M-row table in the low tens of minutes on commodity hardware (local-network source/destination, no exotic latency) — a regression target for task 48's Testcontainers suite, not a hard number to design around.
- **Schema scale:** designed and tested up to ~1,000 tables per schema, matching §6's AI-call batching math (~15–20 tables/call at that size). Beyond that, AI-call round-trips and the DDL-reconciliation step (§7 step 5) are the first bottlenecks, not the batch-load path itself.
- **`--plan-only` latency (cache miss):** dominated by AI round-trips; target under 2 minutes for a ≤100-table schema at ~1 call/table (§6), scaling roughly linearly with call count for larger schemas. A cache hit is near-instant regardless of schema size (no AI call, §6).
- **Parallelism ceiling:** never exceeds `source.pool.maxSize`/`destination.pool.maxSize` (default 10 each) — `run.parallelism` above the smaller of the two pool sizes just adds queuing, not throughput.

A throughput regression test (task 48) asserts the default config sustains the load-throughput target above against a synthetic large dataset, so a performance regression is caught in CI before it reaches a production-scale run.

**Failure/resume behavior:** consistent with §7 step 9–10 — chunk-level transactions, idempotent upsert, a failed table's branch (that table plus its transitive dependents) is skipped without aborting unrelated branches of the job, Spring Batch restart from the last completed chunk (and re-attempt of skipped dependents) when re-run with the same job identity, and safe (if redundant) full reprocessing when re-run fresh. No cross-table atomicity is claimed or needed (req. 10).

**Testing/validation strategy:**
- Unit tests per `MaskingTechnique` implementation: determinism (same input+key → same output across calls), one-wayness (no code path retains or can reconstruct the original from the masked value), and format/length compliance.
- A shared `ConnectorContractTest` abstract test suite, run against a Testcontainers instance of each supported engine, that every connector implementation must pass: introspection accuracy, additive-only DDL behavior (including a pre-seeded "conflicting column" fixture asserting no alteration occurs), upsert idempotency (running the same batch twice yields the same destination state), and capability-interface correctness (e.g., Mongo connector correctly reports non-`DdlCapable`).
- A referential-integrity test with a golden dataset covering composite keys and a self-referencing FK, asserting masked child FK values equal masked parent PK values after a run.
- End-to-end integration tests over representative heterogeneous pairs (e.g., Oracle→Postgres, MySQL→MongoDB) via Testcontainers, run in CI.
- A compliance-oriented test asserting no raw sensitive value ever appears in an AI request payload (contract test against `AiPlanAnalyzer`'s outbound payload) and none appears in application logs (log-scrubbing assertion).
- A throughput regression test against the performance targets above, using a synthetic large dataset.

## 11. Handoff Spec for Implementation

Interfaces/classes below are signatures only, grouped by component from §2. Supporting types (`SchemaModel`, `Row`, `TableRef`, `CanonicalType`, etc.) are referenced but not exhaustively enumerated field-by-field; their shape follows directly from the JSON/YAML examples in §9 and the type table in §8.

**Exceptions**

A small unchecked hierarchy, declared alongside the domain model (task 1) since every layer below references it:

```java
public class MigrationException extends RuntimeException { ... }   // root

// Thrown by PlanGenerationService/KeyColumnMaskingValidator (§4) when a plan assigns
// a non-injective technique to a key column, and by CycleLoadStrategyResolver (§5)
// when a cycle declares DEFERRED_TRANSACTION but an involved connector doesn't
// support it. Always surfaced before any write happens — plan generation fails
// fast, the run never starts.
public class PlanValidationException extends MigrationException { ... }

// Wraps underlying driver/client failures (JDBC SQLException, Mongo driver exceptions)
// from testConnection()/introspectSchema()/extract()/upsert()/applyDdl(), so callers
// catch one type regardless of engine.
public class ConnectorException extends MigrationException { ... }

// Thrown at startup for cross-field config validation Bean Validation annotations
// can't express — e.g. `ai.provider` with no matching @AiProviderFor bean, or a
// referentialIntegrity.cycles[] entry with strategy: DECLARED_ORDER missing loadOrder.
public class ConfigurationException extends MigrationException { ... }
```

**Connectors**

```java
public interface Connector {
    DbType type();
    void testConnection();
    TypeMapper typeMapper();
}

public interface SourceConnector extends Connector {
    SchemaModel introspectSchema();
    RowStream extract(TableRef table, ExtractCursor cursor, int batchSize);
}

public interface DestinationConnector extends Connector {
    SchemaModel introspectSchema();
    UpsertResult upsert(TableRef table, List<String> naturalKeyColumns, List<Row> rows);
}

public interface DdlCapable {
    DdlPlan diffSchema(SchemaModel desired, SchemaModel existing);
    void applyDdl(DdlPlan plan);
}

public interface TransactionalBatchWrite {
    <T> T inTransaction(Supplier<T> unitOfWork);
    boolean supportsDeferredConstraints();
}

public interface ReferentialMetadataCapable {
    List<ForeignKeyRef> introspectForeignKeys();
}

public interface TypeMapper {
    CanonicalType toCanonical(NativeTypeDescriptor nativeType);
    NativeTypeDescriptor fromCanonical(CanonicalType canonicalType);
}

public interface ConnectorRegistry {
    SourceConnector resolveSource(String dbType);
    DestinationConnector resolveDestination(String dbType);
}
```

**Masking Engine**

```java
public interface MaskingTechnique {
    MaskingTechniqueId id();
    boolean isInjective();               // must be true to be eligible for key columns
    MaskedValue apply(Object rawValue, MaskingContext ctx);
}

public interface MaskingEngine {
    Row maskRow(Row sourceRow, TablePlan plan);
}

public interface MaskingKeyProvider {
    byte[] currentKey(int keyVersion);
}
```

**Referential Integrity**

```java
public interface ReferentialGraphBuilder {
    DependencyGraph build(SchemaModel model, List<VirtualForeignKey> overrides);
}

public interface DependencyGraph {
    List<TableRef> topologicalLoadOrder();
    List<List<TableRef>> loadLayers();          // parallelizable groups
    List<List<TableRef>> detectCycles();
    Set<TableRef> transitiveDependents(TableRef table);   // used to skip downstream tables on failure/DDL conflict
}
```

**DDL / Schema Reconciliation**

```java
public interface SchemaFingerprinter {
    String fingerprint(SchemaModel model);
}

// DdlReconciler is DdlCapable.diffSchema/applyDdl on the DestinationConnector itself (§3);
// no separate top-level type beyond what's declared above.
```

**Upsert / Load**

```java
public interface NaturalKeyUpsertWriter {
    UpsertResult write(TableRef table, TablePlan plan, List<Row> maskedRows);
}
```

**Plan Generation (AI-assisted)**

```java
public interface PatternRuleEngine {
    List<PatternSignal> scan(TableRef table, List<Object> sampleValues);
}

public interface AiPlanAnalyzer {
    AiAnalysisResult analyze(SchemaModel schema,
                              Map<TableRef, List<PatternSignal>> patternSignals,
                              AiAnalysisConfig config);
}

// One @Component per provider, annotated @AiProviderFor("<provider-id>");
// resolved from `ai.provider` config the same way ConnectorRegistry resolves connectors.
public interface AiProviderRegistry {
    AiPlanAnalyzer resolve(String providerId);
}

public interface PlanCacheRepository {
    Optional<MigrationPlan> findByFingerprint(String schemaFingerprint);
    void save(MigrationPlan plan);
}

public interface PlanGenerationService {
    // schemaFingerprint is computed by the orchestrator (via SchemaFingerprinter, §7 step 3)
    // and passed in rather than recomputed here.
    MigrationPlan getOrGeneratePlan(String schemaFingerprint, SchemaModel sourceSchema, MigrationConfig config);
}
```

**Orchestration**

```java
public interface MigrationOrchestrator {
    RunResult run(RunMode mode);   // RunMode: FULL | PLAN_ONLY
}

public interface MigrationJobService {
    JobExecutionResult loadAll(MigrationPlan plan, DependencyGraph graph,
                                SourceConnector source, DestinationConnector destination);
}

public interface AuditLogService {
    void recordRunStart(RunContext ctx);
    void recordTableResult(RunContext ctx, TableRef table, TableRunOutcome outcome);
    void recordRunEnd(RunContext ctx, RunResult result);
}

public interface PlanReportWriter {
    void write(MigrationPlan plan, DdlPlan ddlDiff, ReportSink sink);
}
```

Each of these is a Spring `@Component`/`@Service` bean; `MigrationOrchestrator`, `PlanGenerationService`, `MigrationJobService`, and `AuditLogService` depend on the others via constructor injection, resolving concrete connectors through `ConnectorRegistry` at run start rather than at bean-definition time (so connector selection is driven by runtime config, not by which beans happen to be on the classpath alone).

## 12. Implementation Task List

Root package for all classes below: `com.migration` (e.g. `com.migration.domain`, `com.migration.connector.postgres`). Tasks are numbered in strict dependency order — a task never depends on a higher-numbered task. Tasks with no listed dependency on each other (e.g., the per-engine connector tasks 8–12) are independent of one another and can be picked up in any order or in parallel by separate agents, but each still depends on the earlier foundational tasks shown.

| # | Task | Produces (files/classes) | Depends on |
|---|---|---|---|
| 1 | Core domain model: canonical type system, schema description types, and the exception hierarchy | `domain.CanonicalType`, `domain.NativeTypeDescriptor`, `domain.DbType`, `domain.TableRef`, `domain.ColumnDescriptor`, `domain.SchemaModel`, `domain.ForeignKeyRef`, `domain.Row`, `domain.ExtractCursor`, `domain.exception.MigrationException`, `PlanValidationException`, `ConnectorException`, `ConfigurationException` (§11) | — |
| 2 | Config model: `@ConfigurationProperties` tree bound from `migration.yml` | `config.MigrationConfig` (+ nested `SourceConfig`, `DestinationConfig`, `RunConfig`, `OverridesConfig`, `AiConfig`, `MaskingConfig`, `ReferentialIntegrityConfig` (cycle strategies, §9a), `ControlPlaneConfig`), Bean Validation annotations | 1 |
| 3 | Plan/report model: the generated-plan and DDL-diff data structures | `plan.MigrationPlan`, `plan.TablePlan`, `plan.ColumnPlan`, `plan.MaskingRule`, `plan.DdlPlan`, `plan.DdlAction`, `plan.VirtualForeignKey`, `plan.PatternSignal`, `plan.AiAnalysisResult` | 1 |
| 4 | Connector API: base interfaces and capability interfaces | `connector.api.Connector`, `SourceConnector`, `DestinationConnector`, `DdlCapable`, `TransactionalBatchWrite`, `ReferentialMetadataCapable`, `TypeMapper`, plus `RowStream`, `UpsertResult` | 1 |
| 5 | Connector registry and discovery annotation | `connector.api.ConnectorFor` (annotation), `connector.registry.DefaultConnectorRegistry` implementing `ConnectorRegistry` (collects `List<Connector>`, indexes by `@ConnectorFor` value) | 4 |
| 6 | Shared JDBC connector scaffolding used by every relational connector | `connector.jdbc.AbstractJdbcSourceConnector`, `AbstractJdbcDestinationConnector`, `SqlDialect` (strategy interface), `JdbcSchemaIntrospector` (via `DatabaseMetaData`), keyset-pagination extraction helper | 4 |
| 7 | Type-mapping matrix loader | `connector.jdbc.TypeMappingMatrix` (loads a per-connector YAML resource into `TypeMapper`), YAML resource format definition | 1, 4 |
| 8 | Postgres connector | `connector.postgres.PostgresSourceConnector`, `PostgresDestinationConnector`, `PostgresSqlDialect`, `postgres-type-mapping.yml` | 6, 7 |
| 9 | MySQL connector | `connector.mysql.MySqlSourceConnector`, `MySqlDestinationConnector`, `MySqlSqlDialect`, `mysql-type-mapping.yml` | 6, 7 |
| 10 | Oracle connector | `connector.oracle.OracleSourceConnector`, `OracleDestinationConnector`, `OracleSqlDialect`, `oracle-type-mapping.yml` | 6, 7 |
| 11 | SQL Server connector | `connector.sqlserver.SqlServerSourceConnector`, `SqlServerDestinationConnector`, `SqlServerSqlDialect`, `sqlserver-type-mapping.yml` | 6, 7 |
| 12 | MongoDB connector (sampling-based introspection; no `DdlCapable`/`ReferentialMetadataCapable`) | `connector.mongodb.MongoSourceConnector`, `MongoDestinationConnector`, `MongoTypeMapper`, `mongodb-type-mapping.yml` | 4, 7 |
| 13 | Shared connector contract test suite | `connector.api.ConnectorContractTest` (abstract Testcontainers-based test class: introspection, additive-only DDL incl. pre-seeded conflict fixture, upsert idempotency, capability-interface correctness) | 4 |
| 14 | Per-connector contract test instantiation | `connector.postgres.PostgresConnectorContractTest`, `...MySqlConnectorContractTest`, `...OracleConnectorContractTest`, `...SqlServerConnectorContractTest`, `...MongoConnectorContractTest` (each extends task 13's suite against its own Testcontainers image) | 13, 8, 9, 10, 11, 12 |
| 15 | Masking technique API | `masking.MaskingTechnique`, `masking.MaskingTechniqueId`, `masking.MaskedValue`, `masking.MaskingContext` | 1 |
| 16 | Masking key provider | `masking.MaskingKeyProvider` interface, `masking.EnvVarMaskingKeyProvider` default implementation | 2 |
| 17 | HMAC hashing technique | `masking.technique.HmacHashTechnique` (`isInjective() == true`) | 15, 16 |
| 18 | Redaction technique (full + partial) | `masking.technique.RedactionTechnique` | 15 |
| 19 | Generalization/bucketing technique | `masking.technique.GeneralizationTechnique` (date-to-year, ZIP truncation, age banding) | 15 |
| 20 | Synthetic substitution technique | `masking.technique.SyntheticSubstitutionTechnique` (seeded via `HmacHashTechnique` output), bundled name/email corpora resources | 15, 17 |
| 21 | Masking engine implementation | `masking.DefaultMaskingEngine` implementing `MaskingEngine.maskRow` | 3, 17, 18, 19, 20 |
| 22 | Key-column injectivity validation rule | `masking.KeyColumnMaskingValidator` (rejects a `TablePlan` that assigns a non-injective technique to a PK/FK/natural-key column) | 3, 15 |
| 23 | Masking technique unit tests | Determinism, one-wayness, and format/length tests for each technique in 17–20 | 17, 18, 19, 20 |
| 24 | Referential dependency graph builder + cycle load-strategy resolution | `referential.ReferentialGraphBuilder` (impl), `referential.DependencyGraph` (impl: Kahn's topological sort, layer computation, Tarjan-based cycle detection, `transitiveDependents`), `referential.CycleLoadStrategyResolver` (validates each detected cycle against config `referentialIntegrity.cycles[]`: `DEFERRED_TRANSACTION` capability check, `DECLARED_ORDER` FK-edge omission, default `UNSUPPORTED`, §5) | 1, 2, 3 |
| 25 | Referential integrity unit/golden-dataset tests | Composite-key, self-referencing-FK, cycle-detection, and cycle-strategy test cases (`DEFERRED_TRANSACTION` capability-gate rejection, `DECLARED_ORDER` constraint omission, default `UNSUPPORTED` blocking) | 24 |
| 26 | DDL diff/apply logic for relational connectors | `connector.jdbc.JdbcDdlReconciler` (implements `DdlCapable.diffSchema`/`applyDdl` for `AbstractJdbcDestinationConnector`: missing/compatible/conflict classification per §3; omits the FK constraint for any edge flagged by `CycleLoadStrategyResolver` (24) as `DECLARED_ORDER`) | 6, 7, 8, 9, 10, 11, 24 |
| 27 | DDL conflict handling / column-and-table skip logic | `connector.jdbc.DdlConflictResolver` (excludes conflicted column from load; excludes whole table when conflict touches a key column) | 26, 3 |
| 28 | Additive-only DDL tests, incl. pre-seeded conflicting-column fixture | Integration tests over 26/27 per relational engine | 26, 27 |
| 29 | Natural-key upsert writer — relational | `write.jdbc.JdbcNaturalKeyUpsertWriter` + per-dialect upsert SQL generation (`ON CONFLICT`, `MERGE`, `ON DUPLICATE KEY UPDATE`) hung off each `SqlDialect` | 6, 8, 9, 10, 11 |
| 30 | Natural-key upsert writer — MongoDB | `write.mongodb.MongoNaturalKeyUpsertWriter` (`replaceOne`/`updateOne` with `upsert:true` filtered by natural-key fields) | 12 |
| 31 | Upsert idempotency integration tests | Run-twice-same-state tests for 29 and 30 | 29, 30 |
| 32 | Schema fingerprinting | `plan.SchemaFingerprinter` (impl) | 1 |
| 33 | Control-plane datastore schema and migrations | Embedded H2 schema (Flyway/SQL) for plan cache, Spring Batch `JobRepository` tables, and audit log tables; `config.ControlPlaneDataSourceConfig` | 2 |
| 34 | Plan cache repository | `plan.JdbcPlanCacheRepository` implementing `PlanCacheRepository` against the control-plane store | 3, 33 |
| 35 | Pattern rule engine | `plan.PatternRuleEngine` (impl) + regex/format library (SSN, email, credit-card Luhn, phone, national-ID shapes) | 1, 3 |
| 36 | AI plan analyzer — provider abstraction + reference implementations | `plan.ai.AiProviderFor` (annotation), `plan.ai.DefaultAiProviderRegistry` implementing `AiProviderRegistry`, `plan.ai.AbstractAiPlanAnalyzer` (shared prompt construction from schema metadata + pattern signals, never raw values unless `ai.allowDataSampling`, and response parsing into `AiAnalysisResult`), `plan.ai.publicapi.PublicApiAiPlanAnalyzer`, `plan.ai.privateendpoint.PrivateEndpointAiPlanAnalyzer` (both `@AiProviderFor`-annotated, resolved via `ai.provider`) | 1, 3, 35 |
| 37 | Plan generation service | `plan.DefaultPlanGenerationService` implementing `PlanGenerationService`: takes the orchestrator-computed `schemaFingerprint` (32, via 45) → cache check (34) → pattern scan (35) + AI analyze via `AiProviderRegistry` (36) → merge with `overrides` (2) → confidence-fallback policy → key-column validation (22) → persist via 34 | 2, 3, 22, 34, 35, 36 |
| 38 | Plan generation tests | Cache hit/miss behavior, confidence-threshold fallback, rule-overrides-AI-toward-masking asymmetry, compliance test asserting no raw sensitive value in AI request payloads | 37 |
| 39 | Spring Batch infrastructure wiring | `batch.BatchInfrastructureConfig` (`JobRepository` bound to control-plane datasource, `@EnableBatchProcessing`) | 33 |
| 40 | Per-table chunk-oriented step factory | `batch.TableStepFactory`: keyset-paginated `ItemReader` per connector type (6, 12), `ItemProcessor` delegating to `MaskingEngine` (21), `ItemWriter` delegating to `NaturalKeyUpsertWriter` (29, 30) | 6, 12, 21, 29, 30, 39 |
| 41 | Migration job service | `batch.DefaultMigrationJobService` implementing `MigrationJobService`: builds a `Job` from `DependencyGraph` load layers (24) as independent per-branch `Flow`s, parallel within a layer bounded by `run.parallelism`, sequential across layers; tables in a `DEFERRED_TRANSACTION` cycle (24) load as one combined step under `TransactionalBatchWrite.inTransaction(...)` rather than separate per-table steps; on a step `FAILED` (incl. DDL-conflict-forced skips from 27), marks `DependencyGraph.transitiveDependents(...)` of that table `SKIPPED` without aborting unrelated branches | 24, 27, 40 |
| 42 | Audit log service | `audit.JdbcAuditLogService` implementing `AuditLogService` against the control-plane store | 33 |
| 43 | Plan report writer | `report.DefaultPlanReportWriter` implementing `PlanReportWriter` (human-readable + JSON output of `MigrationPlan`/`DdlPlan`) | 3 |
| 44 | Log secret-redaction filter | `logging.SecretRedactionTurboFilter` (Logback), configured against `MaskingKeyProvider`/credential property names | 2, 16 |
| 45 | Migration orchestrator | `orchestrator.DefaultMigrationOrchestrator` implementing `MigrationOrchestrator`: introspect source/destination → fingerprint (32) → get-or-generate plan (37, passing the fingerprint) → DDL reconcile (26, 27) → build dependency graph (24) → dispatch to job service (41) for `FULL` or report writer (43) for `PLAN_ONLY`; records via audit service (42) | 5, 24, 26, 27, 32, 37, 41, 42, 43 |
| 46 | CLI entrypoint | `MigrationRunner` implementing `CommandLineRunner`: parses `--plan-only`, resolves `MigrationConfig` (2), invokes orchestrator (45), maps `RunResult` to process exit code | 45 |
| 47 | Resumability integration tests | Kill-mid-run-and-restart-with-same-`JobParameters` resume test; fresh-restart (new parameters) idempotency test | 41, 46 |
| 48 | End-to-end heterogeneous-pair integration tests + throughput regression | Full-run and `--plan-only`-then-load tests over representative pairs (e.g., Oracle→Postgres, MySQL→MongoDB) via Testcontainers; a throughput regression test against §10's performance targets using a synthetic large dataset | 46, 14, 31 |
