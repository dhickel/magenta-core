# Topic

Output file_path realpath confinement

# Source References

- `.internal-dev/plans/public-alpha-remediation/02-workspace-tools-outputs/subplan-06-output-symlink-materialization.md`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/OutputArtifactService.java`
- `src/test/java/io/mindspice/magenta2/ai/orchestration/workspaces/OutputArtifactServiceAttributionTest.java`

# Key Takeaways

- `file_path` output materialization must check the source path's real target, not only the lexical path string.
- Source files must resolve to real files confined beneath the effective workspace descriptor root. Validate with `toRealPath()` and compare against the descriptor root before copy.
- Use `LinkOption.NOFOLLOW_LINKS` when checking source and destination existence so broken symlinks can produce clear failures and existing destination symlinks do not hide collisions.
- Destination paths are collision-safe normalized relative names beneath the effective workspace's lazy `outputs/` directory. Reject absolute, dot-only, traversing, escaping, or symlinked destinations.
- Current direct publication targets workspace-local `outputs/` as legacy/non-task behavior. New task artifacts will target the trusted canonical `tasks/<taskPathKey>/<taskRunId>/outputs/` child after lifecycle fencing and reserved-tree policy activate it. Never invent a generic `runs/<runId>` path or derive a destination from an arbitrary `Path`.
- When the source is already the destination path, validate its real target first. This prevents an escaped symlink from being registered as a legitimate artifact.

# Engine Relevance

This pattern protects current host filesystem output materialization. Phase 04 must preserve realpath and destination-link checks when it cuts task artifacts over to trusted run-local output locations.

# Open Questions

- Domain 02 subplan 07 should verify output attribution assumptions after this materialization hardening.
