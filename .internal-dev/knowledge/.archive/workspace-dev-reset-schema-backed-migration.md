# SUPERSEDED — This knowledge file is archived and no longer reflects the current codebase.

# Topic

Schema-backed-only migration for development workspace layout resets.

# Source References

- `.internal-dev/plans/workspace-workarea-run-output-job-semantics/worker-directives/phase-05-dev-reset-integration-closeout.md`
- `.internal-dev/plans/workspace-workarea-run-output-job-semantics/shared/validation-matrix.md`
- `src/main/resources/application.yml`
- `~/.magenta/magenta.sqlite`
- `~/.magenta/root/`

# Key Takeaways

- Before development-root migration/reset, inventory schema-backed ownership first (workspaces, Work Areas, assignments, runs, artifacts, chats) and treat only those rows as authoritative for filesystem changes.
- Always take a timestamped SQLite backup before mutating local development metadata.
- For mixed historical roots (large legacy trees with unknown/manual directories), migrate only known schema-backed paths and avoid deleting ambiguous directories in the same pass.
- Keep non-home Work Areas on stable `workareas/<workAreaId>` disk segments and keep `home` system-owned.
- Record residual legacy directories as explicit follow-up risk rather than hiding them as “cleaned.”

# Engine Relevance

Future Phase 05-style closeouts should use schema-backed migrations plus bounded validation as the safe default: DB backup, narrow data move/update scope, full `mvn test`, bounded startup, and separate Playwright-agent dispatch for browser proof.

# Open Questions

- Should the repository add a dedicated dry-run/apply CLI for schema-backed local root migration to avoid one-off shell operations?
