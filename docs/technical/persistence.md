# Persistence architecture

`MigrationRunner` owns fresh-schema initialization transactionally before repository use. The schema identity is recorded in `magenta_core_schema_identity`; version 1 is recorded in `magenta_core_schema_version`.

The fresh framework tables are:

- `magenta_core_schema_identity`
- `magenta_core_schema_version`
- `magenta_core_session_metadata`
- `magenta_core_conversation_memory`
- `magenta_core_queued_requests`
- `magenta_core_interactions`
- `magenta_core_interaction_questions`
- `magenta_core_interaction_answers`
- `magenta_core_interaction_tool_activities`
- `magenta_core_audit_log`

Queued requests retain caller intent, `QueueReason`, enqueue time, and internal bounded claim-lease facts. Interaction rows retain the exact Questions snapshot, ordered questions and answers, typed origin, execution facts, tool activities, model, metadata, and continuation data. Pending questions are a durable state, not a delivery receipt; answer and cancellation mutations are qualified by session and interaction ID.

A `dataRoot` creates `magenta-core.db` beneath the selected directory. A caller-owned `DataSource` is used as supplied. An existing incompatible `magenta_core_*` schema fails before initialization can create or alter tables, with no automatic deletion or rewrite. Historical `turnloop_*` tables are not inspected, migrated, or modified.
