# Phase 01 — Authority and Assignment Foundation

## Context

Current assignment persistence can whole-row upsert immutable attribution, queue queries are separately scoped and unstable, and projects lack non-destructive archive/gate behavior. Repository inspection must settle that runtime `JobDefinition`/`JobRun`, not internal `AgentJob`, is the public Job authority.

## Goal

establish singular assignment authority, gated project safety, stable scoped pagination, and idempotent controls

## In Scope

**Write Targets**: src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/WorkAssignment.java, src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/AssignmentRequest.java, src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/OrchestrationRuntimeRepository.java, src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/AssignmentService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/AssignmentQueryService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/AssignmentControlService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/ProjectOperationsAccessService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/Project.java, src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/ProjectRepository.java, src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/ProjectService.java, src/main/java/io/mindspice/magenta2/api/web/ProjectController.java, src/main/resources/db/migrations/014-project-assignment-authority.sql, src/main/resources/schema.sql

Establish the assignment authority matrix and enforced repository boundary; add immutable source/origin/context attribution and narrow writers; create the project safety/capability boundary; add structurally scoped bounded Queue reads and durable idempotent controls; and prove migration, lifecycle, concurrency, redaction, and query contracts. Supporting tests, migration discovery/registration, and required documentation use established repository locations when necessary; do not implicitly initialize `.internal-dev`.

## Out of Scope

Queue fragments and navigation; Task definitions/attempts and Plan promotion; Job reassignment; templates, outputs, automation, project chat, multi-user authorization, running transfer, and changes to internal `AgentJob` behavior. Do not pre-create later-domain archive guards or a generic extension framework: later phases add their persisted-dependency guards before enabling their capabilities.

## Dependencies

none

## Constraints

- `WorkAssignment` is canonical for submitted-work identity, queue membership, assignment lifecycle, priority, lease state, ownership, immutable attribution, and assignment business version. `JobRun` remains canonical for native-run identity, native-run state, and native result.
- Creation is insert-only. Immutable attribution includes agent, project, assignment type, executable source-definition link, workspace selection, immutable context binding, and matrix-designated additional facts. Unknown fields have no generic mutation path.
- Operator, runner, and lease writers own disjoint fields and methods. Lease fence/version and assignment `rowVersion` are distinct; heartbeats do not advance business version. Controls cannot bypass lifecycle fencing.
- Project association is trusted attribution/context, not workspace authority. Never derive it from JSON, route text, events, filenames, membership history, or paths.
- The trusted-deployment capability defaults disabled. Every sensitive service entry point applies the same server-owned decision; controllers map it and disabled views expose no active control. Resource scope alone never authorizes.
- Agent and project queues are one `AssignmentQueryService` projection through server-created sealed scopes. Snapshots fix bounded ordered membership only; display/actions re-resolve canonical scoped rows.
- DTOs exclude raw input/output/evidence, unrestricted errors, internal paths, lease owners/fences/checkpoints, secrets, and unnecessary diagnostic content.

## Required Evidence Gates

Before enabling the affected mutation or public surface, inspect and record repository evidence in a field-level authority matrix for:

1. every assignment field, canonical owner, writer/service, repository method, update predicate, consumer, and immutable/business-versioned/lease-fenced/derived status;
2. public Job write ownership and the `JobDefinition`/`JobRun` boundary, including any bounded legacy-read need; `ai.agent.job.AgentJob` is excluded from project APIs/views and cannot create dual writes or permanent union authority;
3. caller/access binding for capability and operation digests; absent trustworthy server-bound caller disables sensitive capability;
4. lifecycle/fence behavior proving controls cannot race a native writer or mutate revoked/terminal state;
5. schema, index, migration discovery, checksum, and constructor-DDL behavior for additive `014`; and
6. project/member mutation paths and active-assignment dependencies for atomic archive/member-removal guards.

Contradictory or missing evidence stops the affected capability and requires contract escalation. It never permits optional scope, compatibility write, inferred authority, second lifecycle, or page-local exception.

## Implementation Steps

Complete A through D in order as one contiguous implementation. These are implementation subdivisions, not separately accepted deliverables: do not run an intermediate independent validation gate or declare a subphase complete on its own. Run phase-level Validation only after all four are integrated.

### A. Authority and persistence foundation

1. Inspect runtime repositories/services, lifecycle writers, Job/JobRun and `AgentJob` call sites, schema/migration runner, and tests. Produce the required authority matrix from observed evidence before exposing a mutation path. Cover assignments, Job definitions/runs, task-attempt seams, transcripts, outputs, cancellation, retries, and terminal results even where later phases own implementation.
2. Add persisted facts separating executable source from submission origin, plus immutable project/context/workspace attribution and `rowVersion`. Preserve the selected workspace contract; do not make project identity a workspace kind or reconstruct workspace identity from a `Path`.
3. Replace generic `saveAssignment`/upsert behavior with explicit insert and narrow methods. Each updates only owned fields and predicates immutable identity plus required lifecycle state, expected business version, and/or lease fence. Remove or make inaccessible every production generic assignment mutation path.
4. Keep native-run and assignment state separate. Runner methods may update only runner-owned assignment facts through fenced methods; they cannot overwrite definition fields, source/origin, priority, project, agent, workspace, or context. Heartbeats update lease facts only.
5. Add migration `014` and fresh-schema changes using discovered additive/rebuild and failure-safe conventions. Backfill only deterministic trusted legacy facts; do not invent project/origin/context attribution. Add immutable-column protection where supported as defense in depth, and ensure constructor DDL cannot recreate superseded shapes.
6. Add indexes, foreign keys, unique constraints, and checks required by actual scope/order/operation predicates and the matrix.

### B. Submission, project safety, and controls

1. Extend `AssignmentRequest` and submission so trusted typed source, origin, project, workspace, and immutable context are bound into canonical submission semantics. Reject contradictory JSON `input.projectId` or aliases; never treat them as authority.
2. Require active project and currently eligible executor for project-attributed submission. A project-bound preference must be a current member; an unbound preference follows established named-agent eligibility. Never infer a fallback executor. Preserve absent optional origin as absent.
3. Implement `ProjectOperationsAccessService` as the server-owned capability/scoped-access boundary. Invoke it from every sensitive read/mutation service and map its result in `ProjectController`; controller-only checks are insufficient.
4. Add non-destructive, atomic guarded project archive and member removal. Reject when an active assignment or current designated dependency would be stranded; retain historical rows/links and never cascade operational history. Make later phases explicitly add their Task-attempt, Job-run, automation, materialization, turn, and proposal guards rather than claiming unavailable domains are protected.
5. Persist priority/cancellation operation requests and outcomes. Canonical digest binds server-defined caller/actor context, action, route scope, resource, expected revision, and canonical request values. Same key/digest replays stored result; changed semantics conflict. Client actor values are never trusted.
6. Implement priority as a narrow CAS over canonical project scope, queued/nonterminal state, lease absence, and expected `rowVersion`. Delegate cancellation to the established direct-cancel versus cancellation-request lifecycle; do not add a state write that bypasses writer fencing. Define one outcome for replay, terminal-transition, lease-acquisition, and retry races.

### C. Structural query contract

1. Add server-created sealed `AgentScope` and `ProjectScope`. Scope construction resolves trusted identities; query, cursor, inspector, and action paths receive scope objects rather than optional agent/project arguments.
2. Implement one `AssignmentQueryService` with explicit active/history filters, enforced limit clamps, and deterministic state-rank, priority, creation, and immutable-ID ordering.
3. On initial list request, persist a bounded snapshot containing resolved scope digest, filter/order digest, expiry, and ordered member identities/versions only. Cursor contents are authenticated opaque references to the snapshot/binding, never SQL state or client predicates.
4. On traversal, validate cursor authenticity, actor/capability binding, scope digest, request digest, and expiry. Reject malformed, tampered, cross-scope, expired, missing, and digest-mismatched cursors. Clean expired snapshots through bounded cleanup that cannot affect live snapshots.
5. Re-read every displayed, inspected, or acted-on member canonically under the original scope. Return defined redacted changed/unavailable results when it changed, disappeared, is archived, or no longer satisfies visibility; never widen scope or leak the row.
6. Return secret-minimized summary records and server-computed allowed actions only. Keep controllers thin; prohibit unbounded list paths, raw query predicates, and caller-controlled sort SQL.

### D. Redaction, proof, and closeout readiness

1. Audit all assignment creation/update, lifecycle, Job/JobRun, transcript, output, retry, cancellation, and terminal-result consumers against the matrix. Remove stale whole-row writes and prove each writer uses its owned narrow method.
2. Test immutable attribution rejection; insert-only creation; writer column/predicate boundaries; lease heartbeat version behavior; fence/cancellation races; control replay/conflict; priority CAS; gate-disabled/missing caller binding; active/archive/cross-project/member-removal cases; and redacted denial/error DTOs.
3. Test agent/project overlap identity/status, scope non-widening through filters/cursors/resource IDs, cursor tampering/expiration, equal sort keys, bounded snapshots, concurrent lifecycle/priority changes, canonical re-resolution, and no duplicate/skipped snapshot members.
4. Test migration discovery, fresh install, supported upgrade, checksum/rerun, metadata equivalence, partial-structure rejection, index/foreign-key shape, immutable protection, deterministic backfill, and failure safety. Inspect query plans for introduced bounded indexed paths.
5. Run phase-level validation, inspect the complete diff for unrelated edits, duplicate authority, whole-row writes, unbounded queries, sensitive DTOs, unauthorized capability paths, placeholders, and unjustified JavaScript. Update records/documentation only through the available workflow; never initialize or repair an unavailable `.internal-dev` store.

## Required Guides

- `AGENTS.md`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/AGENTS.md`
- `src/main/java/io/mindspice/magenta2/api/web/AGENTS.md`
- `.internal-dev/specifications/AGENTS.md`
- `.internal-dev/knowledge/schema-data-ownership-validation.md`
- `.internal-dev/knowledge/run-lifecycle-idempotency-and-fencing.md`
- `.internal-dev/knowledge/security-access-control-domain-validation.md`

Read package guides before editing their packages. Read only task-relevant internal-development files when the store is available.

## Technical Guidance

Use records for redacted request/result carriers and sealed scope types for structural scope. Controllers resolve/request a server-owned scope and delegate to services; they do not expose constructors accepting untrusted route/request values.

A cursor is a signed/authenticated snapshot reference, not encoded SQL. Persisted snapshot membership fixes traversal; live scoped re-resolution supplies current safe display/action data. Operation processing follows:

```text
requestDigest = canonical(serverActor, action, scope, resource, expectedVersion, request)
existing(key) -> same digest ? storedResult : conflict
otherwise -> guarded mutation + stored terminal operation result atomically
```

Priority’s predicate includes project scope, expected `rowVersion`, queued/nonterminal state, and lease absence. Database triggers protect immutable columns only as defense in depth; narrow repository methods and predicates establish correctness. Preserve cancellation lifecycle semantics and fences instead of duplicating them.

## Validation

After all mandatory subphases are integrated, run:

- focused repository, service, controller, migration, and multithreaded lifecycle/control tests including subphase-D hostile/concurrent cases;
- migration discovery plus fresh-install, supported-upgrade, checksum, rerun, partial-structure, failure-safety, and metadata-equivalence coverage;
- affected runtime, Job/JobRun-boundary, fencing, archive/member guard, capability, query-plan, and redaction tests;
- relevant broader Maven/static checks; and
- bounded enabled and disabled Spring Boot startup checks.

The post-phase validator review-and-repair must pass before the phase is complete. No dependent phase starts before that PASS.

## Exit Criteria

- The evidence-backed matrix identifies one owner/writer/repository path for each relevant field and confirms the public Job boundary; `AgentJob` is not a project write/view authority.
- No production assignment path whole-row updates or changes immutable attribution. Insert, operator, runner, lease, lifecycle, and native-run writers affect only owned facts, with business versions and fences distinct.
- Capability, submission eligibility, archive, and member-removal behavior fail closed without destructive historical loss or sensitive disclosure. Existing dependencies cannot be stranded; later-domain guard obligations are explicit.
- Agent/project Queue projections expose identical canonical assignment identity under mandatory sealed scope. Bounded authenticated snapshots preserve membership/order while canonical re-resolution safely handles changed/unavailable rows; cursors and resource IDs cannot widen scope.
- Priority and cancellation are server-bound, durable-idempotent, conflict on changed semantics, lifecycle/fence-safe, and correct under terminal/lease races.
- Migration `014`, schema/backfill/index shape, fresh/warm/checksum/rerun/equivalence/failure tests, focused tests, broader checks, and bounded enabled/disabled startup pass. Required records follow available workflow without implicit `.internal-dev` initialization.
- Post-phase validator review-and-repair reports PASS.
