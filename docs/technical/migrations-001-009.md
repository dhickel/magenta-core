# Schema Migration Guide: Versions 001–009

`SchemaMigrationRunner` is the sole ordered migration owner for this series. It discovers contiguous `NNN-lowercase-kebab.sql` resources, records each exact resource name and SHA-256 in `schema_migrations`, and applies migration SQL plus bookkeeping under one SQLite `BEGIN IMMEDIATE` transaction.

Never edit an applied resource. Add the next contiguous resource and update `schema.sql` to the same fresh-database end state. Startup fails closed on checksum drift, malformed/duplicate/gapped resources, unknown bookkeeping, or incompatible partial migration-owned structures.

## Version Inventory

| Version | Resource | Owned change |
| --- | --- | --- |
| 001 | `001-task-run-foundation.sql` | `task_run_locations`, attempts, idempotent operations, native links, and initial writer claims. Establishes workspace kind/key plus data-root-relative run paths. |
| 002 | `002-run-lifecycle-fencing.sql` | Retry/replay lineage, scoped operation identity, assignment fence generation, writer-claim generation, and typed task-run links. |
| 003 | `003-artifact-publication.sql` | `output_publications` reservation, staging/final relative paths, digest/size, guarded publication state, and reconciliation facts. |
| 004 | `004-scoped-skill-resolution.sql` | Immutable skill candidate versions/observations, exact scoped overlays, and bounded legacy-conversion diagnostics. |
| 005 | `005-artifact-access-search-retention.sql` | Extraction documents, optional FTS5 index, artifact lineage edges, run retention policies, pins, and active artifact sessions. |
| 006 | `006-skill-trust-snapshots-packages.sql` | Trust decisions, immutable snapshots/resources, activations, executable authorization, package operation audit, and resource execution audit. |
| 007 | `007-runtime-summaries-measurements.sql` | Rebuildable `run_summaries` and repeated typed `run_measurements`. |
| 008 | `008-schedule-reaction-hardening.sql` | Revisioned schedules/reactions, scheduler claims, firing/dispatch identities, attempts, causation, suppression, and assignment/run links. |
| 009 | `009-warm-database-compatibility.sql` | Immutable no-schema marker for the runner-owned warm-bookkeeping compatibility release. It preserves contiguous history without renaming or rewriting migration 008. |

## Fresh Databases

For an empty database, the runner initializes from `schema.sql` and verifies/applies the ordered history according to its fresh-start contract. The fresh schema must remain structurally equivalent to the migration-owned end state. Runner reconciliation required for SQLite objects that Spring's SQL splitter cannot install, such as trigger bodies, remains active for every target version at or beyond the introducing migration and runs before final integrity validation. FTS5 support is runtime-capability dependent; lack of FTS5 must expose search as unavailable rather than weakening scoped search.

## Existing Databases

1. Stop Magenta and back up the SQLite file and data root.
2. Confirm `schema_migrations` contains only known contiguous resources with matching checksums. A legacy empty four-column bookkeeping table without the current uniqueness/checksum constraints is normalized transactionally during startup.
3. Start Magenta once and allow the runner to serialize migration under its immediate transaction.
4. On failure, preserve the database. Do not edit bookkeeping or migration SQL in place.
5. Diagnose the exact checksum, gap, malformed-name, or partial-structure error and repair through a new migration when required.

The known pre-foundation task-run prototype is replaced automatically only when all five prototype tables are empty. Non-empty prototype state fails closed and requires an explicit data migration. Migration 003 preserves legacy artifact history without inventing task attribution or copying absolute locations into new rows. Migration 008 drops the replaced legacy schedule/reaction definition tables before creating revisioned structures; operators requiring legacy definition preservation need a deliberate migration path rather than inferred history.

## Validation Checklist

- Fresh and representative prior databases reach equivalent migration-owned schemas.
- Re-running migrations is a no-op.
- Exact-byte checksum drift fails startup.
- Interrupted and competing starters recover or serialize safely.
- `schema.sql` matches the implemented migration end state.
- Reads/startup do not create absent task-run or `.skills` roots.
- Persisted new paths are data-root-relative, never absolute.
