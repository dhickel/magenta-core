# Topic

Checksum-protected migration repair sequencing.

# Source References

- `SchemaMigrationRunner`
- `db/migrations/015-lightweight-work-tasks.sql`
- `db/migrations/016-lightweight-work-task-integrity.sql`
- `.internal-dev/reviews/2026-07-29-phase-03-lightweight-task-validator-review.md`
- `.internal-dev/bugs/migration-017-applied-checksum-drift/report.md`

# Key Takeaways

- Never rewrite a migration resource after it has been applied: startup rejects the changed checksum before application context creation.
- A discovered defect in an applied migration requires a later additive/rebuild corrective migration. Keep the original bytes exactly stable.
- SQLite trigger bodies must be installed through the migration runner rather than Spring's generic SQL script splitter, which treats trigger-body semicolons as statement boundaries.
- If a planned future phase reserved the corrective migration number, record and resolve that sequencing conflict before that phase begins.
- Passing fresh or isolated test migrations does not prove a configured warm database will start. Run the bounded application startup against the intended root and inspect `schema_migrations` on checksum drift; never replace its stored checksum or weaken fail-closed validation.
- When `schema.sql` already declares a later additive SQLite column, put the idempotent `PRAGMA table_info`/`ALTER TABLE` guard in `SchemaMigrationRunner` before the migration script. The SQL resource can then create dependent indexes without failing when Spring initialized the fresh schema before the runner. Migration 019 project-event attribution uses this pattern.

# Engine Relevance

Migration checksum validation is a fail-closed production safety boundary. Warm-start evidence is required whenever schema repair changes migration ownership.

# Open Questions

- Phase 04 must select its migration number after the applied Phase 03 corrective migration.
