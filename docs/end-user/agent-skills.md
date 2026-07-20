# Agent Skills

This page documents current Agent Skills status in Magenta MVP.

## Current Contract Status

- **Implemented now:** root and trusted workspace candidate discovery, `SKILL.md` parsing/diagnostics, immutable digest-qualified candidates, exact scoped overlays, digest-bound trust decisions, immutable snapshots, revocation, snapshot-only activation, agent-level compatibility assignment, `/api/skills`, and the `/skills` browser/editor/trust UI.
- **Unavailable unless explicitly proven:** project scope without a trusted project authority, and executable skill resources without process-confinement capability.
- **Deferred:** user-home scopes, general registry/package ingestion, and unrestricted script execution.

## MVP Repository And File Shape

MVP skills live under the Magenta root `skills/` repository:

```text
<magenta-root>/skills/
  <skill-name>/
    SKILL.md
    scripts/      (optional)
    references/   (optional)
    assets/       (optional)
```

`SKILL.md` is required. It contains YAML frontmatter plus Markdown instructions.

## `SKILL.md` Minimum Metadata

- `name` (required): lowercase letters/numbers/hyphens, max 64 chars, matches directory name.
- `description` (required): non-empty, max 1024 chars, describes what the skill does and when to use it.
- Optional fields may include `license`, `compatibility`, `metadata`, and experimental `allowed-tools`.

## View, Assign, Trust, And Activate

1. Open `/skills` and select **Refresh** to rescan candidates and diagnostics.
2. Select a skill to inspect its metadata, source files, candidate digest, trust state, and snapshot history.
3. Use **Guided Create** to scaffold a valid skill, or create/import a skill directory under root `skills/`.
4. Edit `SKILL.md` and optional confined text resources. Refresh after an on-disk change.
5. Assign the skill to an agent. Assignment determines applicability; it does **not** make candidate content trusted.
6. Review the exact candidate digest and diagnostics. **Approve** only that version, or **Reject** it with a reason. Approval creates an immutable snapshot.
7. If approved source later changes, Magenta shows a new pending/changed-digest candidate. Review and approve it separately.
8. Use **Revoke** to block new activation of the approved logical skill. Historical snapshots and activation evidence remain for audit.
9. During execution, only approved model-visible snapshot metadata is disclosed. `activate_skill` loads immutable snapshot instructions/resources. Repeated same-snapshot activation is deduplicated; an already recorded same-run activation can be rehydrated on resume.

Pending, malformed, rejected, changed-digest, and revoked candidates do not disclose candidate-derived instructions. Trust, assignment, activation, and executable authorization are separate states.

## Browser UI

`/skills` provides a compact operational browser:

- filterable skill list with status, assignment count, and diagnostics count;
- detail view with metadata, diagnostics, optional directory indicators, and root-confined file table;
- `SKILL.md` and UTF-8 text-file editor with save and refresh;
- add-file form for the currently selected skill directory;
- top-level `scripts/`, `references/`, and `assets/` directory creation controls;
- guided scaffold creation with `SKILL.md`, optional directories, and optional starter text files;
- agent assignment/unassignment controls.

The UI includes candidate trust decisions, revocation, and immutable snapshot history. `scripts/` are visible as resources, but instruction approval is not executable authorization. Browser/package execution fails closed while process confinement is unavailable. Project-local candidate discovery is also unavailable because projects do not provide a trusted filesystem-root authority.

## Current API Surface

For operational scripting and integration, the MVP now exposes:

- `GET /api/skills`, `POST /api/skills/refresh`, `POST /api/skills`
- `GET /api/skills/{skillName}`, `GET /api/skills/{skillName}/diagnostics`
- `GET /api/skills/{skillName}/files`, `GET /api/skills/{skillName}/files/view`
- `PUT /api/skills/{skillName}/files/text`, `POST /api/skills/{skillName}/files`
- `GET /api/skills/{skillName}/assignments`
- `POST|DELETE /api/skills/{skillName}/assignments/agents/{agentId}`

The `/skills` page uses server-rendered HTMX fragments for browser CRUD/list/detail/trust/snapshot interactions. The detailed control-plane routes and current unavailable/empty collection endpoints are documented in [Skill API](../api/skills.md).

## Validation Behavior

- Name mismatch and some name-shape issues are warning-level and still load.
- Missing/empty `description` or unparseable YAML marks the skill invalid.
- Malformed skills stay visible as diagnostics and do not crash discovery.
- Optional directories (`scripts/`, `references/`, `assets/`) are detected but not eagerly loaded.

## Important MVP Limits

- Project discovery is disabled unless an explicit trusted project authority is supplied; project records do not own filesystem roots.
- No browser-driven script execution contract is promised in MVP.
- `allowed-tools` is experimental metadata and not an enforced permissions guarantee in MVP.
- Cross-client `.agents/skills` interoperability support is deferred.
- Skill activation currently returns the `SKILL.md` **body** (frontmatter stripped) plus a resource file listing.
