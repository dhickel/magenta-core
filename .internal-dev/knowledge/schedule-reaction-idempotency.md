# Schedule and Reaction Idempotency

## Topic

Revision-qualified operation identity, request digests, persisted claims, retry audit, causation deduplication, recovery, and side-effect-free simulation for schedules and event reactions.

## Source References

- `.internal-dev/plans/20260720-004112-home-dhickel-code-java-magenta-internal-dev-ha/concepts.md`
- `.internal-dev/specifications/architecture.md`
- `.internal-dev/specifications/services.md`
- `ScheduleService`, `EventReactionService`, `OrchestrationEventService`
- `AgentOrchestrationController`, `ScheduleReactionFragments`

## Key Takeaways

### 1. Definition revisions preserve historical meaning

- Schedule and reaction edits create a new revision. Definitions are soft-deleted; historical firing/dispatch snapshots and audit remain bound to the original revision.
- Accepted assignment templates and evaluation inputs are snapshotted so later edits cannot reinterpret prior operations.

### 2. Operation key plus digest is the idempotency boundary

- A schedule firing identity includes schedule id, revision, slot instant/local slot interpretation, timezone, and persisted clock-policy semantics.
- A reaction dispatch identity includes reaction id, revision, and exact source event id.
- Each operation stores a canonical request digest. Same key + same digest returns or resumes the recorded result. Same key + different digest is a conflict.
- UI-generated timestamps and polling time are not operation identity.

### 3. Fencing prevents stale scheduler mutation

- Scheduler ownership is persisted as owner, monotonic fence, heartbeat, and expiry for the supported deployment topology.
- Due selection, claim, enqueue authorization, renewal, retry, and terminal transition verify the expected current owner/fence in the guarded mutation.
- An expired or superseded poller affects zero rows and cannot enqueue or finalize work.
- Firing is persisted as claimed before the external lifecycle enqueue boundary.

### 4. Attempts and recovery are append-only

- Every enqueue attempt records attempt number, eligibility, owner/fence, outcome/error, and exact assignment/task-run links when accepted.
- `ENQUEUED` means lifecycle acceptance, not task completion. Runtime outcome comes from native/summary facts.
- Restart recovery resumes only the next legal persisted transition and reuses the same operation identity. It never guesses whether an enqueue succeeded.
- Missed schedule slots coalesce according to the persisted misfire policy rather than generating invented history.

### 5. Reaction causation supplies dedupe and loop safety

- A dispatch stores root event, parent event/dispatch, depth, dedupe key, correlation, and immutable event/reaction snapshots before enqueue.
- Duplicate source/revision returns the recorded dispatch; conflicting content rejects.
- Reaction-id loops, depth above 8, and more than 10 dispatches for one reaction in a rolling 60-second window are suppressed before assignment creation.
- Suppression is a typed append-only audit result, not a silently dropped event, and cannot later enqueue under the same identity.

### 6. Simulation is pure

- Schedule/reaction simulation shares deterministic evaluation logic with execution but receives immutable inputs, a supplied clock, and no mutation-capable collaborators.
- It creates no definition revision, claim, firing, dispatch, attempt, event, assignment, task run, tool call, artifact, or external mutation.
- UI labels simulation **Non-Executing** and keeps match/would-dispatch facts distinct from executed/enqueued/completed facts.

## Engine Relevance

When adding scheduler/reaction behavior, derive operation identity from persisted revision-qualified domain facts, not wall-clock polling or mutable definitions. Persist the next state before crossing the assignment boundary, carry the same key/digest into lifecycle submission, and append observations rather than overwriting history. Keep pure evaluators usable by simulation without repositories or side-effect services.

## Open Questions

- Multi-process scheduler deployment remains outside the current topology; any expansion requires a new claim/coordination decision and validation.
- A deliberate migration path is needed if operators must preserve definitions from removed legacy schedule/reaction tables.
- Create/edit fragments preserve entered values and render field-level validation, while successful mutations OOB-replace only the list host. Desktop/mobile browser evidence should mount fragments inside the operational shell so shared CSS and HTMX are active; direct fragment navigation is intentionally unstyled.
