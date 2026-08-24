# Persistence architecture

The builder initializes `src/main/resources/schema.sql` transactionally before constructing repositories. The schema identity is hashed and recorded in `magenta_core_schema_identity`; version 1 is recorded in `magenta_core_schema_version`.

The durable tables are:

- `magenta_core_session_metadata`
- `magenta_core_conversation_memory`
- `magenta_core_pending_messages`
- `magenta_core_pending_interactions`
- `magenta_core_pending_interaction_questions`
- `magenta_core_audit_log`

Repositories issue queries only. They do not create tables in constructors. Existing `turnloop_*` objects are never inspected by the framework and remain available for an embedding application's independent legacy handling.

A `dataRoot` creates `magenta-core.db` beneath the selected directory. A caller-supplied `DataSource` is used as-is and is expected to provide SQLite-compatible behavior.
