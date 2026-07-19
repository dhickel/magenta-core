---
schema_version: 1
document_type: specification-workflow
status: active
owner: internal-dev
created: 2026-05-25
---

# Specification Workflow

## Beginning Gate

- Read relevant specifications before changing services, APIs, web pages or fragments, SimplyPages modules, architecture, persistence, workflow behavior, or product contracts.
- Before non-trivial work, list or search `.internal-dev/knowledge/` filenames and read only files whose domain matches the task.
- When lost, blocked by project context, or correcting a false assumption, search knowledge filenames again, then run a deeper grep across `.internal-dev/knowledge/`.
- Use web or official docs when missing information is external framework, library, tool, protocol, or platform behavior and local knowledge is absent or stale.

## Mid-Workflow Routing

- Specifications own intended contracts.
- `decisions.md` owns durable tradeoffs.
- Knowledge owns reusable learning.
- Changelogs own prior edit context.
- Bugs own defects and must be mirrored directly to a GitHub issue when created or compiled.
- Plans and reviews own scoped handoffs and review results.
- Horizon ideas own future direction; deferred features own accepted future capability.

## Correction Gate

When a false assumption, repeated mistake, major correction, important user correction, or repeated reverification reveals reusable context, update a domain-named knowledge file and link the affected specification or changelog when useful.

## Workspace Contract Gate

For workspace, run-output, project, job, task/plan, workflow, file-tool, shell-tool, or output-artifact changes, read `architecture.md`, `service-graph.md`, `services.md`, `api.md`, and the workspace knowledge files named by the active plan. Treat references to removed database-backed filesystem identity, job-owned roots, scratch, runtime-temp, aliases, or staged output paths as historical unless a later accepted specification explicitly reintroduces a concrete capability.

## Closeout Gate

Update affected specifications, knowledge, bugs, changelogs, plans, and reviews. If no specification changed, the changelog must include `Specification Impact: none` with one sentence explaining why.

## Focus Migration Audit

| source | disposition | destination or drop reason |
| --- | --- | --- |
| `current-focus.md` | migrated | `FOCUS-20260525-01` Avatar dashboard durable direction moved to `web.md`, `simplypages.md`, and `horizon-ideas.md`; superseded `FOCUS-20260522-01` preserved only as migration history here. |
| `unfinished-work.md` | migrated | Deferred product capabilities moved to `deferred-features.md`, including `UNFINISHED-20260524-03`, `UNFINISHED-20260524-02`, `UNFINISHED-20260524-01`, `UNFINISHED-20260523-01`, `UNFINISHED-20260523-02`, `UNFINISHED-20260522-04`, and `UNFINISHED-20260522-03`. Recently closed rows were intentionally dropped because changelogs already preserve completion evidence. |
| `architecture-focus.md` | migrated | Active architecture rows moved to `architecture.md`, `service-graph.md`, and `decisions.md`; obsolete initialization constraint dropped because this workflow replaces that file. |
| `decisions.md` | migrated | Active and superseded decision rows copied to `decisions.md`, preserving id, decision, source, decided date, knowledge reference, status, review timing, caveats, and supersession state. |
| `ideas-inbox.md` | migrated/drop | Product-directional ideas moved to `horizon-ideas.md`; process ideas moved only where accepted as workflow direction. Archived-source provenance preserved in row notes, not as active source paths. |
| `horizon-ideas.md` | migrated | Empty curated-register role preserved in `horizon-ideas.md`. |
| retired focus guide and README | dropped | Replaced by this flat specifications workflow and `specifications/AGENTS.md`. |

## Notes Migration And Drop Audit

| source | disposition | destination or drop reason |
| --- | --- | --- |
| `2026-04-26-shell-command-line-parser-deficiencies.md` | migrated | Shell command line parser hardening captured in `deferred-features.md` and `services.md`; searchable validation term retained as "shell command line parser". |
| `2026-04-29-searxng-deployment.md` | migrated/drop | Reusable deployment learning left to existing web-tool knowledge and this audit; product-facing SearXNG capability retained in `services.md` and `deferred-features.md`; host-specific command detail intentionally dropped from living specs. |
| `2026-05-04-sync-stream-serialization-gap.md` | migrated | Chat sync/stream serialization gap captured in `architecture.md` and `services.md` as architecture drift, not a current bug. |
| `2026-05-04-upgrade-to-spring-ai-2.0.md` | migrated/drop | Future Spring AI 2.0 upgrade captured in `deferred-features.md`; reusable reasoning metadata context remains in Spring AI knowledge; low-level rebuild commands intentionally dropped from living specs. |
| `2026-05-06-plan-execution-resume-ux.md` | migrated | Saved-plan retry/resume capability captured in `deferred-features.md`. |
| `2026-05-08-comprehensive-review-deferred-work.md` | migrated | Architecture hardening and future capability moved to `architecture.md`, `services.md`, `service-graph.md`, and `deferred-features.md`. |
| `2026-05-08-orchestration-long-running-task-hardening.md` | migrated | Lease heartbeat/configurable duration moved to `deferred-features.md` and `services.md`. |
| `2026-05-08-task-execution-sse-followups.md` | migrated | Native reactive or executor-backed task SSE follow-up moved to `api.md` and `deferred-features.md`. |
| `2026-05-11-container-runtime-selection.md` | migrated/drop | Docker/Podman decision context captured in `decisions.md` and `deferred-features.md`; low-level setup matrix dropped from living specs because knowledge and operational docs own reusable host setup. |
| `2026-05-11-orchestration-refactor-deferred.md` | migrated | Orchestration UI tests, model-backed Docker E2E, JS tests, sidebar collapse, model selects, controller consolidation, and workspace list endpoint moved to `deferred-features.md` or `horizon-ideas.md`. |
| `2026-05-13-operational-ui-parity-pass-02-deferred.md` | migrated | Schedules/event reactions UI, artifact identity, and workspace availability expectations moved to `deferred-features.md` and `services.md`. |
| `2026-05-13-phase-03-workspace-followups.md` | migrated | Workspace list panel and output directory hint ideas moved to `deferred-features.md` and `services.md`. |
| `2026-05-13-phase-04-docker-deferred.md` | migrated | Docker live tests, hard-delete cleanup policy, and runtime state rendering moved to `deferred-features.md`. |
| `2026-05-13-phase-05-live-docker-validation-blocked.md` | dropped | Blocked validation run commands are stale operational evidence; Docker/Podman validation expectation is covered by existing knowledge and current validation policy. |
| `2026-05-13-shell-nav-policy-hardening.md` | migrated | Shared top-nav policy moved to `web.md` and `horizon-ideas.md`; recent implementation already addressed the active behavior. |
| `2026-05-15-project-workspace-lease-deferred.md` | migrated | Job workspace lease/read lease boundaries moved to `services.md` and `deferred-features.md`. |
| `2026-05-15-workflow-v2-followups.md` | migrated | Graph composer HTMX routing, typed adapter-port UX, and deterministic Playwright fixtures moved to `deferred-features.md`. |
| `2026-05-20-session-file-explorer-followups.md` | migrated | Chat file metadata indexing moved to `deferred-features.md`. |
| `2026-05-21-services-ux-review-queue.md` | migrated | Follow-up services/frontend/integration review moved to `horizon-ideas.md`. |
| `2026-05-22-avatar-dashboard-ui-style-guidelines.md` | migrated | Durable Avatar dashboard visual/interaction contract moved to `web.md` and `simplypages.md`. |
| `future_features.md` | migrated | Product capability sections moved to `deferred-features.md`. |
| `simplypages-upstream-module-candidates.md` | migrated | Plausible upstream module candidates moved to `horizon-ideas.md`; Magenta-specific SimplyPages contract moved to `simplypages.md`. |
| archived note files | dropped | Archived note material is inactive historical material. Already-promoted ideas are represented in `horizon-ideas.md` where still useful. |
