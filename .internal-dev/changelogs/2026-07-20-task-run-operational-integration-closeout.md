## Date

2026-07-20

## Git Commit

01c4e347261b6d6d9e00709d5d9166ecf7d80f91

## Change Summary

Completed the Phase 10 implementation boundary for the task-run storage/observability sprint: added agent-scoped bounded run groups and on-demand run detail beside Files, preserved the filesystem-only explorer boundary, added dense responsive operational styling, and reconciled cross-domain specifications, package guides, end-user/technical/API/security/operations documentation, and reusable knowledge for artifacts, skill trust, and schedule/reaction idempotency.

## Files

- `src/main/java/io/mindspice/magenta2/api/web/RunOperationsFragments.java`
- `src/main/java/io/mindspice/magenta2/api/web/OrchestrationController.java`
- `src/main/java/io/mindspice/magenta2/api/web/WorkspaceExplorerFragments.java`
- `src/main/resources/static/css/orchestration.css`
- `.internal-dev/specifications/{architecture,service-graph,services,api,web,simplypages,decisions,deferred-features}.md`
- `.internal-dev/knowledge/{task-run-artifact-lifecycle,scoped-skill-trust-snapshots,schedule-reaction-idempotency}.md`
- Affected `docs/end-user`, `docs/technical`, and `docs/api` guides and package `AGENTS.md` files.
- New end-user guides for workspace/task/output startup, task-run monitoring, and schedules/reactions.
- New API guides for artifacts, skills, runtime observability, and schedules/reactions.
- New technical migration guide for versions 001–009, including the absent-version discrepancy.

## Behavioral Impact

The agent Files tab now independently loads `#agent-run-groups` beside the confined explorer. Run groups are clamped to 1-100 records and grouped by persisted source kind; expansion replaces only `#agent-run-detail`. Detail renders normalized status/outcome, source-supported timestamps, wall/active elapsed, attempt and safe current-claim state, retry/replay lineage links, model/tool counts, artifact integrity-state counts, and recent artifact facts with authorized preview links. Empty, unavailable, legacy, partial, missing, integrity-failed, and error states are explicit. Agent and run workspace scope is checked before audit composition; raw failure text is suppressed, and no claim secret, writer fence, lease owner, absolute path, or cross-scope aggregate is rendered.

No JavaScript was added. Standard loading, OOB refresh, expansion, lineage navigation, and artifact preview are HTMX-first. Visible `tasks/` and `outputs/` trees are read-only in the Files controls and text viewer, while `WorkspaceExplorerService` remains filesystem-only.

## Specification Impact

Updated architecture, dependency, service, route, web, SimplyPages, decision, and deferred-capability intended truth. The reconciled contract records active run-local publication/retention, FTS5 capability probing with unavailable fallback, absent project-root authority, immutable skill snapshot trust with fail-closed executable authorization, rebuildable runtime summaries, and revisioned/fenced schedule/reaction idempotency.

## Risks

- Current alpha HTTP routes still lack a multi-user authentication/authorization layer; agent/type/key checks are domain scoping, not principal authorization.
- Source-kind grouping is truthful but coarser than immutable definition grouping because the summary cache does not persist a definition id.
- The default local warm database failed startup closed with `Incompatible schema_migrations constraints`; an isolated fresh root started successfully. The existing local database was not modified.
- Required delegated Playwright desktop/mobile validation and final independent validator/integration gates were unavailable in this sole-agent tool session and remain required before an overall sprint PASS.
- The Phase 09 plan requires migration 009, but checked-in resources stop at `008-schedule-reaction-hardening.sql`; `007-runtime-summaries-measurements.sql` also has a stale “Migration 008” SQL header.
- The schedule firing-attempt API serializes `claimOwner` and `claimFence` and does not bind the firing to the path schedule/agent; task-run `/api/runtime/runs/{taskRunId}/*` reads lack workspace scope; root-event reaction dispatch lookup lacks path-agent filtering. These violate Phase 10 authorization-first/no-claim response contracts even where HTMX fragments are safer.
- Several skill control-plane collection/history routes remain empty placeholders and package ingestion returns `NOT_IMPLEMENTED`; these are now documented as unavailable rather than represented as complete.

## Follow-up Items

- Run the required `gpt-5.3-codex` medium Playwright validation with desktop/mobile screenshots and visual critique for Files/runs and the focused artifact/skill/runtime/schedule/reaction surfaces.
- Diagnose or deliberately migrate the local warm database's incompatible `schema_migrations` constraints before using that data root.
- Add or reconcile migration 009 through the approved plan contract; do not renumber or rewrite applied resources silently.
- Scope task-run runtime JSON reads by authorized workspace, map schedule firing attempts to a secret-free agent/schedule-checked DTO, and agent-filter root-event dispatch lookup before security sign-off.
- Implement or remove placeholder skill collection/history/catalog/overlay endpoints according to the accepted API contract.
- Run the plan's independent post-phase validator and final integration validation before archiving the sprint plan or declaring the complete signed handoff PASS.

Validation recorded in this phase: `mvn test` passed 863 tests with 0 failures/errors/skips, including focused run-fragment secrecy/OOB/preview and task-tree read-only coverage; isolated fresh-root bounded startup reached `Started Magenta2Application`; `git diff --check` passed.
