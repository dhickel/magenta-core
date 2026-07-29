## Prompt

Synthesize the Project Dashboard and Unified Work Contracts brainstorming into one concise candidate direction. The result must preserve Magenta’s closed workspace model, singular runtime truth, durable lifecycle guarantees, HTMX/SimplyPages conventions, and explicit deferral of project-chat implementation. This is a design recommendation for review, not an accepted implementation plan.

## Source

- unified-contract-architect/findings.md
- project-context-steward/findings.md
- operational-ui-composer/findings.md
- automation-lifecycle-researcher/findings.md
- delivery-risk-strategist/findings.md
- unified-contract-architect/cross-review.md
- project-context-steward/cross-review.md
- operational-ui-composer/cross-review.md
- automation-lifecycle-researcher/cross-review.md
- delivery-risk-strategist/cross-review.md

## Findings

### 1. Preserve one execution truth and project it twice

`WorkAssignment` should remain the canonical execution, queue, priority, status, lease, and ownership record. Agent and project queues are the same bounded query with different filters:

- Agent surface: `agent_id = X`
- Project surface: `project_id = Y`
- Overlap: one assignment satisfying both filters, rendered in both places

Introduce one internal query contract with optional agent, project, assignment-type, status, terminal-history, pagination, and ordering filters. Return the same secret-free assignment summary to both surfaces. Keep leases, fences, paths, checkpoints, and internal evidence out of list payloads.

Do **not** merge task definitions, job definitions, and executions into one “operational item” persistence model. Definitions and executions have different semantics. The Queue should show work that has actually been submitted as assignments; Tasks and Jobs should manage definitions. A shared render-time assignment summary is useful, but mixing unsubmitted definitions into the queue would weaken the singular-status contract.

Transcripts remain conversation records linked to assignments. Agent and project transcript routes should retrieve the same transcript after verifying that the routed agent or project matches the assignment. Project views are read-only.

### 2. Make Tasks lightweight definitions; derive execution state from assignments

Create a distinct lightweight Task concept rather than overloading AI-authored plans. A task needs a title, description, optional project binding, pin state, default priority, and optional preferred agent. Its execution should create a canonical assignment linked back to the task; running and terminal status should be derived from that assignment rather than duplicated on the task row.

For the first contract, pinning should mean **project visibility and prominence only**. It should not alter leases, prevent reassignment, imply automatic restart, or create a special queue position. A project-local boolean is sufficient if tasks belong to at most one project. A separate revision-qualified pin table is justified only if the product confirms cross-project pinning or pinning existing plans/workflows/jobs.

Tasks, plans, and jobs should remain distinct:

- **Task:** lightweight user-managed work definition, usually one active assignment at a time.
- **Plan/workflow:** structured executable definition, often AI-authored or multi-step.
- **Job:** reusable/reassignable container that may hold multiple work items and produce runs.
- **Assignment:** immutable-attribution execution request and lifecycle authority for all of the above.

Task-to-plan promotion should use an explicit link when needed; it should not silently turn every task into a plan.

### 3. Enhance Jobs without forcing a risky model migration into this sprint

The two existing job models are real architectural debt, but consolidating active orchestration tables is not required to deliver project visibility, run drill-down, or reassignment. Before Jobs work, inventory all writers and consumers and select exactly one authority for new writes. `JobDefinition`/`JobService` appears the stronger candidate because it already carries project, workspace, work-item, and run concepts; the other model can remain behind an explicit compatibility read adapter until a dedicated migration is approved. Avoid dual writes and avoid an indefinite “union everything” abstraction.

Reassignment must not mutate `WorkAssignment.agentId` in place. Use revoke-and-clone:

1. The old queued assignment is cancelled directly; running work follows the existing cooperative cancellation/interrupt and fence rules.
2. A new assignment and run are created for the new agent only after the old writer can no longer commit.
3. The new agent receives a freshly captured immutable context binding and its own workspace selection.
4. Explicit reassignment lineage/audit links the new run to the old one; old artifacts remain historical and are not transferred implicitly.
5. Updating a job definition’s preferred agent affects future runs; it does not rewrite historical assignments.

This preserves attribution, confinement, leases, and lineage. Whether the UI exposes running reassignment or initially permits only queued/waiting reassignment remains a product safety decision.

### 4. Keep project scope separate from filesystem authority

Projects must not gain a workspace kind, root path, or shared writable directory. The current Files surface remains a single-member, read-only browser over generation-safe agent materializations.

Reconcile “directory initialized on project creation” by changing the contract wording: project creation stores a **declarative materialization template**; each member agent’s materialization applies the validated template inside its own confined root before becoming `READY`. Template changes should apply through a new materialization generation, not mutate bound historical materializations. This satisfies consistent initialization without inventing project filesystem authority.

### 5. Add project context to automation, not a parallel automation engine

Schedules and event reactions should remain their existing revision-qualified, fenced, idempotent definitions with an optional indexed `project_id`. `agent_id` remains the explicit executor/owner; `project_id` is attribution and query scope. A project schedule or reaction therefore appears on both relevant surfaces without a binding table or duplicate engine.

Project-scoped reactions should match events explicitly carrying the same project identity. Do not infer project scope merely because an event’s agent happens to be a member; membership can change and is not durable causation.

One-time schedules should not be disguised as six-field cron expressions: a cron without a year generally repeats. Use one canonical schedule family with an explicit mode such as `CRON` or `ONE_TIME`; recurring definitions carry cron and timezone, while one-time definitions carry a single `fireAt`. Both reuse the same revision, claim/fence, operation-key, attempt-audit, and simulation machinery.

Schedule and reaction assignment templates must name the target agent and project explicitly. Automatic member-pool selection is a separate orchestration feature and is out of scope.

### 6. Treat project chat as a designed deferred contract

Define a future project conversation surface with `projectId` plus an explicitly selected project-member agent. The agent workspace and immutable project materialization binding provide context; no project workspace is created. The future profile should be server-owned and deny-by-default, allowing bounded reads of project metadata, members, materialization state, assignments, jobs, schedules, reactions, and transcripts. Filesystem writes, process execution, and skill activation remain unavailable.

The future API should mirror existing chat conversation/history/SSE conventions under `/api/projects/{projectId}/chat/...`, carry conversation identity in structured payloads rather than a query-string-only message contract, and isolate project conversations from ordinary chat history. Full routes, SSE client behavior, and tool authority remain deferred. A disabled-state tab is acceptable, but it should not expose nonfunctional controls or internal API documentation to end users.

### 7. Use a grouped operational console, not eleven flat tabs

Eleven horizontal tabs will overflow and weaken scan hierarchy. Prefer grouped navigation while retaining the requested capabilities:

- **Overview:** Dashboard
- **Work:** Queue, Tasks, Jobs, Outputs
- **Context:** Files, Chat
- **Automation:** Schedules, Events
- **Administration:** Members, Settings

Keep the existing selected-tab shell initially, but give interactive tab content stable list/detail sub-hosts. Queue, Tasks, Jobs, Files, Schedules, and Events should refresh only their own hosts. Desktop uses list beside inspector; mobile stacks list above detail. Standard CRUD, filters, simulations, selectors, and lifecycle controls remain HTMX-first. JavaScript is reserved for the future chat SSE stream.

Share SimplyPages row, chip, empty-state, selector, and inspector components through a typed render context that supplies host and route targets. Extract only the reusable rendering seams needed by the new surfaces; do not make a broad rewrite of the large orchestration controller a prerequisite to the first proof slice.

### 8. Preserve operational safety and observability

All mutations should reuse operation-key plus canonical-digest idempotency where applicable. Priority remains an integer with a documented global meaning; project queue ordering should be active-state first, then priority descending, then creation time ascending. Priority changes should be limited to mutable, nonterminal work and audited.

Project lifecycle routes must verify the assignment’s `projectId` and target agent membership. Under the current open-alpha posture this is resource-scope validation, not genuine user authorization; future multi-user authorization remains separate. Shell/process execution continues to fail closed.

Every delivered surface should be validated for canonical overlap: create or mutate once, then observe the same assignment from both agent and project projections. Host-isolation, stale-response behavior, lease/fence conflicts, reassignment lineage, migration upgrade/fresh-install behavior, feature-disabled states, responsive layout, and visual quality are first-class acceptance concerns.

## Options

### Option A — Projection-first, domain-specific definitions (recommended)

Keep assignments singular, retain focused Task/Plan/Job definition models, add project dimensions to existing automation definitions, and reuse parameterized query/render contracts. This provides the strongest balance of clarity, compatibility, and incremental proof.

### Option B — One universal work-item/run schema

Replace task, plan, workflow, and job definitions/runs with discriminator-based canonical tables. This is conceptually uniform but creates a wide or JSON-heavy model and forces risky migrations across established chat, workflow, job, run-identity, and tooling paths. The sprint does not justify that disruption.

### Option C — Project binding tables everywhere

Leave agent-owned schedules, reactions, and definitions untouched and connect them to projects through mapping tables. This supports multi-project binding but adds joins, consistency rules, and a second scoping pattern without a confirmed requirement. Use a binding table only where many-to-many semantics are explicitly desired.

### Option D — Broad UI parity before contract work

Build all tabs and adapt backend contracts as each screen requires them. This produces quick breadth but invites duplicate queries, inconsistent controls, hollow surfaces, and migration rework. It conflicts with the sprint’s contract-first requirement.

## Trade-offs

| Decision | Benefit | Cost / guardrail |
|---|---|---|
| Filtered assignment projections | One lifecycle truth; natural agent/project overlap | Requires indexed filters, bounded pagination, and strict route-scope checks |
| Separate lightweight Tasks | Clear user model without plan baggage | Adds a focused domain and needs an explicit task-to-execution link |
| Simple project-local pin | Minimal semantics and schema | Does not support cross-project or cross-definition pinning; add a junction only if confirmed |
| Revoke-and-clone reassignment | Preserves attribution, context immutability, fencing, and workspace confinement | Creates a new assignment/run and does not transfer partial progress |
| Nullable `project_id` on automation | Consistent, queryable dual scope | Ownership must be documented: agent executes, project scopes |
| Explicit one-time schedule mode | Truthful one-shot semantics | Adds a mode/fire-time branch to an otherwise cron-only model |
| Materialization template | Consistent files without a project root | Applies lazily per agent and requires generation-safe conflict rules |
| Grouped navigation and sub-hosts | Scales beyond eleven tabs and isolates stale responses | Changes information architecture and needs focused usability validation |
| Defer job consolidation | Avoids destabilizing active orchestration | Requires a temporary, explicit compatibility boundary and a later migration decision |
| Defer chat implementation | Honors scope and avoids a half-secured surface | The contract must still settle agent selection, context binding, and future authorization |

## Open Questions

1. Is a task owned by exactly one project, and does “pin” mean only persistent prominence, or must existing plans/workflows/jobs also be pinnable across projects?
2. Should task execution remain a direct task run, or may an agent promote it to a plan automatically? What event updates the task view when that happens?
3. Which existing job model is authoritative for all new writes, and which active consumers prevent an immediate cutover?
4. Should running reassignment be exposed now as explicit cancel-and-restart, or should the first release permit reassignment only before execution begins?
5. Which project-member agent backs project chat, and how is that agent selected and persisted for a conversation?
6. What is the minimal safe materialization-template format: directories only, or validated seed files as well?
7. Who may edit project-scoped schedules/reactions once real user authorization exists: the executor agent’s owner, any project administrator, or both?
8. How much terminal history should the Queue load by default, and what pagination/retention controls are required?
9. Does the grouped navigation preserve the product’s desired mental model, or must all capabilities remain first-level tabs despite overflow?

## Recommended Next Step

Hold a contract decision review, not an implementation kickoff. Ratify or revise five high-impact choices first: Task/pin semantics, authoritative job model, running-reassignment policy, project-chat agent selection, and one-time schedule representation.

If those choices are accepted, draft a single unified-contract specification and then a phased implementation plan. The first proposed proof slice should be the unified assignment query plus Project Queue because it needs no schema migration and can demonstrate same-row visibility, scoped lifecycle controls, transcript access, reusable fragments, and HTMX host isolation. Subsequent candidate slices are Tasks and Files, Jobs reassignment, Schedules and Events, and finally the deferred project-chat implementation. Each slice should remain independently releasable and validated; this ordering is a recommendation for planning, not an approved plan.
