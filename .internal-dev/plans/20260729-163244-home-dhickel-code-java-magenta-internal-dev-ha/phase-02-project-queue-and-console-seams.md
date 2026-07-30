# Phase 02 — Project Queue and Console Seams

## Context

Phase 01 supplies the singular assignment authority, immutable attribution, server-created `AgentScope`/`ProjectScope`, bounded snapshot pagination, redacted summaries, exact persisted conversation links, project capability/archive decisions, and fenced controls. This phase exposes only those proven seams through the project console and demonstrates that agent and project Queue views are different mandatory scopes over the same canonical assignment projection.

## Goal

deliver the grouped shell and Queue proof over the shared projection

## In Scope

**Write Targets**: src/main/java/io/mindspice/magenta2/api/web/OrchestrationController.java, src/main/java/io/mindspice/magenta2/api/web/ProjectConsoleController.java, src/main/java/io/mindspice/magenta2/api/web/ProjectConsoleFragments.java, src/main/java/io/mindspice/magenta2/api/web/ProjectWorkController.java, src/main/resources/static/css/orchestration.css, src/main/resources/static/js/orchestration/projects.js

Extract the project console seam; define the grouped console shell and its controlled unavailable states; add Queue read, inspect, exact-link transcript/diagnostic, priority, and cancellation adapters over Phase 01; and replace agent raw Queue rendering with the same authorized summary contract under `AgentScope`. Focused tests and live-app proof cover this seam; documentation integration remains Phase 08 work.

## Out of Scope

Work Task, Job, reassignment, materialization/template, Files, Outputs, automation, events, and project-chat behavior; project workspace authority; new assignment/query/control authority; broad dashboard or unrelated orchestration-controller rewrites; new JavaScript transport; and documentation changes.

## Dependencies

phase-01-authority-and-assignment-foundation.md

## Orchestration Alignment

- Execute only after Phase 01 has passed its post-phase validator gate.
- Exactly one implementation agent owns every implementation step below. No lettered subphases are required: the six-target controller/fragment/CSS/limited-JS seam is coherent and bounded enough for one agent session.
- Complete all implementation steps before applying the single phase-level Validation and Exit Criteria. A post-phase validator review-and-repair PASS is required before Phase 03 begins.
- Treat a missing Phase 01 scoped, redacted, exact-link, or control seam as a contract escalation for that capability. Do not create a parallel query, authorization, transcript, or lifecycle path to compensate.

## Constraints

- `WorkAssignment` remains the queue and lifecycle authority. This phase neither writes assignment rows directly nor introduces a project-specific queue, state, cursor, or summary model.
- Resolve the requested project once through the Phase 01 project/access path into a server-created `ProjectScope`; resolve agent Queue requests into a server-created `AgentScope`. Pass the sealed scope to every count, snapshot, row re-resolution, transcript/diagnostic read, and control. Path values, filters, cursor contents, form bodies, historical membership, assignment names, and client-supplied project identifiers are not authority.
- Queue rendering consumes only Phase 01 authorized, redacted summary/detail DTOs. Do not serialize or render raw input/output, evidence, unrestricted failure text, internal paths, runtime maps, workspace data, lease owners, fences, checkpoints, or secrets.
- Active and history lists use the Phase 01 deterministic filter and authenticated opaque bounded snapshot cursor contract. A malformed cursor is a `400`; a stale/expired or semantics-conflicting snapshot is a `409` Queue status fragment with refresh guidance. It does not replace the shell.
- Project attribution is immutable. A project Queue can contain only rows whose canonical persisted attribution matches the resolved `ProjectScope`; assignment ID, cursor, inspector link, transcript link, or diagnostic link cannot widen that scope.
- Transcript and diagnostics reads use only exact persisted assignment links and Phase 01 authorized readers. If the applicable exact link or authorized diagnostic is absent, show the controlled absent/unavailable state; never infer a match from time, title, agent, filesystem, raw runtime data, or current/historical membership.
- The project-operation/caller-binding capability decision is enforced by every sensitive route through Phase 01 services. Disabled capability returns the controlled `503` outcome without content leakage. Resource scope alone is not authorization.
- Archived projects retain only capability-gated historical reads. Archive disables Queue controls and no UI state implies otherwise. Service enforcement remains mandatory even when controls are omitted.
- Priority is available only for Phase 01-eligible queued, nonterminal, unleased rows with the expected assignment row version. Cancellation follows the established direct-cancel or cancellation-request lifecycle and never bypasses writer fencing. Same operation key and canonical request semantics converge; changed semantics conflict.
- HTMX is the standard interaction path. Queue list, inspector, transcript, and status are stable independent hosts; OOB swaps, if needed, stay inside the Queue domain. A control or cursor response must not replace the grouped shell or unrelated console host.
- Use SimplyPages components and established render patterns. If the required SimplyPages documentation or matching demo is unavailable, stop the UI edit rather than substituting raw markup or broad JavaScript.

## Required Guides

- `AGENTS.md`
- `src/main/java/io/mindspice/magenta2/api/web/AGENTS.md`
- `.internal-dev/specifications/web.md`
- `.internal-dev/specifications/simplypages.md`
- `.internal-dev/knowledge/agent-lifecycle-htmx-targets.md`
- `.internal-dev/knowledge/htmx-fragment-error-statuses.md`

## Implementation Steps

1. Inspect the required SimplyPages documentation and matching demos for HTMX tabs, master/detail, stable hosts, OOB behavior, responsive rows, and error fragments before UI edits. Inspect the completed Phase 01 public controller/service contracts and existing agent/project routes to identify the authorized scoped query, exact-link read, control, cursor, and error-result seams. Use those seams as-is; record and escalate any missing required contract rather than creating a local substitute.

2. Extract only the project chooser, grouped shell, project-only route helpers, and route-compatible fragments from `OrchestrationController` into `ProjectConsoleController` and `ProjectConsoleFragments`. Preserve existing non-project orchestration behavior and route compatibility. Keep controllers thin: parse transport input, obtain the server-owned scope through the established access service, delegate to the appropriate Phase 01 service, and map its explicit result. Do not move repository access, lifecycle policy, or authorization decisions into web code.

3. Define one `ProjectTab` registry as the sole source for group, label, key, route, host ID, active state, and availability. It must express the final groups `Overview/Dashboard`, `Work/Queue, Tasks, Jobs, Outputs`, `Context/Files, Chat`, `Automation/Schedules, Events`, and `Administration/Members, Settings`. In this phase Queue is the only newly functional work tab. Every domain not owned here resolves to an explicit controlled unavailable fragment within the normal shell; it has no speculative API, mutation control, or client-side approximation. Use progressive links and HTMX navigation with history support so direct links select the same canonical registry entry.

4. Add the project Queue adapters in `ProjectWorkController` over Phase 01 services: bounded active/history list, scoped detail, exact-link transcript, authorized diagnostic, priority, and cancellation. Validate syntactically malformed request input as `400`; map scoped absence, cross-scope assignment references, and unavailable exact links to the established non-leaking `404`/absent fragment; map lifecycle/version/operation-digest conflicts to `409`; and map disabled sensitive capability to the controlled `503` fragment. Resolve project capability, archive/read state, immutable project attribution, and structural scope before computing counts or returning any Queue content. Preserve capability-gated historical reads for an archived project while rejecting controls.

5. Render bounded active/history Queue rows through the Phase 01 summary contract and keep separate stable hosts for Queue list, inspector, transcript, and status. Carry only the opaque cursor and server-defined filter semantics between requests. Render empty, unavailable, absent-link, disabled, and stale-snapshot states as local fragments. On stale snapshots, offer a fresh Queue request and retain the grouped shell, selected tab, and unaffected hosts. Do not disclose whether a cross-scope row, transcript, or diagnostic exists.

6. Re-resolve the canonical row with the same `ProjectScope` for every inspector, transcript, and diagnostic request; a list-row payload is never sufficient authority. Read transcript only through the row’s exact persisted conversation link and diagnostics only through the established authorized assignment diagnostic seam. Use detail-specific redacted DTOs where Phase 01 provides them, preserve bounded reads, and render no raw runtime, lease, fence, workspace, path, input, output, evidence, or unrestricted error field.

7. Wire HTMX priority and cancellation forms with the server-bound operation key and expected assignment version supplied by the authorized Queue DTO. Submit only to the scoped control adapters. On success, refresh only the affected Queue list/inspector/status hosts according to the control result; on `409`, render local stale/conflict guidance; on capability/archive/lifecycle denial, render a local disabled/unavailable state without active controls. Replace agent raw Queue rendering in `OrchestrationController` with the exact same summary/row renderer and query contract under `AgentScope`. The proof requires overlapping agent/project Queue rows to retain identical assignment identity and canonical status while scope-specific navigation remains sealed.

8. Keep `projects.js` limited to selected-tab presentation and browser-history coordination around server-rendered/HTMX navigation. It must not fetch Queue data, submit controls, parse sensitive data, decide availability, or authorize access. Add or extend focused controller/projection/integration coverage for hostile scope, cursor, resource-ID, and deep-link inputs; redaction; exact-link transcript/diagnostic absence; gate-disabled and archived behavior; operation replay, changed-digest, version-stale, and lifecycle conflicts; independent host targeting; and identical overlapping agent/project assignment identity and status. Exercise the rendered Queue through the live application rather than relying on markup-only assertions.

## Technical Guidance

Keep REST and HTML adapters transport-specific but make both consume the same Phase 01 authorized DTO/result contract. The tab registry may be a small record such as:

```java
record ProjectTab(String group, String key, String label, String route, String hostId, Availability availability) {}
```

`Availability` is server-derived from phase ownership and authorized capability state; it is not a client hint. Keep presentation state in the shell/registry and assignment state in Phase 01 services. Prefer existing SimplyPages composition and slot/host patterns over raw strings. Use CSS only for the dense operational-console treatment: compact controls, thin blue-gray boundaries, small radii, semantic status chips, responsive Queue row stacking, and visibly distinct disabled/unavailable states.

## Validation

After all implementation steps are complete, run the focused project and agent controller/projection/integration tests and verify at least:

- the same overlapping assignment has the same canonical identity and status in `AgentScope` and `ProjectScope`, while each scope rejects IDs, cursors, filters, and deep links that try to widen it;
- active/history ordering and bounded snapshot traversal are deterministic; malformed cursors return `400`; stale/expired or changed-semantics snapshots return local `409` refresh guidance without shell replacement;
- list/detail/inspector/transcript/diagnostic DTOs and fragments exclude restricted fields, and missing exact links neither leak existence nor trigger inferred lookup;
- gate-disabled routes return controlled `503` results, archived projects allow only permitted historical reads, and archive/capability/lifecycle states expose no working control path;
- priority and cancellation preserve Phase 01 operation-key, digest, expected-version, lifecycle, lease, and fencing contracts, including replay convergence and semantic/version conflicts;
- Queue HTMX responses target only their intended stable hosts, direct links select the correct tab, and unavailable later-domain tabs are controlled non-functional fragments.

Run relevant Maven checks and static checks, then bounded Spring Boot startup with the applicable enabled and disabled capability configurations. Run live-app Playwright validation on a subagent against the changed surfaces. Capture desktop and mobile screenshots for active Queue, history, empty, unavailable, transcript-unavailable, stale/conflict, disabled control, and deep-link states; review functional controls, host isolation, visual density, hierarchy, alignment, wrapping, overflow, responsive stacking, first-viewport usefulness, keyboard/focus/ARIA behavior, and confirmation that JavaScript stayed within the stated narrow role. Complete the orchestration post-phase validator review-and-repair and obtain PASS.

## Exit Criteria

All eight implementation steps are complete before these criteria are evaluated. The project seam is extracted without an unrelated controller rewrite; one grouped registry drives shell, deep-link, active-tab, and unavailable-tab behavior; and only Queue receives functional project adapters in this phase. Every Queue route uses a server-created sealed scope and Phase 01 authorized/redacted services; no view can widen scope or infer transcript/diagnostic linkage. Agent and project Queue projections agree for overlapping assignments while retaining their mandatory scopes. HTMX hosts update independently, controls obey Phase 01 capability/archive/lifecycle/idempotency/fencing contracts, and `projects.js` remains presentation/history-only. Focused tests, relevant checks, bounded startup, live-app Playwright evidence, visual review, and the post-phase validator review-and-repair all PASS.
