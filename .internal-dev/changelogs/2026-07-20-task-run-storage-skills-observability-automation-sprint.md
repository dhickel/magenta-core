# Task-Run Storage, Skills, Observability, and Automation Sprint

## Date

2026-07-20

## Git Commit

01c4e347261b6d6d9e00709d5d9166ecf7d80f91

## Change Summary

Reconciled the ten-phase task-run storage, scoped Skills, artifact lifecycle, runtime observability, schedule/reaction, and operational UI sprint to implemented repository truth.

1. **Path and migration foundation:** established native-run cardinality, canonical task path keys, trusted run/output location values, and checksum-governed ordered migrations.
2. **Run lifecycle and fencing:** reserved task-run identity before dispatch; added operation-key/digest idempotency, attempts, independent assignment/writer fences, resume/retry/replay semantics, and typed child lineage.
3. **Reserved-tree confinement:** centralized protected-tree mutation policy and kept workspace-bound process/executable behavior fail closed because process confinement is unavailable.
4. **Artifact publication and reconciliation:** added run-local staging, create-only final promotion, integrity facts, writer rechecks, idempotent reservations, and filesystem/database reconciliation.
5. **Artifact access, search, and retention:** added scope-first metadata/search/preview/download/lineage, runtime FTS5 capability probing, integrity suppression, pins/blockers, and preserve-by-default retention.
6. **Scoped skill resolution:** normalized global package and flat workspace candidates, deterministic root precedence, exact-version overlays across agent/project/job/task/workflow/chat-session specificity, stale/conflict states, and negative disclosure boundaries.
7. **Skill trust and snapshots:** added explicit digest-bound approve/reject/revoke, immutable snapshot/resources, snapshot-only activation, activation audit, and fail-closed package/executable capability surfaces. No skill is auto-trusted.
8. **Runtime summaries and measurements:** added repeated typed model/tool measurements, rebuildable non-authoritative summaries, truthful elapsed/health denominators, exact-link audits, APIs, and focused fragments.
9. **Schedule/reaction hardening:** added revisioned definitions, operation-key/digest identities, fenced schedule claims, append-only firing/dispatch attempts, causation/dedupe/loop controls, and side-effect-free simulation.
10. **Operational UI integration and closeout:** composed bounded run groups/details beside agent Files without adding persistence to explorer services; reconciled operational fragment hosts, specifications, knowledge, package guides, and public documentation.

## Files

- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/**`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/**`
- `src/main/java/io/mindspice/magenta2/ai/skills/**`
- `src/main/java/io/mindspice/magenta2/api/web/**`
- `src/main/resources/db/migrations/001-*.sql` through `008-*.sql`
- `src/main/resources/schema.sql`
- `src/main/resources/static/css/orchestration.css`
- `.internal-dev/specifications/{architecture,service-graph,services,api,web,simplypages,decisions,deferred-features}.md`
- `.internal-dev/knowledge/{task-run-artifact-lifecycle,scoped-skill-trust-snapshots,schedule-reaction-idempotency}.md`
- Affected `docs/api`, `docs/end-user`, and `docs/technical` guides.

## Behavioral Impact

Task-capable agent/user executions use canonical workspace-child run locations and fenced lifecycle state. Artifacts publish without replacement and are searched/opened only within explicit scope and verified integrity. Retention keeps artifacts indefinitely by default. Skills resolve through digest-qualified candidates but require explicit immutable-snapshot approval before model disclosure or activation. Runtime APIs preserve repeated and unavailable facts. Schedule/reaction operations recover idempotently through revision-qualified identities and expose non-executing simulation. The agent Files tab independently composes bounded run metadata while `WorkspaceExplorerService` remains filesystem-only.

## Specification Impact

Updated all Phase 10 target specifications: architecture, service graph, services, API, web, SimplyPages, decisions, and deferred features. Recorded no-auto-trust/immutable snapshots, FTS5 availability, unavailable process confinement, fail-closed executable/package behavior, preserve/keep-indefinitely retention, and revision-qualified schedule/reaction idempotency.

## Risks

- Overall sprint verdict remains **BLOCKED**: required delegated desktop/mobile Playwright evidence and final independent integration validation are not present.
- The default warm local database fails closed because its historical `schema_migrations` constraints are incompatible; an isolated fresh database starts.
- `SkillController` still exposes placeholder/empty collection responses and `packages/ingest` returns `NOT_IMPLEMENTED`; package ingestion and executable authorization are unavailable, not completed.
- Schedule/reaction list/action/simulation/audit fragments exist, but full create/edit fragment composition and required browser proof remain incomplete.
- Current open-alpha scope checks are not multi-user authentication/authorization.

## Follow-up Items

- Repair or deliberately migrate the warm database bookkeeping schema.
- Replace placeholder Skills collection/control-plane routes with bounded implemented behavior or remove them from the public surface.
- Complete package ingestion only after a proven process-confinement/archive boundary exists.
- Complete schedule/reaction create/edit fragments if that accepted deferred capability remains desired.
- Run required desktop/mobile Playwright validation and visual critique, then run independent post-phase and final integration validators before archiving the plan or declaring PASS.
