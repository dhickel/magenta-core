# Topic

Root-relative Magenta workspace storage.

# Source References

- `src/main/java/io/mindspice/magenta2/core/config/MagentaRootConfiguration.java`
- `src/main/java/io/mindspice/magenta2/core/config/MagentaRootProperties.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/RootRelativePathService.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspacePathLayout.java`
- `docs/technical/configuration-operations.md`

# Key Takeaways

- Treat `magenta.root.path` as the product runtime root. The default root is `${user.home}/.magenta`.
- Keep the SQLite database and data root together by default: `<magenta.root.path>/magenta.sqlite` and `<magenta.root.path>/root`.
- `WorkspacePathLayout` is the sole structural path authority. Its retained factories produce `workspace/agent/<id>`, `workspace/chat/<id>`, and `workspace/user/<name>` roots.
- Store Magenta-owned filesystem path columns as data-root-relative strings for new writes.
- `RootRelativePathService` is retained only for portable artifact-relative paths; it does not determine workspace identity or authorize roots.
- Resolve persisted path strings through `RootRelativePathService`; do not use `Path.of(storedValue)` directly on database path columns.
- Keep runtime tool context paths as resolved host filesystem paths. Database storage is portable; tool execution still needs actual host paths.
- Compatibility reads are intentionally narrow: absolute paths are accepted only when they are under the current configured data root.
- The data root contains `workspace/agent/`, `workspace/chat/`, and `workspace/user/` as the active structural workspace trees. No other active roots, links, leases, or aliases are created.

# Engine Relevance

This rule is central to root portability and future migration work. It lets Magenta move a database and root together without rewriting every owned path row, while keeping old current-root rows readable during the transition. Workspace identity is always resolved through descriptors, never reconstructed from raw paths.

# Open Questions

- Should a future migration command rewrite unstructured JSON/transcript path references, or only authoritative owned path columns?
- Should path-kind metadata be added to distinguish relative/absolute/stale values after the compatibility period?
- Should startup diagnostics report stale path rows without repairing them?
