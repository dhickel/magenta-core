# Persistence schema reference

Magenta Core installs a fresh namespaced schema from `src/main/resources/schema.sql`
inside one transaction. This page states the exact identity contract, every
table, and the constraints the framework relies on.

## Identity and compatibility

- schema ID: `io.mindspice:magenta-core:sealed-adts-v1`
- fresh schema version: `1`
- hash: SHA-256 of the exact packaged `schema.sql` bytes
- reserved table prefix: `magenta_core_`
- reserved index prefix: `idx_magenta_core_`

Initialization is transactional and fail-closed. The check order in
`MigrationRunner` is:

1. **No identity table**: if any `magenta_core_*` table or index exists without
   identity, reject. Otherwise install the fresh schema.
2. **Identity table present**: the single row's `schema_id`, `schema_version`,
   and `schema_hash` must match exactly; an empty or multi-row identity table
   is rejected as malformed.
3. **Structure check** (matching identity): the actual SQLite object set
   (tables + indexes, by name and definition) is compared against a freshly
   built in-memory schema; missing or changed objects are rejected, and
   unexpected objects are rejected while the applied version is still 1.
4. **Required columns, indexes, and SQL fragments** (checks, foreign keys,
   constraint text) are verified table by table.

Incompatible state is **never rewritten, dropped, or migrated** — the builder
fails with `ConfigurationException` (`schema_initialization_failed`) and the
database is left untouched for explicit operator handling. Tables named
`turnloop_*` are outside the framework's read/write set and may coexist.

## Tables

| Table | Purpose and key constraints |
| --- | --- |
| `magenta_core_schema_identity` | One schema ID (`TEXT PRIMARY KEY`), version `INTEGER NOT NULL CHECK (> 0)`, hash `TEXT NOT NULL`, `initialized_at` default now. |
| `magenta_core_schema_version` | Applied version bookkeeping: `version INTEGER PRIMARY KEY CHECK (> 0)`, `applied_at`, `description`. Version 1 is the fresh schema; caller migrations append contiguous versions. |
| `magenta_core_session_metadata` | Session title, selected model, favorite/archive flags (`CHECK IN (0,1)`), origin (default `magenta-core`), metadata JSON, created/updated timestamps. |
| `magenta_core_conversation_memory` | Ordered sealed `TurnMessage` rows. `role CHECK IN ('system','user','assistant','tool')`; a `CHECK` requires tool rows to carry nonblank `tool_name` + `tool_call_id` and non-tool rows to carry neither. `content TEXT NOT NULL` (empty allowed). |
| `magenta_core_queued_requests` | Queued caller intent. `queue_reason CHECK IN ('EXPLICIT','SESSION_ACTIVE','AWAITING_QUESTIONS')`; `explicit_model` nullable (null preserves normal resolution); claim columns are **all null or all present** (`CHECK`); claim token has a unique partial index while active. Queue order is `(session_id, enqueued_at, message_id)`. |
| `magenta_core_interactions` | Durable interaction snapshot: `turn_id` (execution turn), model, explicit tool names, metadata/continuation JSON, sealed `origin_type CHECK IN ('DIRECT','ANSWER','QUEUED')` with matching origin-ID columns, context usage triplet, `state CHECK IN ('PENDING','ANSWERED','CANCELLED')`. Cancelled rows require nonblank `cancellation_reason` + `cancelled_at`; other states require neither. A **partial unique index** enforces at most one `PENDING` interaction per session. |
| `magenta_core_interaction_questions` | Ordered nonblank questions: `(interaction_id, question_index)` primary key, `question_text CHECK (length(trim(...)) > 0)`. |
| `magenta_core_interaction_answers` | Ordered nonblank answers with the same key shape. |
| `magenta_core_interaction_tool_activities` | Ordered `status CHECK IN ('COMPLETED','FAILED')` summaries; `FAILED` rows require a `failure_category` from the closed `ToolFailureCategory` set, `COMPLETED` rows require none. |
| `magenta_core_audit_log` | Append-only typed JSON events: `event_type`, `payload_json TEXT NOT NULL`, `created_at`; indexes on `(session_id, created_at, id)` and `(event_type, created_at, id)`. |

All runtime child tables reference `magenta_core_session_metadata` directly or
through interactions with `ON DELETE CASCADE`; deleting a session cascades to
its framework-owned rows. SQLite foreign keys are enabled by the schema script
(`PRAGMA foreign_keys = ON`) and per-connection by the runner.

## Queue constraints

- Reasons are `EXPLICIT`, `SESSION_ACTIVE`, or `AWAITING_QUESTIONS`.
- Model and tool selections preserve `null` (normal resolution) versus explicit
  values: `explicit_model` is nullable with a trim-nonblank check; tool names
  are a JSON list (null JSON means normal resolution, `[]` means explicit
  empty selection).
- Claim token, claimed time, and lease expiry are either all null or all
  present; claim tokens are unique while active; the expired-claims partial
  index supports lease recovery.
- Ordering: `(session_id, enqueued_at, message_id)`.

## Interaction constraints

- At most one `PENDING` interaction per session (partial unique index on
  `session_id WHERE state = 'PENDING'`).
- The interaction ID is distinct from the execution turn ID at the Java
  boundary (`InteractionInfo` validates this).
- Cancellation requires a nonblank reason and cancellation timestamp;
  non-cancelled rows cannot carry those fields.
- Origin grouping: `DIRECT` rows carry no origin IDs; `ANSWER` rows carry the
  interaction ID; `QUEUED` rows carry the message ID.
- Ordered children preserve exact question, answer, and tool-activity order
  across restart via zero-based index columns and composite primary keys.
- Terminal rows remain until exact interaction cleanup so restart recovery can
  consume the stored ordered answer list without replaying an outcome.

## Serialized message/tool identity

`TurnMessage` rows persist the sealed variant as `role` (`system`, `user`,
`assistant`, `tool`); tool identity columns (`tool_name`, `tool_call_id`) exist
only for `tool` rows. Extension metadata is stored as JSON alongside each row.

## Caller migrations

`MigrationRunner.apply(List<Migration>)`:

- accepts later migrations in any input order, sorts by version;
- requires a contiguous sequence after the current version
  (`IllegalArgumentException` on gaps, versions `<= 1`, or duplicates);
- each callback receives a connection-bound `DataSource`; callback changes and
  the version bookkeeping row commit or roll back together;
- already-applied versions are skipped.

## Related

- [Configure persistence how-to](../how-to/configure-persistence.md)
- [Clean-break compatibility and tradeoffs](../explanation/compatibility.md)
- [Migration and MigrationRunner API](api.md#advanced-and-supporting-types)
