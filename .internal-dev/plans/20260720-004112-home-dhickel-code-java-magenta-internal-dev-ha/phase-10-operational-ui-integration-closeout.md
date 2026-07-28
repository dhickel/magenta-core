# Phase 10 — Cross-Domain Operational Integration and Closeout

## Context

Artifact, Skills, runtime, and automation phases own their focused fragments. This phase composes run metadata with the Files surface, aligns navigation and styling, reconciles contracts and documentation, and proves complete repository behavior without reopening those domain implementations.

## Goal

compose the final cross-domain surface and complete repository closeout

## In Scope

**Write Targets**: src/main/java/io/mindspice/magenta2/api/web/RunOperationsFragments.java, src/main/java/io/mindspice/magenta2/api/web/OrchestrationController.java, src/main/java/io/mindspice/magenta2/api/web/WorkspaceExplorerFragments.java, src/main/resources/static/css/orchestration.css, .internal-dev/specifications, .internal-dev/knowledge, .internal-dev/changelogs, docs

- Add the thin run-operations adapter and reusable task-group/run-detail fragments needed for Files composition.
- Compose already-owned artifact, Skills, health, schedule, and reaction fragments without moving persistence access into explorer services.
- Update navigation, shared styling, specifications, package guides, user/technical/API docs, knowledge, changelog, and archived workflow records.
- Run complete migration, execution, recovery, startup, browser, and final-diff validation.

## Out of Scope

- Reimplementing focused domain fragments owned by Phases 05, 07, 08, and 09.
- Unrelated dashboard redesign, generic user-workspace browsing, new project roots, rich binary preview, marketplace UI, or distributed scheduler administration.
- Moving legacy output files.
- Adding a workspace kind, task-run workspace descriptor, project filesystem root, generic workspace identity, or path-derived identity.

## Dependencies

phase-05-artifact-access-search-retention.md, phase-07-skill-trust-snapshots-packages.md, phase-08-runtime-summaries-measurements.md, phase-09-schedule-reaction-hardening.md

## Constraints

- Start only after every listed dependency has passed its post-phase validator review-and-repair gate.
- Use exactly one implementation agent for this phase; final validation remains assigned to the required validator.
- `WorkspaceExplorerService` remains filesystem-only. Persistence-backed run, artifact, health, skill, schedule, and reaction data is composed through separate services and reusable fragments.
- Scope and authorize requests before querying or rendering persistence-backed data. Never expose claim secrets, writer fences, absolute paths, unauthorized counts, snippets, rows, lineage, metadata, links, or bytes.
- Standard CRUD, filtering, expansion, pagination, and partial refreshes use HTMX and OOB swaps. JavaScript is allowed only for a narrowly documented interaction where it is demonstrably simpler.
- Preserve the dense operational-console language: compact controls, thin blue-gray borders, small radii, semantic chips, a useful first viewport, and coherent mobile stacking.
- The UI must not imply that simulation executed, legacy or unavailable history is complete, integrity-failed bytes are accessible, executable skills are safe when confinement is unavailable, or unsupported FTS5/project-root capabilities exist.
- Preserve no-replace publication, integrity suppression, retention safety, reserved-tree confinement, immutable skill snapshots, revocation, scheduler/reaction idempotency, and all prior ownership boundaries.
- Do not persist absolute paths, reconstruct workspace identity from `Path`, move legacy outputs, create absent run/skill roots during startup or reads, or infer missing historical facts.
- Preserve unrelated edits and compatibility outside this signed handoff.

## Implementation Steps

1. Reinspect the required SimplyPages docs/demos and existing Files/dashboard composition patterns before editing. Reuse row/column, slot-key, list/detail, modal, and OOB patterns; add no broad transport JavaScript.
2. Add a thin run-operations controller/adapter and `RunOperationsFragments` for bounded task groups, run expansion, status/outcome, timestamps, active/wall elapsed, attempts/claims, retry/replay lineage, artifact integrity/count, and recent outputs. Consume Phase 08 summaries and measurements rather than deriving unavailable facts in the UI.
3. Render explicit empty, unavailable, legacy, missing, integrity-failed, and partially reconciled states. Scope and authorize requests before querying, and expose no claim secrets, writer fences, absolute paths, or unauthorized aggregate information.
4. Compose run metadata adjacent to the agent Files tab without injecting repositories or persistence-backed queries into `WorkspaceExplorerService` or `WorkspaceExplorerFragments`. Keep visible task directories browsable but read-only and load persistence panels independently.
5. Mount the focused artifact, Skills, runtime, schedule, and reaction fragments at stable hosts. Ensure filters, expansion, and pagination preserve authorized scope and mutation responses refresh only affected hosts through HTMX/OOB swaps.
6. Update navigation and shared CSS only as needed. Any JavaScript requires a written path-of-least-resistance justification and remains local to the specific interaction.
7. Update architecture, service graph, services, API, web, SimplyPages, and decisions specifications to implemented truth, including the verified project-root contract or disabled state, deployed FTS5 capability or unavailable state, and process-confinement outcome with its fail-closed executable fallback.
8. Update affected package guides and end-user, technical, API, security, data-model, workspace/output, Skills, orchestration, frontend, and operations documentation plus indexes. Document routes, payloads, fragment hosts, scope rules, unavailable states, and operator recovery behavior that actually exist.
9. Create focused knowledge for task-run/artifact lifecycle, scoped skill trust/snapshots, and schedule/reaction idempotency. Add the final changelog, archive completed plan/bug artifacts as required, check related GitHub issue state where applicable, and preserve unrelated records.
10. Validate fresh and representative prior databases. Confirm ordered migrations are deterministic and restart-safe, `schema.sql` matches the migrated end state, checksum drift fails closed, old readers do not write obsolete structures, and startup or reads do not create absent run/skill roots.
11. Run focused and full automated tests, bounded startup, and real task/workflow/job execution covering creation, skill activation, publication/search/preview, interruption/resume/replay, reconciliation, retention safety, schedule firing, and reaction dispatch. Exercise both supported and fail-closed capability paths; do not replace blocked real-execution evidence with unit-only evidence.
12. Delegate Playwright to the required test subagent while the application runs. Capture desktop/mobile screenshots for Files/runs, artifacts, Skills, health/history, schedules, and reactions; verify HTMX/OOB behavior, authorization-safe states, and backend-observable results.
13. Critique and repair alignment, spacing, gutters, density, hierarchy, affordances, wrapping, overflow, first-viewport utility, mobile stacking, modals, and consistency with dashboard/agent consoles. Re-run affected browser checks after repairs.
14. Review the final diff for accidental API growth, duplicate boundaries, placeholders, broad JavaScript, absolute persisted paths, path-derived identity, accidental workspace kinds, moved legacy bytes, stale docs, untrusted prompt leakage, no-replace violations, weakened confinement, duplicate schema ownership, missing internal records, and unreported blockers. Complete post-phase validator review-and-repair, run final integration validation, and commit only after both report PASS.

## Invariants and Edge Cases

- A task run remains a child resource inside an effective `AGENT` or `USER` workspace. `CHAT` remains non-task-selectable, and chat artifacts retain their existing non-task output location.
- Filesystem identity is always workspace identity plus root-relative path. UI links and request parameters never carry trusted absolute locations or reconstruct workspace identity from a path.
- Run/task groups are bounded and deterministic. Duplicate display names, renamed or deleted definitions, Unicode/case variation, and legacy rows cannot merge distinct canonical identities.
- Active, unavailable, legacy, missing, integrity-failed, and partially reconciled records retain distinct states. Missing values remain unavailable rather than becoming zero, success, healthy, or complete.
- Retry and replay show typed parent lineage and new run identities; resume shows a new attempt on the same run. Internal retries do not appear as unrelated operator runs.
- Final artifact placement remains create-only. Integrity failure suppresses preview and download, and authorization is rechecked when bytes are opened or streamed.
- Search does not reveal unauthorized counts, snippets, rows, lineage, metadata, links, or bytes. Unsupported FTS5 is shown as unavailable unless an approved bounded fallback was actually implemented.
- Pending, untrusted, changed-digest, or revoked skills disclose no candidate-derived model content. Executable authorization remains separate from instruction approval and activation.
- Simulation is visually and semantically distinct from execution and causes no tools, artifacts, external mutation, or execution-like firing row.
- Retention cannot remove active attempts, claims, downloads, references, lineage pins, legal pins, or unresolved reconciliation state.
- HTMX responses update only their stable authorized hosts; stale responses must not overwrite unrelated domain panels or broaden scope.
- Desktop and mobile views preserve readable hierarchy without clipped controls, stranded columns, excessive dead zones, overlap, or hidden critical status.

## Required Guides

- `AGENTS.md`
- `.internal-dev/AGENTS.md`
- `.internal-dev/specifications/AGENTS.md`
- `.internal-dev/specifications/web.md`
- `.internal-dev/specifications/simplypages.md`
- API/web and orchestration/workspace/skills package guides
- Agent Skills UI, Files explorer, orchestration UI, and live-chat Playwright knowledge files
- SimplyPages dynamic SlotKey/RenderContext, editing-system, HTMX endpoint/swap, component/module, and relevant demo documentation

## Technical Guidance

Use independent, stable hosts such as `#agent-run-groups`, `#artifact-results`, `#artifact-preview`, `#skill-trust-panel`, `#runtime-health`, and `#automation-audit`. Domain fragments remain owned by their implementation phases; this phase supplies only the thin composition boundary.

A host request should carry an authorized domain scope and bounded page/filter inputs, then delegate to the owning service or fragment:

```java
@GetMapping("/agents/{agentId}/runs")
Component runs(@PathVariable long agentId, RunPageRequest page) {
    var scope = access.requireAgentScope(agentId);
    return runOperationsFragments.groups(runOperations.query(scope, page.bounded()));
}
```

Treat the sketch as shape guidance only: use established repository types and route conventions, and do not add a parallel scope abstraction if one already exists. OOB mutation responses should return only the hosts whose backend state changed.

## Validation

- Through the required `gpt-5.3-codex` medium-reasoning test subagent, run focused run/Files controller and fragment tests plus relevant service/repository tests and the full Maven suite.
- Validate fresh and representative existing databases, migration reruns, checksum drift, interrupted recovery, supported/unavailable FTS5 paths, and `schema.sql` equivalence.
- Run bounded Spring startup and integrated real task/workflow/job execution and recovery, including authorization, integrity, no-replace publication, retention, skill trust/revocation, schedule claims, reaction causation, and simulation side-effect checks.
- Run focused desktop/mobile Playwright while the application is running, with screenshots and explicit visual critique for Files/runs, outputs/search/preview, Skills/trust, health/history, schedules, and reactions.
- Verify ordinary interactions are HTMX-first, OOB swaps affect only intended hosts, any JavaScript has a narrow recorded justification, and every visible claim matches backend state.
- Review the resulting diff and validation evidence with `openai-codex/gpt-5.6-sol` at medium reasoning; repair defects and require post-phase PASS.
- After post-phase PASS, run the orchestration's final integration validation with `openai-codex/gpt-5.6-sol` at medium reasoning and require PASS.
- If a required real-execution or browser dependency is blocked, follow repository blocker policy, report it explicitly, and do not mark the phase complete or substitute unit-only evidence.

## Exit Criteria

- The signed-handoff capabilities work end to end. Repository-verification fallbacks for absent project-root authority, unavailable FTS5, or unproven process confinement fail closed and are represented truthfully.
- Operational surfaces are reusable, authorized, bounded, truthful, HTMX-first, responsive, and coherent; persistence-backed composition does not enter filesystem explorer services.
- Specifications, docs, package guides, knowledge, changelog, and archived records match implemented behavior and preserve unrelated records.
- Fresh and existing migrations, focused and full automated tests, bounded startup, real execution, security/recovery checks, and desktop/mobile Playwright checks pass with no acknowledged defect in the implemented path.
- Final diff review finds no placeholders, speculative APIs, absolute persisted paths, path-derived identity, accidental workspace kinds, moved legacy files, untrusted prompt leakage, duplicate schema ownership, weakened confinement, no-replace violations, stale contracts, or unreported validation blockers.
- Post-phase validator review-and-repair reports PASS, then final integration validation reports PASS.
