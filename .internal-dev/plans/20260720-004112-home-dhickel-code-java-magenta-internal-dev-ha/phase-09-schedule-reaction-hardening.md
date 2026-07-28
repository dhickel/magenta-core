# Phase 09 — Fenced Schedule and Reaction Management

## Context

Current polling and raw-map reactions lack fenced ownership, revision-qualified identity, explicit clock and recovery policy, pure simulation, complete attempt audit, and causation loop protection. Their management surface must reflect persisted backend facts and Phase 08 runtime summaries without inferring unavailable outcomes.

## Goal

fenced automation semantics and focused management UI

## In Scope

**Write Targets**: src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/ScheduleService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/EventReactionService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/OrchestrationEventService.java, src/main/java/io/mindspice/magenta2/api/web/AgentOrchestrationController.java, src/main/java/io/mindspice/magenta2/api/web/ScheduleReactionFragments.java

- Add typed schedule, firing, simulation, claim, reaction, dispatch, and assignment-template models plus migration 009 and equivalent fresh-schema state through the migration owner established in Phase 01.
- Harden persisted scheduler ownership, recovery, retry, audit, causation, deduplication, and loop/rate rules.
- Add typed management APIs and reusable, slot-safe HTMX fragments.
- Add fixed-clock, competing-owner, restart/fault, simulation, controller, fragment, and browser tests.

## Out of Scope

- A generalized or newly distributed scheduler.
- Tool-capable or artifact-producing simulation.
- Deleting or rewriting firing, attempt, dispatch, causation, or suppression audit.
- Cross-domain run/Files composition owned by Phase 10.
- Replacing native run status or Phase 08 summary ownership.

## Dependencies

phase-02-run-lifecycle-fencing.md, phase-08-runtime-summaries-measurements.md

## Constraints

- Bind claims to the inspected deployed Magenta topology; do not invent coordination beyond supported topology.
- Cron uses a versioned, documented Spring six-field dialect and a mandatory valid IANA timezone.
- Simulation is capability-free: it creates no assignments, runs, artifacts, events, firings, dispatches, claims, or tool calls and performs no external mutation.
- Firing, attempt, dispatch, causation, and suppression audit is append-only by contract. Definitions are revisioned and soft-deleted.
- Reusing an operation key with the same request digest returns the recorded result; reuse with different input is a conflict.
- Every guarded scheduler transition verifies the expected owner and monotonic fence at the mutation boundary. Expired or superseded owners cannot enqueue or finalize work.
- Definition edits create a new revision. Historical firing and dispatch identity, snapshots, and audit remain bound to the original revision.
- Assignment/run links use Phase 02 lifecycle APIs and fences; outcome presentation uses Phase 08 facts and preserves unavailable legacy values.
- Simulation and execution share deterministic evaluation logic, not side-effecting orchestration collaborators.
- UI labels simulation as non-executing, enqueued work as distinct from completed work, and legacy or unavailable history as incomplete.
- CRUD and partial refreshes are HTMX-first; any JavaScript requires a narrow recorded justification.

## Implementation Steps

1. Add migration 009 and fresh-schema evolution for revisioned definitions, scheduler claims, firing attempts, reaction dispatches, causation, request digests, operation keys, snapshots, and suppression facts. Register the immutable migration through the sole migration owner, preserve valid legacy audit without invented fields, and test checksum, rerun, partial-state, and concurrent-start behavior.
2. Replace raw assignment maps at service and controller boundaries with validated typed templates. Canonically encode and digest mutation requests, reject unknown or invalid fields before persistence, and snapshot each accepted template into immutable firing or dispatch audit so later definition edits cannot alter history.
3. Define and document clock defaults: Spring six-field cron; mandatory IANA timezone; gap skips; overlap fires once at the earlier offset; restart misfire coalesces to one fire-once record; overlap defaults to forbidden while linked work is active; disable or soft-delete blocks unclaimed future firing without silently cancelling already enqueued work. Persist the dialect and policy version needed to interpret historical revisions.
4. Implement one persisted scheduler claim suitable for the inspected topology, with owner, monotonic fence, heartbeat, and expiry. Make acquisition, renewal, due selection, claim, enqueue authorization, and terminal transition compare the expected owner and fence in the guarded statement; stale pollers must affect zero rows and cannot enqueue.
5. Uniquely identify a firing by schedule ID, revision, local slot, timezone, and offset policy. Persist `CLAIMED` before enqueue, snapshot the evaluated inputs, and use the revision-qualified firing key as the Phase 02 assignment/run operation key with a request digest. Same-key/same-digest replay returns the recorded firing; different input conflicts.
6. Persist no more than three enqueue attempts per firing with delay `min(60 seconds, 2^attempt seconds)`, explicit eligible-at, owner/fence, error classification, and terminal exhaustion. Append an attempt before each enqueue boundary and record assignment, task-run, native-run, and correlation links on success; never represent successful enqueue as completed execution.
7. Recover claimed or in-progress firing after expiry and restart by inspecting persisted state and linked Phase 02 operations. Resume only the next legal transition, reuse the recorded operation key, and coalesce missed slots into one auditable misfire record rather than one row per slot. Do not enqueue when linked work already proves success or when current overlap policy forbids it.
8. Uniquely identify a dispatch by reaction ID, revision, and source event ID. Persist dispatch, immutable reaction/template snapshot, request digest, and causation before enqueue; carry root event, parent event/dispatch, depth, dedupe key, correlation, assignment, and task-run links into audit. Duplicate source/revision returns the recorded dispatch, while conflicting input is rejected.
9. Reject a reaction ID already present in the causation chain, depth above 8, and more than 10 dispatches for one reaction in a rolling 60-second window by default. Evaluate these rules transactionally with dispatch creation, persist a typed suppression reason instead of dropping the event, and ensure suppressed dispatches cannot later enqueue unless an explicit new source/revision creates a distinct identity.
10. Share pure schedule and reaction evaluators between execution and simulation using immutable input and a supplied `Clock`. Return typed next-slot, DST/misfire/overlap, match/no-match, dedupe, causation, and suppression explanations. Keep evaluators free of repositories, lifecycle services, tools, artifact services, event publication, and wall-clock access.
11. Add typed CRUD, revision creation, enable/disable, soft-delete, preview/simulation, paginated audit, and legal cancellation-control routes in `AgentOrchestrationController`. Validate identifiers, cron/timezone/policies, bounded page inputs, optimistic revision expectations, operation keys, and request digests; return stable typed validation, conflict, unavailable, and stale-fence responses. Cancellation controls may request cancellation of linked work through its owning lifecycle API but must not rewrite audit or claim that enqueued work was cancelled without a recorded result.
12. Implement `ScheduleReactionFragments` as reusable, slot-safe SimplyPages components for compact lists, editors, actions, pure preview/simulation, bounded firing/dispatch attempt audit, linked runtime outcomes, and legacy/unavailable/error states. Use stable fragment hosts and HTMX/OOB refresh after mutations; preserve entered values and field errors, distinguish disabled/deleted/suppressed/exhausted/enqueued/running/terminal states, and avoid broad JavaScript transport.
13. Test duplicate pollers, stale and expired fences at every guarded transition, restart/fault injection at every persisted state boundary, same-key replay and digest conflicts, revision edits, DST gaps/overlaps, coalesced misfire, overlap, retry/exhaustion, disable/delete, linked-work cancellation, simulation side-effect absence, duplicate events, loops, depth/rate suppression boundaries, append-only audit, typed route failures, fragment swaps, and responsive layout. Run the required validator review-and-repair after all evidence is collected.

## Invariants and Edge Cases

- At most one revision-qualified firing and one revision-qualified source dispatch exist for their unique identities, regardless of poller, event, or restart races.
- A scheduler owner without the current unexpired fence cannot select, enqueue, renew, suppress, retry, or finalize guarded work.
- A committed enqueue attempt is never erased. Recovery resolves it from persisted lifecycle links or safely retries through the same operation key; it does not guess success.
- Definition disable, soft-delete, or revision change never mutates historical snapshots and never silently cancels linked work.
- DST gaps, overlaps, timezone rule changes, clock rollback, and restart after multiple missed slots follow the persisted policy version and remain explainable in audit.
- Null or absent legacy links, outcomes, usage, and causation remain unavailable; no synthetic firing, dispatch, success, health, or completion fact is created.
- Duplicate and suppressed reaction events remain auditable. Causation depth and rate checks are deterministic at their exact boundaries and cannot be bypassed by retries.
- Simulation accepts the same validated definition inputs as execution but has no mutation capability and emits no execution-like record.
- Audit pagination is bounded and deterministic. Authorization and scope checks occur before rows, counts, links, or runtime details are rendered.

## Required Guides

- `AGENTS.md`, `.internal-dev/AGENTS.md`, and `.internal-dev/specifications/AGENTS.md`
- Relevant service, architecture, API, web, and SimplyPages specifications
- Nearest orchestration, runtime, API, and web package guides
- Task-relevant execution-history, API-boundary, scheduler, and HTMX knowledge files identified by filename or targeted search
- SimplyPages dynamic SlotKey/RenderContext, HTMX, component, editing, and API documentation plus relevant demos before fragment work

## Technical Guidance

Keep state transitions explicit and small: validate input, acquire or verify the persisted fence, append the next audit fact, invoke Phase 02 through an idempotent operation key, then append the observed link/result under the same expected fence. Repository updates should encode legal source states and fence predicates in their affected-row conditions rather than relying on an earlier in-memory check.

Revision-qualified identities prevent edits from colliding with historical audit. Store canonical request digests and immutable definition/template snapshots with firing and dispatch records. Keep native run status authoritative; use Phase 08 summaries only to render linked outcomes.

Model simulation as pure functions such as `evaluateSchedule(definitionRevision, instant, policy)` and `evaluateReaction(reactionRevision, eventSnapshot, causationWindow)`. Execution may consume their returned decisions, but the evaluator must not receive services capable of persistence, publication, tools, event emission, or assignment creation.

Use focused controllers that delegate state-machine behavior to services. Mutation responses refresh stable fragment hosts through HTMX/OOB swaps. Audit queries must be bounded and return explicit legacy, unavailable, and error states instead of deriving missing facts.

## Validation

- Run migration, schedule/reaction repository, service, wiring, controller, and fragment tests, including uniqueness, digest conflicts, affected-row fence checks, soft deletion, and append-only enforcement.
- Use supplied fixed clocks and fixed timezone fixtures around DST gaps, overlaps, clock rollback, exact rate-window boundaries, and restart misfires.
- Run competing pollers and injected expiry between due selection, claim, attempt append, enqueue, and finalization; prove one idempotent external result and zero successful stale-owner mutations.
- Inject restart/failure after every committed firing and dispatch transition; prove reconciliation converges through recorded operation keys or remains in an explicit safe terminal/unresolved state.
- Compare database, workspace, event, assignment, run, artifact, and tool-observation state before and after schedule and reaction simulation; prove only the response exists and no persistent or external side effect occurred.
- Verify legacy and unavailable facts remain explicit, linked runtime outcomes agree with Phase 08, and enqueue success is never rendered as run completion.
- Through the required `gpt-5.3-codex` medium test subagent, run focused Playwright for schedule/reaction CRUD, revision conflict, enable/disable, pure simulation, bounded audit, error recovery, and HTMX/OOB updates on desktop and mobile. Capture screenshots and critique alignment, density, hierarchy, wrapping, overflow, first-viewport usefulness, mobile stacking, and state clarity.
- Through the required test subagent, run the broader Maven suite and bounded Spring Boot startup. Exercise a real fenced firing and reaction dispatch when required dependencies are available; follow blocker policy rather than substituting unit-only evidence.
- Review the final diff for placeholders, raw assignment maps at changed boundaries, duplicate schema ownership, mutable audit, unfenced transitions, accidental simulation capabilities, unbounded queries, broad JavaScript transport, speculative coordination, and unrelated edits.
- Run the required `openai-codex/gpt-5.6-sol` medium validator review-and-repair gate and require PASS.

## Exit Criteria

- The ledger goal, dependency line, and sole Write Targets line match orchestration metadata exactly.
- Schedule ownership, revision-qualified firing/dispatch identity, request digests, attempts, snapshots, causation, suppression, and work links are persisted with enforced uniqueness and append-only audit.
- Current owner and monotonic fence are verified at every guarded transition; competing or stale owners cannot duplicate external work.
- Clock dialect, timezone, DST, misfire, overlap, retry, disable/delete, cancellation, dedupe, and loop/rate semantics are versioned, explicit, tested, and visible where relevant.
- Recovery from every tested committed boundary converges idempotently or records a safe truthful terminal/unresolved state without fabricated history.
- Simulation is capability-free, deterministic under a supplied clock, side-effect-free, and visibly distinct from execution.
- Typed APIs reject invalid, stale, conflicting, and unbounded requests without leaking partial data or relying on raw maps.
- Management fragments are reusable, bounded, truthful, responsive, slot-safe, and HTMX-first; screenshots pass functional and visual review.
- Focused tests, broader Maven suite, bounded startup, required real execution, final-diff review, and internal-development closeout pass without an unapproved blocker.
- Post-phase validator review-and-repair reports PASS before Phase 10 begins.
