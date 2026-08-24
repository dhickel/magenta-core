# Phase 03: Fresh schema and persistence

## Context
The extracted core used legacy `turnloop_*` tables and repository-local DDL. The accepted contract requires a fresh namespace and restart-safe pending interactions.

## Goal
Centralize schema ownership and persist memory, sessions, queue claims, pending questions, and audit state.

## In Scope
- `magenta_core_*` schema and identity/version records.
- One migration/schema initializer path.
- JDBC repositories without constructor DDL.
- Ordered multi-question interaction state and legacy-table coexistence.

## Out of Scope
- Legacy data migration or deletion; product schema restoration.

## Implementation Steps
1. Install fresh schema transactionally.
2. Remove repository self-migration and add durable interaction repository.
3. Add SQL/restart/coexistence tests.

## Validation
Fresh-schema, legacy coexistence, restart round-trip, foreign-key, queue claim, audit, and migration tests plus `mvn clean verify`.

## Exit Criteria
Builder-created file-backed databases survive restart and never touch legacy tables.
