# Summary
The default local Magenta database cannot start because its applied checksum for migration 017 differs from the checked-in `017-job-reassignment-operations.sql` bytes.

# Scope
Warm startup against the configured default database at `${user.home}/.magenta/magenta.sqlite`.

# Reproduction
1. Run `timeout 30s mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=0` from the repository root.
2. Observe application-context creation fail in `SchemaMigrationRunner`.
3. Inspect `schema_migrations` version 17 and calculate the checked-in migration checksum.

# Expected
A previously migrated default database starts when its schema remains compatible with the current application.

# Actual
Startup fails closed with `Checksum drift for applied migration 017-job-reassignment-operations.sql`.

# Evidence
- Applied version-17 checksum: `ab7de9202335d3a16876d2a11721509767f6b7118d3f0eb62d922201bd6cee0d`
- Checked-in version-17 checksum: `50250b59da283618eb50c30ae4bab5d3e8ec8ddb3875c871945a06d6679d3007`
- The bounded startup command exited `1` before `Started Magenta2Application`.
- `mvn test` passes against isolated test databases, so it does not exercise this warm-database checksum mismatch.

# Impact
The configured default application instance is unavailable. This blocks the required bounded real startup validation and any live browser validation against the default root.

# Status
Open / blocking warm startup.

# Next Action
Do not alter the stored checksum or weaken checksum validation. Recover the exact originally applied migration bytes if they can be identified; otherwise design and approve an explicit data-migration/reconciliation path that preserves migration immutability and verifies the existing `job_reassignment_operations` schema before retrying warm startup.
