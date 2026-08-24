# Run Lifecycle Idempotency and Fencing

## Topic
Run lifecycle idempotency, migration, and fencing invariants.

## Source References
- `.internal-dev/plans/20260720-004112-home-dhickel-code-java-magenta-internal-dev-ha/phase-02-run-lifecycle-fencing.md`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/TaskRunLifecycleService.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/OrchestrationRuntimeRepository.java`
- `src/main/resources/db/migrations/002-run-lifecycle-fencing.sql`
- `.internal-dev/reviews/2026-07-20-phase-02-run-lifecycle-fencing-validation.md`
- `src/main/java/io/mindspice/magenta/core/DefaultTurnLoop.java`
- `src/main/java/io/mindspice/magenta/core/SerialTurnGate.java`
- `src/main/java/io/mindspice/magenta/core/internal/ConcurrentActiveTurnRegistry.java`

## Key Takeaways
- Operation keys are scoped by operation type. Canonical request digests include immutable definition/workspace/input/lineage facts but exclude generated run IDs, native IDs, assignment IDs, timestamps, paths, display names, and transport ordering.
- Persist a lifecycle reservation and root-relative location before provisioning the filesystem. Filesystem creation inside a database transaction can survive database rollback and invert the recovery contract.
- A writer generation must be monotonic across the whole run, not reset for each attempt. One-current-writer must be enforced by a unique partial index, and mutation verification must include run, attempt, owner, generation, and secret token.
- A CAS helper is ineffective if real runner paths continue using unfenced save, heartbeat, completion, release, or cancellation methods. End-to-end caller cutover is part of the fencing feature.
- Migration startup may encounter a complete `schema.sql` initialized before migration bookkeeping. It is safe to record migrations only after validating the entire latest owned schema; detecting one v2 table or column can bless a malformed partial schema.
- Migration tests must supply the full ordered resource sequence represented by the current `schema.sql`. Running only migration 001 against a migration-002 fresh schema produces false v1 validation failures and stale v1 assertions.
- A deterministic definition/input hash is a request digest, not a submission operation key. Separate intentional submissions with identical inputs need distinct persisted keys; only retries of one submission reuse its key.
- A runner must fail closed when lifecycle reservation, provisioning, or claim acquisition fails. Logging and continuing recreates the unfenced path the lifecycle is meant to eliminate.
- Legacy rows without an operation type or current-claim marker must remain explicitly unavailable/non-current rather than being assigned a guessed type or current owner.
- Reserve the native run UUID at assignment submission and persist it in the assignment checkpoint. The runner must consume that id; nested native services must recognize the matching trusted context rather than allocate a replacement id.
- Workflow nodes and job items need a separate typed parent/child relation. Optional columns on the one native-link row cannot represent arbitrary task children and encourage assignment/native aliasing.
- Force interrupt and stale-lease recovery must revoke linked writer claims immediately. Waiting until a replacement runner happens to acquire a lease leaves a split-brain filesystem window.
- A real empty-job runner integration is a useful model-free proof: it exercises submission reservation, assignment leasing, native job identity, root provisioning, writer acquisition/closure, and terminal CAS without external AI infrastructure.
- Immutable context bindings must be resolved through durable owner links, not checkpoint JSON or a parallel metadata column. Submission idempotency must inspect an existing operation before creating a candidate assignment/binding; otherwise a losing concurrent retry leaves orphan immutable records. Runner validation must compare assignment/run owner links and binding state/generation before filesystem provisioning or writer acquisition.
- SQLite concurrent writers can return `SQLITE_BUSY` if two submission transactions both read before their first write. Serialize same-process submission entry before transaction work and retain a migration-owned unique operation record as the cross-process arbiter; then test that compatible concurrent calls return one assignment/binding/run while contradictory retries create nothing.
- `CompletableFuture.cancel(true)` on work launched through `supplyAsync` is not proof that the underlying provider or tool invocation stopped. Subscriber cancellation and timeouts must revoke a turn-scoped authority under the same lock used to guard stale-execution persistence; revocation waits for an in-progress write and all later memory, interaction, failure-cleanup, audit/lifecycle, and compaction writes from that execution fail closed. Authoritative cancellation cleanup may still write its cancellation record while it owns the session boundary.
- A gate timeout cannot safely change `EXECUTING` to `IDLE` using only a session ID. Capture the acquisition token, invoke a token-aware fence outside the gate lock to avoid lock-order inversion, and reset the gate only if the same token is still current and fencing succeeded. Late completion must also compare both turn ID and gate token before release or transition.
- Daemon timeout scanners still require explicit ownership. The composition root closes only the default gate it created, detaches its handler from caller-owned gates, and leaves caller-supplied gate lifecycle under caller control.

## Project Relevance
These rules prevent duplicate reservations, generated-ID idempotency failures, split-brain assignment/filesystem writers, orphan directories after rollback, and migration bookkeeping that misrepresents partial schemas as complete.

## Open Questions
- Phase 03 still needs process-level confinement because a preflight writer check cannot stop a shell subprocess after it starts.
- Phase 04 will move publication from legacy workspace-root outputs to the trusted run-local output location already carried in context.
