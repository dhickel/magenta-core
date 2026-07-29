## Prompt

Red-team only the supplied authoritative synthesis. Identify overlooked issues, hidden constraints, likely defects, failure modes, contradictions, and unsupported assumptions without reconstructing or requesting the underlying worker reports. This is a critique of a candidate design direction, not an implementation plan.

## Source

Only the supplied `<authoritative-synthesis>` was reviewed. No raw worker reports, repository files, or implementation evidence were used. Consequently, claims in the synthesis about existing schemas, model authority, migration needs, and compatibility should be treated as unverified.

## Findings

### 1. “Singular runtime truth” is asserted before the competing authorities are resolved

The synthesis declares `WorkAssignment` authoritative while acknowledging two job models with run concepts and active consumers. A compatibility read adapter does not establish one runtime truth if either job model still owns status, retries, cancellation, or completion. The design needs a field-by-field authority matrix covering status, owner, priority, run identity, cancellation, output linkage, and terminal result. Without it, the project and agent projections can disagree even if they share an assignment DTO.

The statement that `JobDefinition`/`JobService` “appears” to be the stronger write authority is not sufficient grounds for a contract decision. Writer/consumer inventory is a prerequisite, not a later implementation detail.

### 2. Assignment identity and mutability invariants are underspecified

`WorkAssignment` is described as an “immutable-attribution execution request,” yet it also owns mutable status, priority, lease, and cancellation state. The synthesis does not identify which fields are immutable. In particular, `agent_id`, `project_id`, assignment type, definition link, context binding, and workspace selection should have explicit immutability rules.

Project visibility also needs temporal semantics. Is `project_id` captured immutably when submitted, inherited dynamically from a task/job, or recomputed from current membership? Dynamic inheritance would rewrite history; immutable capture requires rules for project moves, deletion, and retries.

### 3. The common optional-filter query can become an unsafe universal search endpoint

An internal query with all filters optional risks accidental unbounded scans and cross-resource disclosure. Route scope must be mandatory and structurally separate from user-selected filters. A project route must not be able to omit or override its project predicate; the same applies to agent routes.

Stable pagination is not defined. “Active state, priority descending, creation time ascending” needs a total order, including a unique tie-breaker, a precise active/terminal rank, and cursor behavior under concurrent priority/status changes. Offset pagination will duplicate or skip rows as the queue mutates. Nullable `project_id`, status, priority, and creation-time indexes may also require a schema migration, contradicting the proposed migration-free first slice.

### 4. Task execution has race and lineage holes

“Usually one active assignment at a time” is not an invariant. Concurrent submit, retry, promote, and reassign requests can create multiple active assignments unless the rule is transactionally enforced. If multiple executions are allowed over a task’s lifetime, “derive task status from the assignment” is ambiguous: latest assignment, current active assignment, most severe state, or a designated execution pointer?

Revoke-and-clone, retries, and task-to-plan promotion all create assignment lineage. The synthesis does not define how the task selects its current execution, how a promoted plan remains linked, or what users see when an earlier attempt failed but a later attempt succeeds. A project-local pin also needs rules for unbound tasks, moving a task between projects, project deletion, and concurrent pin updates.

### 5. Reassignment is not yet a safe protocol

“Create the replacement only after the old writer can no longer commit” is the key guarantee, but no mechanism proves it. Cooperative cancellation does not establish writer quiescence. A stale process may still perform external side effects even if database fence checks reject its final commit.

The protocol also has a destructive gap: cancellation can succeed and replacement creation or context capture can fail. It needs a durable reassignment operation/state machine, idempotent lineage key, atomic or recoverable transition, timeout policy, and explicit outcomes such as `REQUESTED`, `REVOKING`, `REPLACEMENT_CREATED`, and `FAILED`. Repeated UI submissions must not create multiple clones. “Running reassignment” should be presented as cancel-and-restart, not migration, because partial progress and non-transactional side effects are not transferred or rolled back.

### 6. Current-membership checks conflict with historical truth

The synthesis requires project lifecycle routes to verify target-agent membership, but assignments and transcripts are historical records. If an agent leaves a project, a current-membership check could make historical assignments, transcripts, outputs, or cancellation state inaccessible. Conversely, allowing all historical access may expose data after removal.

The contract must distinguish membership-at-submission, current membership, historical-read access, and mutation authority. It also needs behavior for removing an agent that has queued/running assignments or owns enabled schedules/reactions.

### 7. “Project views are read-only” conflicts with project lifecycle controls

The Findings say project views are read-only, while later sections require project-scoped priority changes, lifecycle routes, task/job mutations, automation CRUD, and reassignment. If “read-only” applies only to transcripts or Files, it must be narrowed. If it applies to the project surface generally, several proposed capabilities violate it.

Using the same summary DTO on agent and project surfaces also must not imply the same action set. Capabilities should be computed from route scope and state, not inferred by the client from fields.

### 8. Resource-scope validation is not authorization

The synthesis correctly admits this, but then treats mutations as deliverable under an “open-alpha posture.” If the application is remotely reachable, checking that an assignment belongs to a project does not establish that the caller may view transcripts, reprioritize work, cancel runs, browse member files, or edit automation. This is a deployment safety issue, not merely future multi-user polish.

At minimum, the recommendation needs an explicit environment/exposure gate and fail-closed behavior. “Secret-free assignment summary” is also too narrow: titles, descriptions, transcripts, outputs, errors, and agent filenames may themselves be sensitive.

### 9. Idempotency and stale-response controls are described too vaguely

“Where applicable” leaves dangerous ambiguity for submit, cancel, reprioritize, reassign, schedule fire, reaction fire, and simulation. Operation-key scope, digest inputs, retention, replay response, actor/resource binding, and collision behavior need one contract.

Separate HTMX hosts prevent some target collisions but do not prevent out-of-order responses, stale inspectors, duplicate submits, or OOB swaps with reused IDs. The UI contract needs request cancellation/serialization, revision or version checks for mutations, host-scoped identifiers, and stale-response tests. Hidden lease/fence details are appropriate for list payloads, but mutations still need a safe concurrency token or equivalent server-side precondition.

### 10. Automation attribution has unresolved lifecycle failures

Adding nullable `project_id` is a migration and requires foreign-key deletion behavior, indexes, revision-copy rules, and uniqueness decisions. Executor membership must be checked both when a revision is created and when it fires. The design does not say whether automation is disabled, fails audibly, or selects no replacement when the executor leaves the project.

Project identity on events must be trusted provenance, not arbitrary caller data. Otherwise a producer can spoof project scope and trigger reactions. The design should define which event types carry immutable project attribution and how operation keys prevent duplicate firing across retries or definition revisions.

For `ONE_TIME`, “exactly once” should not be implied. Claims and fences can provide at-least-once execution with idempotent effects, but crashes around effect completion remain relevant. Past `fireAt`, timezone normalization, clock skew, retry windows, cancellation, and terminal retention are omitted.

### 11. The materialization template may reintroduce project filesystem authority indirectly

A project-owned template containing directories or seed files is still durable project-owned filesystem content, even if applied into agent roots. The contract must state where template bytes live, who may edit them, how they are validated, and whether they are metadata or files. Seed files create risks around secrets, executable content, symlinks, traversal, quotas, collisions, and overwriting agent changes.

“Apply through a new materialization generation” leaves transition semantics unresolved: whether existing bindings keep the old generation, when the new generation becomes active, what happens on partial failure, and whether template edits unexpectedly fork or discard member-specific state. This is materially more than wording reconciliation and may require new persistence and orchestration.

### 12. Files and Outputs lack a complete access contract

A “single-member” Files browser needs a precise member-selection rule, behavior for non-`READY` generations, and protection against path traversal and symlink escape. Current versus historical membership again matters. The Outputs tab is listed but not designed: outputs live under effective agent/user workspaces, so project output discovery needs durable assignment/output metadata rather than filesystem inference. Read access, retention, deletion, and generation changes are not addressed.

### 13. Deferred chat is partly over-specified and partly unresolved

The synthesis simultaneously defers chat and proposes routes, tool profile, conversation isolation, context binding, and SSE conventions. Ratifying those details now could constrain later design without validating the existing chat model. Meanwhile, the central identity question—how a member agent is selected and persisted—is still open.

A disabled tab should not be treated as delivery of a capability. It may create user confusion unless it communicates a genuine availability state and has no active controls. Conversation authorization, membership removal, agent changes mid-conversation, transcript retention, and context rebinding remain unresolved.

### 14. The grouped UI is a plausible direction, not a validated information architecture

Grouped navigation avoids tab overflow but introduces deep-linking, browser-history, keyboard, accessibility, active-state, and mobile-navigation requirements not mentioned in the synthesis. Keeping the existing selected-tab shell may not support nested groups cleanly. Stable list/detail hosts also need unique IDs across multiple rendered modules.

A typed render context can reduce duplication, but it may become a route-generating abstraction that accidentally shares action URLs or permissions across scopes. Reuse presentation primitives; keep resource scoping and mutation capability explicit.

### 15. The “no-schema-migration” proof slice is unsupported and too broad

The proposed first slice includes unified querying, project queue rendering, scoped lifecycle controls, transcript access, reusable fragments, and HTMX isolation. That is not a small read-only proof. It crosses data access, authorization, mutations, transcripts, UI, and concurrency.

The claim that it needs no migration is unproven: `project_id` availability, indexes, stable cursor fields, assignment-definition links, and transcript linkage are not established by the synthesis. A safe proof should begin only after schema and authority inventory, and should initially be read-only unless mutation preconditions and authorization are settled.

### 16. Important lifecycle and validation cases are absent

The design does not define archive/delete behavior for projects, tasks, jobs, schedules, reactions, assignments, transcripts, and outputs. Foreign-key strategy, retention, tombstones, and historical display all affect the “durable lifecycle” guarantee.

The acceptance list is broad but omits explicit concurrency/failure-injection scenarios, accessibility, query performance, migration rollback, project deletion, member removal, duplicate operation keys, restart recovery, and external side effects from fenced-out workers. “Observe the same assignment twice” proves projection overlap, not singular authority or lifecycle safety.

## Options

### Option A — Contract-hardening gate before any proof slice (recommended)

Resolve runtime authority, assignment immutability, temporal membership, task execution lineage, reassignment recovery, query pagination, authorization posture, and project lifecycle semantics first. Then select the smallest implementation slice supported by verified schema evidence.

### Option B — Read-only projection spike

After schema/index inspection, expose a bounded project assignment list and transcript links with no lifecycle controls. Use it to validate overlap, scope enforcement, pagination, component reuse, and HTMX host behavior. This provides evidence quickly but deliberately does not claim mutation or lifecycle completion.

### Option C — Mutation-capable queue behind a strict deployment gate

Implement project queue controls only if caller authorization or an explicit local/private deployment gate exists, and only after revision/idempotency contracts are defined. This gives earlier operational value but substantially increases concurrency and security risk.

### Option D — Continue with the proposed phased direction unchanged

This maximizes momentum but bakes unresolved authority, lineage, membership, and migration assumptions into APIs and UI. It is the highest-rework and highest-safety-risk option.

## Trade-offs

| Choice | Advantage | Risk / cost |
|---|---|---|
| Contract-hardening first | Prevents incompatible persistence and lifecycle commitments | Delays visible UI progress |
| Read-only projection spike | Tests the core dual-projection claim with limited blast radius | Does not validate cancellation, priority, or reassignment |
| Cursor-based total ordering | Stable bounded navigation under queue churn | Requires a precise status rank, tie-breaker, and likely indexes |
| Immutable project attribution | Preserves historical truth | Requires explicit behavior for moves, deletion, and membership changes |
| Durable reassignment state machine | Makes cancel-and-restart recoverable and idempotent | Adds persistence and operational states |
| Current-membership enforcement | Simple rule for active operations | Can hide history and strand work after member removal |
| Historical membership/snapshot rules | Preserves auditability | Adds policy and potentially retained access after removal |
| Project materialization templates | Consistent initialization without a project root | Creates a new content authority and generation-transition complexity |
| Compatibility job adapter | Reduces immediate migration pressure | Can prolong dual truth and complicate pagination/identity |
| Disabled chat surface | Communicates roadmap or feature state | Can confuse users and prematurely freeze an unsettled contract |

## Open Questions

1. Which exact store and fields are authoritative for every runtime concern across assignments and both job models?
2. Which assignment fields are immutable, and is `project_id` a submission-time snapshot?
3. What transactional invariant prevents more than one active execution for a task, and how is the current attempt selected after retries, promotion, or reassignment?
4. What durable protocol proves the old writer is fenced, recovers from replacement-creation failure, and deduplicates repeated reassignment requests?
5. How do current membership, membership-at-submission, historical reads, member removal, and active-work mutation interact?
6. What caller authorization or deployment gate protects project transcripts, files, outputs, and lifecycle mutations during open alpha?
7. Does the existing schema actually support a migration-free project queue, including required indexes and stable cursor ordering?
8. What are project archive/delete semantics for linked definitions, assignments, automation, transcripts, and outputs?
9. Where is a materialization template stored, what content may it contain, and how are conflicts, symlinks, failures, and generation activation handled?
10. What happens to project-scoped schedules and reactions when their executor leaves, the project is archived, or an event carries untrusted project identity?
11. Is the first proof intentionally read-only, or are mutation safety and authorization prerequisites part of that slice?
12. Which chat details truly need ratification now, versus remaining explicitly undecided until chat implementation is authorized?

## Recommended Next Step

Do not ratify the synthesis as an implementation contract yet. Hold the proposed decision review, but expand it into an invariant review with concrete evidence for:

1. runtime field authority across assignment and job models;
2. immutable assignment attribution and task-attempt lineage;
3. temporal membership and project archive/delete behavior;
4. recoverable, idempotent reassignment;
5. authorization or deployment gating;
6. schema/index/pagination feasibility; and
7. automation and materialization failure semantics.

After those decisions, revise the unified-contract specification before planning implementation. If an early proof is desired, make it a bounded, read-only Project Queue projection with stable cursor ordering and transcript scope checks. Add lifecycle controls only after concurrency, idempotency, and authorization preconditions are explicit and testable. Do not retain the “no schema migration” claim unless repository inspection proves it.
