## Agent Skills Package

This package owns Agent Skills filesystem discovery, `SKILL.md` parsing/validation, metadata persistence, agent assignment metadata, runtime catalog filtering, root-confined file management, and dedicated activation behavior for the Magenta root skill repository.

### Responsibilities
- Resolve the Magenta-owned skill repository at `<magenta-root>/skills` and read-only flat skill roots at trusted AGENT/USER workspace `.skills/` locations.
- Normalize scoped candidates, parser diagnostics, immutable digests, exact assignment overlays, and explicit shadow/conflict/stale outcomes without changing model-visible activation before trust snapshots.
- Enforce confined path resolution for skill filesystem operations and reject escaping/link/non-regular package-tree entries.
- Parse and validate `SKILL.md` frontmatter/body with stable diagnostics.
- Discover skill directories and persist metadata, diagnostics, and optional-directory visibility flags.
- Persist agent skill assignments separately from approved tool metadata.
- Provide API-facing skill management/file operations through backend services while keeping controllers thin.
- Build runtime skill catalogs using assigned, enabled, loadable skills only.
- Activate assigned skills by returning the parsed `SKILL.md` body plus resource listings without eager resource reads.
- Deduplicate repeat activations per conversation/session context.
- Keep malformed skills visible as metadata diagnostics without crashing discovery.

### Change guidance
- Keep this package backend-domain only. Do not add web/controller rendering logic here.
- Do not scan guessed project-local `.agents/skills`; project scope requires an explicit trusted authority.
- Treat `allowed-tools` as experimental metadata only unless explicitly approved otherwise.
- Do not store skill assignments in `agent_profiles.approved_tool_names_json`.
- Do not eagerly load `scripts/`, `references/`, or `assets/` file content during discovery.
- Keep path-confinement checks explicit and test-backed (normalization + realpath + root prefix checks).

### Validation
- Parser tests for valid/invalid frontmatter, required fields, and lenient warning behavior.
- Discovery tests for root scan behavior, malformed safety, optional-directory flags, and refresh-after-edit metadata changes.
- Path-confinement tests for traversal/symlink escape rejection.
- Controller/API tests for skill file tree/view/save/create and assignment route behavior.
