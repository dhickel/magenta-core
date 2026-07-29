# Phase 08 — Console Integration and Closeout

## Context

Prior phases supply passed domain contracts. This phase composes their console surfaces, corrects integration defects within its ledger-owned seam, and reconciles validated behavior with documentation. It creates no authority, lifecycle store, workspace kind, transport, or compatibility write path.

## Goal

audit and integrate passed domains without creating new authority

## In Scope

**Write Targets**: src/main/java/io/mindspice/magenta2/api/web/ProjectConsoleController.java, src/main/java/io/mindspice/magenta2/api/web/ProjectConsoleFragments.java, src/main/java/io/mindspice/magenta2/api/web/AppNavigation.java, src/main/resources/static/css/orchestration.css, src/main/resources/static/js/orchestration/projects.js, docs/end-user/projects-and-workspaces.md, docs/end-user/plans-and-tasks.md, docs/end-user/jobs.md, docs/end-user/schedules-and-reactions.md, docs/end-user/chat.md, docs/technical/architecture.md, docs/technical/data-model.md, docs/technical/services.md, docs/technical/security.md, docs/technical/frontend-htmx.md, docs/technical/api-reference.md, docs/api/schedules-reactions.md, .internal-dev/specifications/architecture.md, .internal-dev/specifications/service-graph.md, .internal-dev/specifications/services.md, .internal-dev/specifications/api.md, .internal-dev/specifications/web.md, .internal-dev/specifications/simplypages.md, .internal-dev/specifications/decisions.md

- Integrate only passed Queue, Task, Job, Files, Outputs, Automation, Events, and project-chat surfaces through the console seam.
- Repair only ledger-targeted controller, fragment, navigation, CSS, JavaScript, and documentation/specification defects.
- Produce integrated evidence for existing domain contracts; a failed domain contract is not permission to create a console-specific exception.

## Out of Scope

New runtime families; project workspace authority; multi-user authorization; running reassignment; automatic scheduling; broad controller rewrites; migrations or schema changes; additional JavaScript transports; changes to predecessor phase files; and deferring a known delivered-path defect.

## Dependencies

phase-07-project-chat.md

## Constraints

- Preserve the settled ownership of assignments, native runs, Task attempts, automation revisions, chat conversations, materialization generations, and outputs. No console code may create a second authority, compatibility dual write, whole-row assignment update, optional scope, or page-local authorization exception.
- Keep sensitive-entry capability enforcement in the owning services. Gate-disabled, unauthorized, missing, cross-scope, archived, removed-member, unavailable, stale, leased, fenced, or invalid-lifecycle states expose no active control or sensitive content.
- Use server-owned mandatory scopes, authorized redacted DTOs, bounded snapshots, canonical re-resolution, and exact durable links. Never derive identity, membership, totals, transcripts, runs, outputs, or generation bindings from caller input, filesystem scans, paths, names, timestamps, or historical membership.
- Standard controls remain HTMX. `project-chat.js` remains the sole project JavaScript transport, limited to bounded safe SSE parsing/rendering, cancellation, autoscroll, identity checks, inert text insertion, and safe host-replacement reset.
- Archive and member-removal guards reject operations that would strand active assignments, designated Task attempts, Job runs, enabled automation, provisioning materializations, in-flight turns, or live proposals while preserving authorized historical records.
- Do not initialize, repair, or create `.internal-dev` artifacts when its store is unavailable.

## Preconditions and Execution Order

- Begin only after `phase-07-project-chat.md` has PASS. Earlier phase passes are required transitively by the orchestration's ordered waves.
- Execute subphases A through F in order with exactly one implementation agent. They form one phase and do not have independent validation or exit gates.
- Run the phase-level Validation only after every subphase is complete. Phase completion requires the orchestration post-phase validator review-and-repair PASS; final integration remains governed by the orchestration after all phases PASS.
- Read the required guides before the related work. For each UI change, inspect the relevant SimplyPages documentation and matching demo before editing; if configured documentation is unavailable, stop the UI edit rather than introducing raw-markup or broad-JavaScript substitutes.

## Implementation Steps

### A. Consolidate navigation and host registration

1. Make `AppNavigation` and the console registry the single source for the exact grouped order, deep-link mapping, host IDs, active state, and availability of Overview/Dashboard; Work/Queue, Tasks, Jobs, Outputs; Context/Files, Chat; Automation/Schedules, Events; and Administration/Members, Settings.
2. Make every registered item resolve an authorized, project-scoped stable host. Preserve each route's owner and service call; registration selects and renders the surface but does not recreate its data query.
3. For unavailable, gate-disabled, archived, or otherwise disallowed items, render the existing redacted disabled or unavailable state. Do not omit required historical read behavior, present false zero facts, or leave an actionable link/control behind.
4. Verify deep-link and active-tab resolution cannot select a host outside the requested project scope or bypass item availability.

### B. Repair console composition boundaries

1. Audit `ProjectConsoleController` and `ProjectConsoleFragments` for duplicate IDs, stale broad HTMX targets, incorrect host replacement, and OOB swaps that cross an owning domain boundary.
2. Give each domain its own stable host and restrict replacement to that host. Keep OOB swaps inside the owning domain; re-resolve displayed resources from canonical scoped rows before rendering actions or inspectors.
3. Ensure Dashboard uses bounded, authorized redacted read models. Render unavailable or absent facts explicitly rather than substituting a zero, guessed relationship, or filesystem-derived aggregate.
4. Preserve server-rendered SimplyPages component/module patterns and slot-key requirements when sharing render objects. Do not substitute ad-hoc raw markup when an established component or documented pattern applies.

### C. Audit integrated work identity and exact-link displays

1. Exercise Task, Job, schedule, and reaction submission through their existing console hosts and confirm displayed assignments preserve immutable source, origin, project attribution, selected workspace/context binding, and canonical status.
2. Confirm agent and project Queue overlap presents identical assignment identity and canonical status while retaining mandatory scope at list, cursor, action, deep-link, and inspector boundaries.
3. Confirm Task designated-attempt history, Job run history, and queued reassignment predecessor/successor lineage use durable links. Reassignment remains unavailable unless queued, unleased, and without an allocated native run; the console must not suggest running transfer or predecessor artifact copying.
4. Confirm transcripts, diagnostics, output metadata, preview, and download resolve only through exact persisted assignment/conversation/run/attempt/lineage/publication links. Do not introduce time-window, title, timestamp, path, or name inference.

### D. Audit guards and failure representations across hosts

1. Trace archive and member-removal behavior across Queue, Tasks, Jobs, Files, Outputs, Schedules, Events, and Chat. New work, controls, automation activity, materialization, turns, and proposals must fail closed while authorized historical access stays redacted and exact-link based.
2. Trace capability, scope, membership, operation-key/digest, expected-revision/CAS, lease, and writer-fence failures to existing service results. Render safe statuses without leaking raw input/output, evidence, internal paths, lease owners, fences, checkpoints, secrets, or unrestricted errors.
3. Confirm Files remain exact-generation, read-only, confined, and non-creating; Outputs use the same durable-link authorization for list, metadata, preview, and download; neither surface scans a workspace or treats project text as authority.
4. Confirm automation screens retain revision-head semantics, mutually exclusive `CRON`/`ONE_TIME` modes, auditable failures, and pure simulation. Confirm project chat retains immutable project/member-agent/generation identity, bounded memory, serialized idempotent turns, deny-by-default tools, proposal target verification, safe SSE host replacement, and generic-chat route exclusion.
5. If evidence exposes a defect owned by a domain file outside the ledger targets, record the contradictory evidence and escalate under the governing workflow. Do not conceal it with a console workaround or edit outside this phase's targets.

### E. Polish the operational console without changing transport ownership

1. Use the established dense operational-console visual language: compact controls, thin blue-gray borders, small radii, semantic chips, coherent grid spacing, and useful first-viewport information on desktop and mobile.
2. Correct CSS and `projects.js` only as needed for host isolation, responsive layout, focus visibility, keyboard navigation, ARIA/status semantics, loading behavior, wrapping, overflow, empty states, error states, and disabled states.
3. Keep standard CRUD, filtering, row actions, form submissions, and partial refreshes HTMX-driven. Remove accidental fetch-based transport or unsafe HTML insertion. Do not expand `projects.js` into a general transport layer.
4. Preserve `project-chat.js` ownership and constraints; do not duplicate its SSE handling in `projects.js`.

### F. Reconcile validated documentation and closeout records

1. Reconcile each listed end-user document with actually passed behavior for projects/workspaces, Tasks/Plans, Jobs, Schedules/Reactions, and Chat. State disabled, archived, and historical-access behavior where it materially affects use.
2. Reconcile listed technical and API documents with validated authority boundaries, durable links, gates, project attribution, HTMX host isolation, and the absence of new console-owned authority. Do not document unimplemented behavior or convert an assumption into a contract.
3. If the `.internal-dev` store is already ready, follow its guides and update only the listed relevant specifications with validated intended contracts; add a decision only for a genuine durable decision with rationale and tradeoffs. Complete required ready-store changelog/archive closeout without creating or repairing the store.
4. If the store is not ready, do not initialize it, create artifacts, or claim records were written. Retain implementation and documentation evidence needed for ordinary project closeout.
5. Inspect the final diff for accidental authority changes, whole-row writes, duplicate authorities, optional scope, unbounded queries, sensitive DTOs, unauthorized capability paths, unjustified JavaScript, placeholders, unrelated API growth, and edits outside the ledger targets.

## Required Guides

- `AGENTS.md`
- `src/main/java/io/mindspice/magenta2/api/web/AGENTS.md`
- `docs/AGENTS.md`
- `.internal-dev/AGENTS.md` when the store is ready
- `.internal-dev/specifications/AGENTS.md` when the store is ready
- `.internal-dev/specifications/web.md` when the store is ready
- `.internal-dev/specifications/simplypages.md` when the store is ready
- `.internal-dev/knowledge/live-chat-mcp-workflow-testing.md` when the store is ready and live-chat/browser validation applies
- `.internal-dev/knowledge/orchestration-ui-validation.md` when the store is ready and UI validation applies

## Technical Guidance

Treat an integration failure as evidence against an owning contract. Repair only the ledger-owned console seam when the defect is genuinely composition, navigation, fragment, styling, JavaScript, or documentation behavior. A defect requiring a domain-service, repository, schema, or contract change outside the ledger targets must be escalated; it must not be hidden by a page-level exception.

Use authorized redacted DTOs and existing service seams. Do not add direct repository reads to make a dashboard, tab, or inspector convenient. Keep renderers stateless; preserve stable hosts; and make deep links re-resolve canonical scoped resources rather than trusting prior list state.

## Validation

After A through F are complete, perform one consolidated phase-level validation:

1. Re-run focused validations for every touched shared console, navigation, fragment, CSS, JavaScript, and documentation path, including enabled and gate-disabled behavior.
2. Run the clean and supported-warm migration matrix through `019`. Verify fresh/warm metadata equivalence, migration ordering, checksum behavior, repeated application, partial-structure rejection, foreign-key/index and revision-shape integrity, and historical-link reachability. Do not accept a schema or migration defect as a console-only issue.
3. Run applicable focused tests, relevant broader Maven/build/static checks, and bounded Spring Boot startup with project/automation capability configurations disabled and enabled. Diagnose and repair failures attributable to this phase; escalate an out-of-target owner defect with evidence.
4. Delegate live-app Playwright validation to a subagent using the required live-chat workflow. Cover every grouped tab and deep link; Queue filters/actions and inspectors; Task attempts and promotion displays; Job runs and reassignment lineage; exact-generation Files; durable-link Outputs including bytes/metadata/preview/download; Schedules, Reactions, and Events; archive/member-removal and disabled/error/empty states; and restricted Chat streaming, cancellation, replacement, proposal handling, and generic-route exclusion.
5. Capture desktop and mobile screenshots of changed surfaces and provide a written visual critique covering alignment, density, hierarchy, available-space use, first-viewport usefulness, gaps, control affordance, wrapping, overflow, mobile stacking, focus/keyboard behavior, and ARIA/status behavior. Verify standard interactions use HTMX and no unjustified JavaScript transport or unsafe HTML rendering remains.
6. Review migration links/checksums, documentation/specification accuracy, ready-store closeout status when applicable, and the final diff. Obtain the orchestration post-phase validator review-and-repair PASS.

## Exit Criteria

- Every A–F subphase is complete and the post-phase validator review-and-repair PASS is recorded.
- Grouped tabs, deep links, active states, availability states, and stable hosts are truthful, project-scoped, isolated, responsive, and accessible.
- Integrated evidence proves Task, Job, schedule, reaction, Queue, Files, Outputs, Automation, Events, and Chat preserve their existing authority, exact-link, lifecycle, archive/member-removal, capability, scope, membership, idempotency, CAS, lease/fence, generation, and generic-chat-exclusion safeguards.
- No console path creates an authority, performs a whole-row assignment write, widens scope, infers identity from filesystem/name/timestamp data, exposes sensitive content, adds an unauthorized capability path, or introduces unjustified JavaScript transport.
- Required migrations, checks, startup configurations, delegated Playwright evidence, visual critique, documentation/specification reconciliation, and conditional ready-store closeout pass. Any evidenced out-of-target contract conflict is escalated rather than masked or declared complete.
