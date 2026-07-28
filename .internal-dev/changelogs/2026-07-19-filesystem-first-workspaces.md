# Date

2026-07-19

# Change Summary

Phase 08 — Close Documentation and Records for the filesystem-first workspace model. Reconciled all current documentation, package guides, living specifications, decisions, and knowledge files with the completed three-kind workspace implementation (phases 01-07). Removed Avatar documentation, archived superseded knowledge files, created the filesystem-first-workspaces knowledge entry, updated all end-user and technical docs to reflect the closed `AGENT`/`CHAT`/`USER` workspace model, and updated the changelog with specification impact.

# Files

## Deleted
- `docs/end-user/avatar-dashboard.md`
- `docs/technical/avatar-dashboard-fragments.md`
- `docs/technical/avatar-dashboard-layout-persistence.md`
- `docs/technical/avatar-planner-organizer.md`

## Archived Knowledge (moved to .internal-dev/knowledge/.archive/)
- `avatar-chat-sticky-resize-container.md`
- `avatar-shell-resizable-rail-geometry.md`
- `avatar-work-area-ui-refactor.md`
- `simplypages-avatar-layout-and-editing.md`
- `project-workspace-materialized-links.md`
- `workspace-api-list-and-agent-tab-operational-pattern.md`
- `workspace-file-architecture-rules.md`
- `agent-shell-workspace-alias-resolution.md`
- `workspace-dev-reset-schema-backed-migration.md`

## Created
- `src/main/java/io/mindspice/magenta2/ai/AGENTS.md` (new package guide)
- `src/main/java/io/mindspice/magenta2/api/AGENTS.md` (new package guide)
- `.internal-dev/knowledge/2026-07-19-filesystem-first-workspaces.md` (new knowledge)

## Updated Package Guides
- `AGENTS.md` (root) — replaced old workspace semantics and Avatar style reference
- `src/main/java/io/mindspice/magenta2/api/web/AGENTS.md` — removed Avatar/Work Area references, updated workspace API docs

## Updated Indexes
- `README.md`
- `docs/README.md` — removed Avatar doc links
- `docs/end-user/00-index.md` — removed Avatar, updated workspace language
- `docs/technical/00-index.md` — removed Avatar doc links
- `docs/api/00-index.md` — complete rewrite with typed workspace routes

## Updated End-User Docs
- `docs/end-user/quickstart.md` — removed `/avatar` references
- `docs/end-user/agents.md` — removed Avatar assistant section, updated workspace/Files tab
- `docs/end-user/chat.md` — updated chat file directories
- `docs/end-user/jobs.md` — removed Work Area references
- `docs/end-user/plans-and-tasks.md` — removed Work Area references
- `docs/end-user/projects-and-workspaces.md` — complete rewrite for three-kind model
- `docs/end-user/workflows.md` — removed Work Area references

## Updated Technical Docs
- `docs/technical/architecture.md` — updated workspace and persistence sections
- `docs/technical/api-reference.md` — removed `/api/work-areas` and `/api/workspaces` (generic); added typed `/api/workspace/{type}/{id}` routes
- `docs/technical/services.md` — complete rewrite of workspace and output services section
- `docs/technical/chat-planning-tasks.md` — removed run staging references
- `docs/technical/orchestration-runtime.md` — confirmed current
- `docs/technical/workflow-engine.md` — removed run staging and Work Area references
- `docs/technical/workspaces-tools-outputs.md` — confirmed current
- `docs/technical/frontend-htmx.md` — removed Avatar modal reference
- `docs/technical/configuration-operations.md` — removed Avatar datasource/schema references

## Updated Specifications
- `.internal-dev/specifications/architecture.md` — updated ARCH-20260719-01 anchors/drift to reflect completion
- `.internal-dev/specifications/services.md` — updated SVC-20260719-01 drift gaps to reflect completion
- `.internal-dev/specifications/service-graph.md` — confirmed current
- `.internal-dev/specifications/api.md` — confirmed current
- `.internal-dev/specifications/web.md` — confirmed current
- `.internal-dev/specifications/simplypages.md` — confirmed current
- `.internal-dev/specifications/decisions.md` — confirmed current

## Updated Knowledge
- `.internal-dev/knowledge/root-relative-workspace-storage.md` — updated for new model
- `.internal-dev/knowledge/plain-path-segment-id-validation.md` — updated workspace key validation
- `.internal-dev/knowledge/output-file-path-realpath-confinement.md` — updated for workspace-scoped publication
- `.internal-dev/knowledge/agents-md-specification-reference.md` — removed starter generation, updated loading pattern

# Behavioral Impact

Phase 08 primarily reconciles documentation truth with the completed implementation from phases 01-07. Final integration additionally repaired one runtime defect: former alias names such as `workspace`, `root`, `run`, `work`, `projects`, and `scratch` are now ordinary confined child names inside the selected descriptor root instead of remaining permanently blocked. `outputs/` remains publication-owned: file tools may inspect it but not mutate it, and shell execution may not use it as a working directory.

# Specification Impact

Updated architecture.md (ARCH-20260719-01) and services.md (SVC-20260719-01) to reflect completed phase removal. Specifications, decisions, and code now agree on the three-kind filesystem-first workspace contract.

# Risks

- The validation environment returned `404` for `/webjars/htmx.org/dist/htmx.min.js`. This is a pre-existing serving issue outside the workspace refactor; the server-rendered HTMX contract and controller behavior pass, but live client-side swaps still depend on resolving that environment issue.
- Stale references in archived knowledge files and the explicitly historical Phase 01 consumer inventory remain as superseded history.

# Follow-up Items

- Track the HTMX WebJar serving issue separately and verify live swaps after the environment serves the versionless asset.
- Archive the active plan after orchestration closeout.

# Git Commit

`93f7728b7259861e1b21e71fe4302daead0c871b` is the committed Phase 07/08 implementation baseline used for final revalidation.
