# Core Extraction Branch Topology and Recovery

## Topic

Repository state gotcha: the Magenta working tree (branch `core`, 2026-08) is a headless mid-refactor state; the full product lives on other branches. Any agent mapping "the app" from the working tree will find almost nothing.

## Source References

- `.internal-dev/reviews/2026-08-09-product-rectification-exploration.md`
- `.internal-dev/handoffs/2026-08-09-product-model-rectification.md`
- `.internal-dev/changelogs/20260804-phase-01-maven-module-scaffolding.md` … `20260804-phase-11-autoconfigure.md`
- Commits: `a19e6c86` (phase-02-delete-web-ui), `8b6cdcc1` (phase-05-delete-app-shell)

## Key Takeaways

- Working tree (`core`): `magenta-app` = 29-file thin chat-config shell; `mindspice-core` + `mindspice-core-autoconfigure` = reusable turn-loop framework (SPIs, `TurnLoopBuilder`, `MigrationRunner`, 5 `turnloop_*` tables). `sql.init.mode: never`; no controllers/services/tools/UI.
- Phase-05 (`8b6cdcc1`) deleted 414 files / ~73,911 lines: orchestration, skills, plan, task, project, system, job, execution packages, `db/migrations/` (20 files), `SchemaMigrationRunner`, `static/js/*`. Deleted files are recoverable: `git show 8b6cdcc1~1:<path>`.
- Full product branches: `master` (2026-07-29) and `sprint/20260729-163244-project-dashboard` (2026-08-04, newest full app, 18 commits ahead of master; `schema.sql` sha256-identical to working tree). Use the sprint branch for implementation evidence.
- Active extraction sprint `.internal-dev/sprints/exec-core-refactor-20260804/`: phases 1–11 PASS, 12–14 pending (phase-14 reintegration expects the already-deleted `AgentsMdResolver` — latent gap).
- The 83-table `magenta-app/src/main/resources/schema.sql` is orphaned on `core` (zero Java consumers) but is the canonical legacy data model used for planning evidence.
- The 11 extraction-phase changelogs all say "Specification Impact: none" — the spec corpus (12 files) still describes the master-era product; treat specs as intended-contract history until the rectification sprint supersedes them.

## Project Relevance

- Before claiming "the app does X" or planning against current code, check which branch is checked out and whether the symbol exists in the tree (`git log --oneline core` for the deletion commits).
- For rectification planning, cite schema/docs from the working tree but Java/UI evidence from `sprint/20260729-163244-project-dashboard`.

## Engine Relevance

- The rectification sprint planning (sprint-planner workflow on `.internal-dev/handoffs/2026-08-09-product-model-rectification.md`) must treat branch recovery and spec supersession as first-class baseline work, not assumed state.

## Open Questions

- Whether the rectification sprint rebuilds on `core` (recommended, Option A in the handoff) or resumes the sprint branch — pending user confirmation.
- Whether the app schema is revived wholesale or slimmed during rectification.
