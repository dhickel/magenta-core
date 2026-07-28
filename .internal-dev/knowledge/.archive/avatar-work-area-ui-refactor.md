# SUPERSEDED — This knowledge file is archived and no longer reflects the current codebase.

---
schema_version: 1
document_type: knowledge
date: 2026-05-24
owner: codex
status: active
---

# Topic

Avatar Work Area UI refactor planning and implementation boundaries.

## Source References

- `.internal-dev/knowledge/.archive/avatar-work-area-ui-refactor-planning.md`
- `.internal-dev/knowledge/.archive/avatar-work-area-ui-refactor-implementation.md`
- `.internal-dev/specifications/web.md`
- `.internal-dev/specifications/simplypages.md`
- `.internal-dev/specifications/services.md`
- `src/main/java/io/mindspice/magenta2/api/web/AvatarDashboardController.java`
- `src/main/java/io/mindspice/magenta2/avatar/AGENTS.md`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/AGENTS.md`

## Key Takeaways

- Keep `/avatar` aligned with Magenta operational UI conventions, not consumer dashboard patterns.
- Keep user-facing Avatar state in `avatar.sqlite`, but keep Work Area, assignment, runtime, and output state in `magenta.sqlite`.
- Treat Work Areas as runtime-owned metadata around confined directories under an owner root.
- Resolve selected Work Area as `workspace/` for assignment execution and expose the broader owner root as `root/`.
- Keep workspace file actions under shared path-confinement policy, including symlink escape denial and protected delete constraints.
- Keep planner tasks as organizer records; do not treat them as executable Magenta task definitions.
- Reuse the shared HTMX entity-selector pattern for Work Area pickers and keep explicit browsing affordances.

## Engine Relevance

Use this as the primary ingestion target for future Avatar/workspace planning and maintenance. The archived planning/implementation files remain historical detail, while this file captures the durable contract.

## Open Questions

- Whether historical organizer data in `avatar.sqlite` should be migrated or replaced for alpha users.
- Whether Work Area controls should later be generalized beyond web submit forms.
- Whether additional reusable SimplyPages dashboard-editor components should be upstreamed after broader adoption.
