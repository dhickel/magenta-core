# Phase 03 — Lightweight Project Tasks

## Context

Phase 03 consumes the passed Phase 01 assignment-authority, capability, operation-key, migration, and scoped-query contracts plus the passed Phase 02 project-console host. It adds a separate lightweight Task definition layer and does not alter compatibility Plan-template behavior at `/api/tasks`.

A Work Task is tracking and submission provenance. It is executable only through one explicitly promoted Plan. The Plan is assignment source; `WORK_TASK/<task-id>` is immutable assignment origin. Assignment lifecycle remains owned by `WorkAssignment`.

## Goal

add lightweight Tasks with explicit Plan promotion and canonical assignment attempts

## In Scope

**Write Targets**: src/main/java/io/mindspice/magenta2/ai/orchestration/tasks/WorkTaskDefinition.java, src/main/java/io/mindspice/magenta2/ai/orchestration/tasks/WorkTaskAttempt.java, src/main/java/io/mindspice/magenta2/ai/orchestration/tasks/WorkTaskRepository.java, src/main/java/io/mindspice/magenta2/ai/orchestration/tasks/WorkTaskService.java, src/main/java/io/mindspice/magenta2/api/web/WorkTaskController.java, src/main/java/io/mindspice/magenta2/api/web/WorkTaskFragments.java, src/main/java/io/mindspice/magenta2/api/web/ProjectConsoleController.java, src/main/resources/db/migrations/015-lightweight-work-tasks.sql, src/main/resources/schema.sql

## Out of Scope

- A Task assignment type, runner, direct model execution, fan-out, automatic member selection, or Task lifecycle/status authority.
- Silent Task-to-Plan conversion, generic existing-Plan attachment, promoted-Plan synchronization, or bidirectional Task/Plan edits.
- Multi-project pinning, workspace authority from a project, native Job/JobRun changes, Phase 04 reassignment behavior, or compatibility changes to `/api/tasks`.
- Broad console integration, raw-markup fallback, new JavaScript transport, and documentation integration owned by Phase 08. Do not widen this phase's canonical write targets.

## Dependencies

phase-02-project-queue-and-console-seams.md

## Constraints

- Run only after the dependency has PASSed. This is Wave 03 with exactly one implementation agent. Subphases A–D are contiguous parts of this one phase: all are mandatory before phase-level Validation and Exit Criteria apply, and none is an independent completion gate. Phase 04 remains blocked until the post-phase validator PASSes.
- Before persistence or submission edits, inspect passed Phase 01 evidence for the assignment field-authority matrix, origin representation, nonterminal status vocabulary, assignment transaction, caller binding, capability decision, canonical project/member guards, and Plan execution seam. Missing or contradictory evidence stops the affected capability; do not add a parallel writer, optional scope, compatibility write, or page-local exception.
- `WorkTaskDefinition` holds title, description, optional project binding, project-local pin, default priority, optional preferred agent, immutable promoted-Plan link, timestamps, and definition version. Visible Task state is only `Task -> designated attempt -> WorkAssignment.status`; neither Task nor attempt copies assignment lifecycle.
- Project binding is zero-or-one and immutable after the first attempt. Pin requires a binding and is presentation-only. A project-bound preferred agent must be a current eligible member of an active project; an unbound preferred agent must satisfy established named-agent eligibility. Preference is not executor fallback.
- Submission requires an explicit eligible executor and uses stored Task binding, never request `projectId`, route text, event payload, filename, or historical membership. Revalidate active project/current member at command time. Archived projects reject Task edits, promotion, submission, and controls.
- A successful submission creates one canonical `WorkAssignment` with source `PLAN/<linked-plan-id>`, origin `WORK_TASK/<task-id>`, copied default priority, and immutable project attribution, then exactly one linked attempt. Later Task default-priority edits and Queue priority remain independent.
- Enforce at most one nonterminal Task-origin assignment through a migration-level uniqueness guard on the canonical Work Assignment origin/status representation proven in Phase 01, and atomically append/designate attempts. Do not use an application-only precheck, mutable Task status, or whole-row assignment update.
- Attempts are append-only exact Task/assignment/order/predecessor links. A retry after terminal state appends one successor; identical operation replay returns its recorded attempt and appends nothing. The designated-attempt pointer changes only atomically with accepted append.
- Promotion creates or records one immutable Plan link. Same key/digest replays; changed digest conflicts; later promotion converges to the existing link. The public Plan service is the only Plan writer. Task edits never update Plan and Plan edits never update Task.
- Every sensitive Task service entry applies the trusted-deployment capability and server-owned canonical scope. DTOs exclude raw input/output, unrestricted errors, paths, leases, checkpoints, secrets, and internal diagnostics. Mutations bind operation key/digest to server-derived caller, action, scope, resource, expected revision, and request semantics.

## Required Guides

- Read `AGENTS.md`, `src/main/java/io/mindspice/magenta2/ai/orchestration/AGENTS.md`, `src/main/java/io/mindspice/magenta2/ai/chat/plan/AGENTS.md`, and `src/main/java/io/mindspice/magenta2/api/web/AGENTS.md` before governed edits.
- Read the passed Phase 01/02 contracts and supplied concepts before selecting schema, Plan writer, or console integration details.
- If an initialized internal-development store exists at implementation time, read applicable task-workflow schema/run-snapshot knowledge and architecture records under its access rules; never initialize or repair it implicitly.
- Before UI work, inspect configured SimplyPages layout/editing/HTMX guidance and matching demos. If unavailable, stop the UI edit rather than using raw HTML or broad JavaScript.

## Implementation Steps

### A. Definition and persistence foundation

1. **Model versioned Task definitions and append-only attempts.** Add `WorkTaskDefinition` and `WorkTaskAttempt` as Tasks-package records. Define current-state reads as a join through the designated attempt to canonical `WorkAssignment.status`; do not persist Task status. Include immutable attempt order, assignment link, retry predecessor, and an immutable one-to-one Plan link. Confirm the existing public Plan creation API and `TASK_TEMPLATE` representation first. If it cannot produce the required minimal template through existing authority, escalate rather than creating a competing Plan writer/type.

2. **Add migration 015 and repository primitives.** Additive tables cover Task definitions, append-only attempts, immutable Plan links, and operation outcomes. Add foreign keys; unique Task/Plan promotion; unique `(task_id, attempt_order)`; unique assignment-to-attempt; designated-attempt integrity; and the active-origin uniqueness guard for `WORK_TASK` assignments over exact Phase 01-proven nonterminal statuses. The guard releases only when canonical assignment state is terminal. Update `schema.sql`; implement bounded deterministic reads and transactional commands. Follow discovered migration-runner conventions and reject partial shape while preserving the immutable earlier baseline.

### B. Definition commands and Plan promotion

3. **Implement guarded Task definition commands.** Implement create, read, bounded global/project list, edit, pin, preferred-agent, and project-binding commands through `WorkTaskService`/repository. Use CAS and persisted outcomes for replayable mutations. Validate definition data; require a binding before pinning; reject archived project controls; validate preferred-agent rules; and reject binding move/copy after any attempt. A different project after history requires explicit creation/copy of a new Task with no attempt history, never historic attribution rewrite. Project lists use structural server-created scope, not optional request filters.

4. **Implement operation-keyed explicit promotion.** Promotion is the only path that creates a Task executable Plan link. In one guarded operation, create one minimal `TASK_TEMPLATE` Plan through the established Plan service and persist the immutable link/outcome. Bind the digest to canonical scoped Task and expected revision. Exact replay returns the persisted link without creating another Plan; digest mismatch conflicts; a distinct valid request after linking returns the existing link without mutation. Do not synchronize title, description, priority, project, or agent values. Submission must invoke established Plan execution validation and reject missing, disabled, or non-executable Plan.

### C. Canonical assignment attempts and transport surface

5. **Submit and retry through canonical assignment authority.** In one transaction, re-resolve Task and scope; apply capability; validate explicit executor, project/member state, and executable Plan; then call Phase 01 `AssignmentService` with `PLAN/<id>` source, `WORK_TASK/<id>` origin, copied priority, and immutable attribution. Append/designate only after accepted assignment. Use database uniqueness plus stored operation result so competing submission safely fails or converges. Retry only when designated assignment is terminal and appends one lineage successor; a nonterminal attempt rejects distinct retry. Do not mutate predecessor assignment or transfer workspace/artifacts.

6. **Add distinct global and project Task APIs.** Add `WorkTaskController` endpoints under `/api/work/tasks` plus project-scoped equivalents; preserve `/api/tasks` behavior and routing. Return redacted bounded list/detail/attempt DTOs with deterministic cursors and exact links. Re-resolve scoped resources for every action. Require expected revision and operation key for create/edit/pin/preferred-agent/promotion/submit/retry as applicable, and map disabled, unauthorized, archived, missing, cross-scope, ineligible, concurrent-active, and stale outcomes without sensitive leakage.

### D. Console fragments and complete proof

7. **Render the HTMX Tasks seam.** Extend only the passed `ProjectConsoleController` Tasks host and add stateless `WorkTaskFragments` over authorized redacted DTOs. Provide bounded list, editor, promotion, explicit-executor submit, attempt/history expansion, pin, preferred-agent, and empty/error/disabled states. Stable isolated HTMX hosts refresh only themselves; OOB swaps stay in Task domain. Display default priority as submission default and active Queue priority as assignment-derived data. Disable archive, gate-disabled, nonmember, unpromoted, non-executable-Plan, and active-attempt controls. Use forms/HTMX only; add no JavaScript transport.

8. **Add focused regression and concurrency coverage.** Cover CAS; replay/digest conflict; one-promotion convergence; independent Plan/Task edits; unavailable Plan; binding/pin rules; eligibility/no fallback; priority copy; source/origin; redaction/cross-scope; gate-disabled/archive; bounded ordering; and `/api/tasks` preservation. Exercise duplicate submit and retry races so exactly one nonterminal origin assignment/attempt survives and replay creates no lineage. Cover fresh install, supported warm upgrade, repeated application, checksum, partial structure, foreign-key/index, and metadata equivalence.

## Technical Guidance

The display relation is:

```text
WorkTaskDefinition -> designated WorkTaskAttempt -> WorkAssignment.status
```

Attempts are durable exact links, not runs or status records. The assignment source is the promoted Plan and origin is the Task. Project attribution is not workspace selection; use established `AssignmentService` workspace rules and never reconstruct workspace identity from the Task project.

Use repository-proven status constants, never guessed terminal names. If evidence cannot prove safe terminal release/writer quiescence for the active-origin index, leave submission/retry unavailable and escalate. Operation outcomes retain only redacted identity sufficient for safe replay; attempt history uses exact durable IDs, never names, timestamps, Queue order, or filesystem inference.

## Validation

After all A–D work is complete, run one phase-level set:

- Run focused Task repository/service/controller/authorization/concurrency tests with Plan and Assignment regressions.
- Verify fresh schema through `015`, supported warm upgrade from `014`, repeated application, checksum mismatch and partial-shape rejection, foreign-key/index shape, and `schema.sql`/migrated metadata equivalence.
- Run relevant Maven/static checks and bounded Spring Boot startup with trusted-deployment capability disabled and enabled; confirm disabled routes fail closed with no active Task controls.
- Use a Playwright subagent against the running app for management, promotion replay, explicit-executor submit, history, archive/empty/error/disabled states, deep links, HTMX host isolation, keyboard/focus/ARIA, desktop/mobile screenshots, and visual review of density, hierarchy, alignment, wrapping, overflow, affordance, and first viewport.
- Inspect the final diff for whole-row assignment writes, duplicate authority, optional scope, unbounded reads, sensitive DTOs, client-derived project identity, unjustified JavaScript, placeholders, unrelated edits, and `/api/tasks` change.
- Require orchestration post-phase validator review-and-repair PASS only after this complete set; subphases have no independent pass gate.

## Exit Criteria

- All A–D subphases are complete.
- Tasks are distinct from Plans in schema, API, service, and UI; explicit promotion creates one immutable executable Plan link and edits remain independent.
- Task execution uses linked executable Plan source and canonical assignment lifecycle; attempts are append-only exact lineage with derived status and one transactionally enforced nonterminal origin.
- Binding, pin, preferred-agent, priority-copying, archive, capability, scope, idempotency, revision, and explicit-executor invariants hold without fallback or attribution rewrite.
- `/api/tasks` compatibility is intact; `/api/work/tasks` and project surfaces are bounded, redacted, structurally scoped, HTMX-first, and fail closed.
- Migration, checks, enabled/disabled startup, browser/visual validation, and orchestration validator PASS complete before Phase 04 starts.
