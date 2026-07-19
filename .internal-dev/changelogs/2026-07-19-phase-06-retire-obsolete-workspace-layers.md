# Phase 06 — Retire Obsolete Workspace Layers

## Date
2026-07-19

## Change Summary
Retired the generic filesystem workspace, Work Area, project-root, link, alias, migration, starter, obsolete output-routing, and filesystem-lease layers. Repaired the remaining tests for canonical descriptors and direct workspace-relative paths, then closed production gaps found during validation.

## Files
- Deleted obsolete workspace/Work Area/link/lease persistence and service types and obsolete tests.
- Reduced `WorkspacePathLayout` and `WorkspaceDirectoryService` to the closed agent/chat/user descriptor model, publication outputs, confinement, and canonical agent-root deletion.
- Updated file, shell, prompt, AGENTS.md, profile, project, status, operational-tool, selector, controller, schema, and output behavior.
- Rewrote affected tests, including replacing vacuous phase assertions with executable contract checks.
- Updated `.internal-dev/knowledge/agent-shell-workspace-alias-resolution.md` with the superseding direct-path contract.

## Behavioral Impact
- Canonical roots are `workspace/agent/<id>`, `workspace/chat/<id>`, and `workspace/user/<name>`.
- File and shell tools use direct descriptor-relative paths. Retired aliases fail closed; shell cannot target `outputs/`; file mutation beneath `outputs/` is rejected.
- Project records remain metadata-only and no project filesystem routes or bridge remain.
- Agent lifecycle remains lazy; confirmed hard delete removes only the canonical agent root without following a root symlink.
- Fresh schema no longer creates `workspaces`, `workspace_links`, `workspace_leases`, or `work_areas`; existing copies remain untouched.

## Specification Impact
Implements the active filesystem-first workspace contracts in `architecture.md`, `service-graph.md`, and `services.md`; no living specification text required correction during validation.

## Risks
- Historical databases and filesystem trees are intentionally preserved and remain inert.
- Current documentation reconciliation is owned by Phase 08.

## Follow-up Items
- Phase 07 may add the descriptor-backed agent Files tab and user workspace selectors only after this gate passes.
- Phase 08 must reconcile current documentation and archive finalized plan records.

## Validation
- `mvn test`: 629 tests, 0 failures, 0 errors, 0 skipped.
- Fresh bounded startup: application started; fresh SQLite schema contained none of the four obsolete tables.
- Existing-root bounded startup: application started; sentinel rows in all four obsolete tables remained value-equivalent.
- Static searches found no active deleted-type references, obsolete routes/schema DDL, project bridge/stub, stale tool alias defaults, vacuous assertions, or modified Phase 06 plan files.

## Git Commit
02cdc18b63cf11ddf2eac414967c7999db827ee5 (base commit; phase changes are uncommitted at validation time)
