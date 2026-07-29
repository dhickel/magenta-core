# Topic

Checksum-protected migration drift during bounded local startup validation.

# Source References

- `src/main/java/io/mindspice/magenta2/core/persistence/SchemaMigrationRunner.java`
- `.internal-dev/changelogs/2026-07-29-phase-05-materialization-files-outputs-validator-repair.md`

# Key Takeaways

- A normal startup against the local existing `~/.magenta` database failed before application wiring because its recorded checksum for immutable migration `017-job-reassignment-operations.sql` differs from the bundled resource.
- Do not edit an applied migration to make a warm database start. Treat checksum drift as a migration-bookkeeping incident and use a new corrective migration or explicit operator remediation only after diagnosis.
- A bounded fresh-root startup (`--magenta.root.path=<temporary-empty-directory>`) is useful to distinguish a local warm-data blocker from schema/application wiring regressions. The Phase 05 fresh-root startup reached `Started Magenta2Application` and was then stopped by the expected timeout.

# Engine Relevance

When validating schema-affecting changes, run both the normal warm startup and a fresh-root startup. Report a warm checksum mismatch precisely instead of attributing it to new code or weakening checksum protection.

# Open Questions

- Why does the local warm database carry a checksum differing from the tracked migration 017 resource?
