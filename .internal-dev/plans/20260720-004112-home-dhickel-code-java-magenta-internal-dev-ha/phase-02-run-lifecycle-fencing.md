# Phase 02 — Run Lifecycle, Idempotency, Attempts, and Fencing

## Context

Phase 01 locks native-run cardinality, canonical task identity/digest encoding, ordered migration ownership, and trusted confined task-run location/capability types. This phase uses those decisions to reserve identity before execution and to prevent duplicate or stale assignment and filesystem writers across plan/task, workflow, job, assignment, runner, and submission paths.

Native run tables remain status truth. Companion lifecycle records add operations, attempts, links, root-relative locations, assignment fences, and writer claims without inventing historical facts or replacing native status ownership.

## Goal

idempotent run operations, attempts, links, assignment fences, and writer claims

## In Scope

**Write Targets**: src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/TaskRunLifecycleService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/OrchestrationRuntimeRepository.java, src/main/java/io/mindspice/magenta2/ai/chat/plan/PlanRepository.java, src/main/java/io/mindspice/magenta2/ai/orchestration/workflow/WorkflowRepository.java

- Add lifecycle repository/types and migration 002 through the owning runtime and repository paths, keeping the fresh schema equivalent.
- Cut plan, task, workflow, job, assignment, runner-context, and API submission paths to reserved IDs, explicit links, attempts, and fenced claims.
- Add focused migration, repository, service, concurrency, recovery, linkage, and controller tests adjacent to changed code.

## Out of Scope

- Artifact materialization, publication, search, preview, retention, and artifact UI.
- Reserved-tree mutation enforcement beyond carrying and checking trusted run-write capabilities.
- Process confinement and executable skill authorization.
- Skill, runtime-summary, schedule, and reaction behavior.
- A new workspace kind, task-run workspace descriptor, generic workspace identity, or project filesystem root.

## Dependencies

phase-01-contract-path-migration-foundation.md

## Constraints

- Apply Phase 01’s inspected cardinality contract. Reuse a native run ID as `taskRunId` only where Phase 01 proved immutable one-to-one identity and filesystem-safe validation; otherwise reserve a distinct task-run ID and persist an explicit native link.
- `work_assignments.id` is always a separate assignment identity. Native run, task run, assignment, workflow-child, job-run, and job-work-item identities may be associated only through typed links.
- Persist locations only as `(workspaceKind, workspaceKey, rootRelativePath)`. `WorkspaceDirectoryService` resolves the descriptor, and `WorkspacePathLayout` defines `tasks/<taskPathKey>/<taskRunId>/`; no caller persists an absolute path or derives identity from a `Path`.
- Task runs are children of effective `AGENT` or `USER` workspaces. Reject `CHAT` task selection before reservation. Existing chat artifact behavior is unchanged.
- Native run records remain status truth. Lifecycle reads may normalize status for presentation but must not write a competing status model.
- Initial creation reserves a run before dispatch. Resume reuses the same run ID and location and creates a new attempt. Operator retry and replay reserve new run IDs with typed parent lineage. Bounded internal retries become attempts in the owning run or child-call measurements, never an uncontrolled replacement writer.
- Operation keys are scoped by operation type. Reuse with the same canonical request digest returns the recorded result; reuse with a different digest is a conflict and creates no run, attempt, claim, or link.
- Assignment lease fences and run writer fences are independent monotonic capabilities. Every guarded assignment heartbeat, checkpoint, cancellation, terminal transition, release, working-file mutation, and later publication mutation verifies expected identity, owner, and current fence atomically at the mutation boundary.
- A zero-row guarded update means the capability is stale. The worker must stop state and filesystem mutation; it must not reacquire implicitly, complete, release another owner’s claim, or report success.
- At most one current writer claim exists per run attempt. A new resume attempt can acquire a higher writer fence only after lifecycle rules make the previous writer non-current; fence values are never reused after expiry, release, cancellation, or restart.
- SQLite and filesystem operations remain separate consistency domains. Persist reservation and location before provisioning; retries and reconciliation must converge on the same confined root without changing identity or treating directory existence alone as success.
- Responses expose stable IDs, operation key, lineage, attempt, and native status only. Never expose absolute paths, lease owners, fence tokens, write capabilities, or claim secrets.
- Preserve honest legacy rows: do not infer task attribution, fabricate attempts or lineage, create historical directories, or guess workspace identity. Represent unavailable lifecycle facts explicitly.

## Implementation Steps

1. **Consume the Phase 01 contract and add migration 002.** Read the nearest package guides and the relevant architecture, service, API, migration, workspace, plan, workflow, and job contracts. Verify Phase 01 recorded native-run cardinality, canonical digest encoding, migration ownership, and trusted location types; stop and escalate any contradiction rather than choosing a new identity model. Through the sole migration owner, add operation, task-run, attempt, typed-link, assignment-fence, and writer-claim structures plus required uniqueness, foreign-key, and monotonicity constraints. Add equivalent fresh-schema definitions and remove no native status authority. Store workspace kind/key and root-relative path, never host paths.

2. **Implement transactional lifecycle operations.** In `TaskRunLifecycleService` and `OrchestrationRuntimeRepository`, implement initial reservation, retry, replay, and resume as explicit commands. Initial/retry/replay atomically reserve or return the operation result, create the canonical task-run/native links, and create the first attempt. Resume atomically returns a prior same-key result or opens one new attempt on the existing run. Add typed links for native run, assignment, workflow parent/child, job run, and job work item according to the Phase 01 cardinality decision. Ensure uniqueness makes concurrent identical calls converge and conflicting calls fail without partial records.

3. **Canonicalize and verify operation requests.** Hash the operation type, immutable definition identity, effective workspace kind/key, source run and lineage mode where applicable, and canonically normalized inputs using Phase 01’s versioned encoding. Store operation key, digest, state, and stable result identity. Compare the digest before returning an existing result. Do not include display names, mutable slugs, timestamps, generated IDs, host paths, or transport ordering artifacts in the digest.

   ```text
   existing(key, type) absent  -> reserve operation and result atomically
   existing.digest == request  -> return the recorded result
   existing.digest != request  -> conflict; perform no lifecycle mutation
   ```

4. **Reserve identity at submission boundaries.** Update public plan/task, workflow, and job create/resume/retry/replay paths so a native/task-run identity is reserved before queueing or asynchronous dispatch. Require an operation key where the public contract allows it and return it with the reserved run identity. For compatibility paths, generate the key once, persist it with the submission before dispatch, and reuse it after retry or restart. A dispatch failure leaves a recoverable persisted operation; it must not cause a second run reservation.

5. **Fence assignment leases atomically.** Make assignment acquisition increment and return a monotonic lease fence in one transaction. Replace read-then-write ownership checks with compare-and-set SQL for heartbeat, checkpoint, cancellation, terminal transition, expiry takeover, and release. Each mutation matches assignment ID, expected owner, expected fence, and allowed state; release or expiry cannot reset the fence.

   ```sql
   UPDATE work_assignments
      SET heartbeat_at = :now
    WHERE id = :id AND lease_owner = :owner AND lease_fence = :fence
      AND state = 'RUNNING';
   ```

6. **Carry trusted execution context.** Extend `OrchestrationTaskContext` with `taskRunId`, `attemptId`, trusted `TaskRunLocation`, and `TaskRunWriteCapability`, while retaining the containing trusted `WorkspaceDescriptor`. Remove or narrow arbitrary run/output `Path` fields only after every consumer uses trusted values. Propagate the complete immutable context explicitly through asynchronous workflow/job children; never reconstruct it from thread-local state, assignment IDs, persisted paths, or parent directories.

7. **Order runner startup and writer acquisition.** Runner startup must: acquire the fenced assignment lease; load the reserved lifecycle record; resolve the workspace descriptor through `WorkspaceDirectoryService`; execute the lifecycle command or load its recorded result; idempotently provision the recorded confined run root; acquire the attempt’s monotonic writer claim; then dispatch work with the trusted context. Recheck the assignment and writer capabilities at every state/filesystem mutation boundary. On stale-fence detection, stop mutation immediately and surface a stale-worker result without attempting terminal ownership changes. Persist enough transition state to recover a crash before or after root provisioning or claim acquisition.

8. **Integrate native families and explicit lineage.** Make plan, task, workflow, and job services consume reserved IDs rather than creating replacement identities at execution time. Persist assignment/native/task-run links and typed `RETRY_OF`, `REPLAY_OF`, workflow-parent/child, and job/work-item links. Each child execution gets its own canonical sibling run location under its effective workspace; never nest a child run directory beneath a parent run or use an assignment ID as its task-run ID. Preserve native status writes in their owning repositories.

9. **Preserve workflow approval resume semantics.** Persist approval checkpoints against the owning run and attempt. Approval continuation is `resume`: it reuses the run ID and location, creates a new attempt and higher writer fence, and cannot revive the prior writer. Operator retry and replay create lineage-linked runs and cannot mutate the source run, its attempts, checkpoints, or location. Concurrent approval/resume requests with one operation key converge; distinct valid resume requests serialize so only lifecycle-permitted attempts become current.

10. **Return stable, secret-free results.** Update service/controller result models to return task-run ID, applicable native run ID, operation key, typed lineage, attempt ID/number, and native status. Same-key replay returns the same recorded IDs. Represent missing historical attempt or linkage data as unavailable rather than synthesizing it. Do not serialize root-relative or absolute paths unless an existing authorized contract explicitly requires the non-secret relative value; never serialize owner/fence/capability material.

11. **Add focused edge, concurrency, and recovery coverage.** Test initial/resume/retry/replay semantics; same-key/same-digest replay; same-key/different-digest conflict; simultaneous reservations and resumes; independent monotonic assignment and writer fences; stale heartbeat/checkpoint/cancel/complete/release and file mutation; lease expiry/takeover; restart before and after reservation, provisioning, and claim acquisition; workflow approval resume; bounded internal retries; workflow child and job-work-item links; sibling locations; workspace-root relocation; `CHAT` rejection; duplicate display names and renamed definitions under the Phase 01 identity vectors; and legacy rows with unavailable facts. Inject failures at each committed database/filesystem transition boundary and assert convergence or a safe explicit unresolved state.

## Required Guides

- `AGENTS.md` and the nearest package `AGENTS.md` for every changed package
- `.internal-dev/AGENTS.md` and `.internal-dev/specifications/AGENTS.md`
- Relevant architecture, service-graph, service, API, persistence/migration, workspace, plan, workflow, and job specifications
- `.internal-dev/knowledge/orchestration-lease-heartbeat-and-task-sse.md`
- `.internal-dev/knowledge/task-workflow-schema-and-run-snapshots.md`
- Phase 01’s recorded cardinality, canonical-key test vectors, migration decisions, and changelog

## Technical Guidance

Keep transaction boundaries narrow and make database constraints the final concurrency authority. A guarded update must include the identity, expected owner, expected fence, and allowed source state; a preceding read is diagnostic only and never authorizes a write. Assignment lease acquisition and writer-claim acquisition are separate operations and separate counters.

Represent lifecycle commands and results as explicit records rather than nullable mode flags. Keep operation creation and its stable result identity in one transaction. If native identity must be allocated by a native repository, reserve it inside the same SQLite transaction or use a persisted intermediate operation state that can be retried deterministically; do not dispatch between allocations.

Provision only the trusted location loaded from the lifecycle record. Directory creation is idempotent but does not prove ownership. Before each mutable run-file operation, validate the current write capability through the lifecycle boundary and pass only the confined child location to the filesystem service. Phase 03 will centralize broader reserved-tree enforcement; do not emulate it here with ad hoc string-prefix checks.

## Validation

- Through the required `gpt-5.3-codex` medium test subagent, run migration and lifecycle repository tests repeatedly against SQLite, including concurrent connections and injected transition failures.
- Run focused plan, task, workflow, job, assignment, runner, and controller suites. Assert response stability and absence of absolute paths and fence/capability secrets.
- Execute real agent-workspace and declared-user-workspace tasks covering initial, resume, operator retry, and replay; verify canonical sibling locations, lineage, attempts, and stale-writer rejection. Verify task selection for `CHAT` is rejected without a reservation.
- Relocate the data root and recover using only workspace identity plus root-relative location. Restart between reservation, provisioning, assignment acquisition, writer acquisition, checkpoint, and completion; verify deterministic recovery and no duplicate run or active writer.
- Run the broader Maven suite and bounded Spring Boot startup. Treat unavailable required infrastructure as a repository-policy blocker rather than replacing real execution with unit-only evidence.
- Review the final diff for duplicate schema ownership, path-derived identity, persisted absolute paths, accidental workspace kinds, assignment/run aliasing, unfenced mutation paths, fabricated legacy facts, placeholders, speculative APIs, and unrelated edits.
- Complete required specification, knowledge, changelog, plan/archive, documentation, and phase-commit closeout. Then run the required `openai-codex/gpt-5.6-sol:medium` post-phase review-and-repair gate; dependent phases remain blocked until it reports PASS.

## Exit Criteria

- Initial, resume, operator retry, and replay operations obey their identity and lineage semantics; same-key/same-digest calls return the stable recorded result and conflicting digests have no side effects.
- Every newly executable run has a reserved canonical identity, one confined root-relative location, explicit native/assignment links, and an attempt record before execution begins.
- Resume reuses the run/location with a new attempt; retry and replay create distinct lineage-linked runs; workflow/job children use explicit links and sibling locations.
- Assignment leases and writer claims use independent monotonic fences, and every guarded state or filesystem mutation rejects stale identity/owner/fence atomically.
- Crash and restart tests recover reservation, provisioning, attempts, and claims without duplicate runs, split-brain writers, path changes, or fabricated success.
- Native repositories remain status truth; API results are stable and expose neither absolute paths nor claim secrets; historical unknowns remain explicit.
- Focused tests, concurrency/recovery tests, broader Maven suite, bounded startup, and required real executions pass with no unapproved blocker.
- Internal-development closeout and the phase commit are complete, and the post-phase validator review-and-repair reports PASS.
