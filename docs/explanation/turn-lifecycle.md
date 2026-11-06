# Turn lifecycle, serialization, and durable state

This page explains *how* Magenta Core executes a turn: the seven-phase state
machine, per-session serialization, the queue claim/drain protocol, durable
interaction compare-and-set races, tool-loop protection, and the reasoning
behind each design choice. It is the "why" companion to the
[response protocol reference](../reference/response-protocol.md) and the
[lifecycle how-to](../how-to/lifecycle-and-cancellation.md).

## The seven-phase turn state machine

Every turn is driven by `DefaultTurnEngine` through `TurnPhase`:

```text
PREPARE → INVOKE_MODEL → EVALUATE → (EXECUTE_TOOLS → INVOKE_MODEL)* → FINALIZE → DONE
                ↑             │
                └── REPAIR ←──┘   (empty response or tool-loop guard)
```

| Phase | Work | Failure category if it throws |
| --- | --- | --- |
| `PREPARE` | Resolve model; filter tools through the access policy; assemble system prompt and context window; persist the user message; emit `Started`. | `EXECUTION` (tool resolution errors become `TOOL`) |
| `INVOKE_MODEL` | Call the provider (streaming or blocking); extract thinking; emit `ContentChunk` / `ThinkingChunk` deltas. | `MODEL` |
| `EVALUATE` | Completion detector decides: finalize, repair (empty response), or execute tools. | `EXECUTION` |
| `EXECUTE_TOOLS` | Guard-check, then run each tool callback; emit `ToolActivity` per invocation; feed results back. | `TOOL` |
| `REPAIR` | Inject control messages for empty/incomplete/looping responses; emit a `Notice`; loop back to `INVOKE_MODEL` (bounded: 2 empty-response retries). | `EXECUTION` |
| `FINALIZE` | Persist messages; build the terminal `Outcome`; emit it; trigger async compaction. | `PERSISTENCE` |
| `DONE` | Terminal state; the stream completes. | — |

Interruption is checked at the top of every phase transition and between tool
calls: a queued interrupt reason is picked up at the next safe point, the
partial state is flushed, and the turn moves to `FINALIZE` with an
`Interrupted` (or `TimedOut`/`TurnCancelled`) outcome. This is why `interrupt`
is *cooperative*: it can never observe the engine mid-persist or between a
tool's side effect and its recorded result.

### Why a single state machine

Blocking `send` and reactive `stream` execute the *same* engine stream and
reduce it through the same accumulator. There is no separate "blocking path"
that could interpret progress differently than the streaming path. The
`takeUntil(Outcome)` boundary guarantees that after the first terminal value
nothing else is emitted, and the accumulator rejects progress-after-outcome as
an internal error rather than guessing a meaning.

## Per-session serialization

A session has at most one authorized execution. Three mechanisms cooperate:

1. **Session locks** — a striped `ConcurrentHashMap` of monitors serializes
   submission, question CAS, drain, and session-mutation decisions for one
   session.
2. **The turn gate** — `TurnGate` owns the durable-per-process state machine
   `IDLE → EXECUTING → AWAITING_ANSWER → EXECUTING → IDLE`. `acquire` returns a
   token; a session mid-execution rejects every other mode, and
   `AWAITING_ANSWER` admits only `ANSWER` mode.
3. **The active-turn registry** — `ActiveTurnRegistry` tracks the live turn
   ID, its phase, and its revocation reason; the facade maps `activeTurnIds`
   (session → turn) and `activeGateTokens` (session → token) to verify that
   *the same token that acquired the gate* is the one finishing.

The timeout path shows why all three exist. The `SerialTurnGate` scanner marks
an over-budget turn `TimingOut` but does **not** release it; it calls the
facade's fence handler with the session and token. The facade revokes the
matching active turn (which makes the engine's next interruption check fire),
emits `TimedOut`, and only then releases the gate. If the fence does not match
— a stale scanner tick for an already-finished turn — the gate stays
`EXECUTING`. The result: stale provider work can never become a second
authorized writer to the session's persistence.

`close()` uses the same machinery in reverse: revoke everything, then
`gate.drain(drainTimeoutSeconds)` waits for executing turns to observe the
revocation, then force-releases what remains.

## Submissions while busy are queued, not rejected

When a submission finds the session executing, awaiting answers, or owned by a
drain, it is **persisted as queue intent** and the caller receives a `Queued`
receipt with the reason (`SESSION_ACTIVE`, `AWAITING_QUESTIONS`) — or
`EXPLICIT` when the caller used `enqueue` directly. Rejecting would force
callers to build their own retry/ordering machinery; queueing keeps one
oldest-first lane per session and makes the intent durable across crashes.

The tradeoff is the **at-least-once** guarantee: the receipt is durable before
execution, and the drain acknowledges only after a terminal `Message` or
`Questions`. A crash between provider completion and acknowledgement re-runs
the request. Exactly-once would require a distributed transaction across the
provider call and the database — out of scope for a library. Callers with
harmful duplicates must use their own idempotency keys (documented in the
[queue how-to](../how-to/queue-and-drain.md)).

## Queue claim and drain protocol

The drain is the only queue consumer, and it owns all claim mechanics:

```text
drain(session) subscription
  └─ recoverExpiredClaims()                     # leases from crashed drains
  └─ loop:
       ├─ pending interaction?        → emit Questions, stop
       ├─ answered-but-unfinished?    → resume continuation (Answer origin)
       ├─ claimOldest(session, lease) → empty? stop
       ├─ execute with Queued(messageId) origin
       ├─ outcome Message|Questions   → ack(token) → continue | stop
       └─ anything else               → release(token) → stop/error
```

Design consequences:

- **Claim tokens never cross the facade.** A `Queued` receipt proves durable
  acceptance but confers no processing ownership. If callers could hold tokens,
  they could ack, reorder, or strand work; keeping the protocol internal lets
  the framework preserve oldest-first ordering and crash recovery.
- **Leases bound crash windows.** A claim is `(claim_token, claimed_at,
  lease_expires_at)`, all-or-nothing, unique while active. The default lease is
  5 minutes (bounded at one hour); after expiry the next drain recovers the
  claim. Short leases trade duplicate-execution risk for faster recovery.
- **Acknowledging `Questions` is intentional.** The question interaction is
  durable; the request *did* complete. Stopping after `Questions` lets the
  human answer before the rest of the queue runs, preserving question order
  semantics.
- **Interruption/timeout/cancellation release, not ack.** The request did not
  complete; a later drain retries it.

## Durable interactions and CAS races

An `ask_user` turn produces `Questions` with its own UUID — deliberately
distinct from the execution turn ID — persisted *before* publication. The
interaction row and its ordered question/answer children are the durable
source of truth; `pendingQuestions` replays the exact snapshot.

Answering is a compare-and-set:

```text
answer(session, interactionId, answers)
  ├─ validate: exactly one nonblank answer per question   → IllegalArgumentException
  ├─ find(session, interactionId)
  │    ├─ absent            → QuestionsMissing
  │    ├─ Answered          → QuestionsAlreadyAnswered (answers preserved for recovery)
  │    └─ Cancelled         → QuestionsAlreadyCancelled
  ├─ session busy?          → TurnLoopBusyException
  ├─ record ordered answers in the transcript marker (crash-recovery write)
  ├─ interactionStore.answer(...)  # atomic UPDATE ... WHERE state='PENDING'
  │    └─ Applied           → start continuation (Answer origin)
  │    └─ lost race         → typed rejection; restore marker
  └─ on completion: delete the consumed interaction
```

The race the design defends against: two clients answer (or one answers and
one cancels) the same interaction concurrently. Only one `Applied` wins; the
loser gets a typed outcome, never a side effect. The store's SQLite partial
unique index (`one PENDING per session`) plus the ID-qualified atomic update
make the CAS robust even across processes sharing the database.

Crash recovery deserves attention. The ordered answers are written into the
conversation transcript *before* the CAS, and the `ANSWERED` row retains them.
If the process dies after the CAS but before the continuation completes, the
next drain (or submission) finds the answered interaction plus its marker and
resumes the continuation with `ExecutionOrigin.Answer(interactionId)` —
without replaying the `Questions` outcome to a user who already answered.

Cancellation uses the same CAS boundary, which is why a stale client holding an
old interaction ID can never cancel a newer prompt: the old ID is either
`Missing` (consumed) or points at a terminal row.

## Typed protocol projections

The response protocol is the *projection* of this machinery onto sealed ADTs:

- Every expected terminal condition is an `Outcome` variant — including
  `Queued`, `TimedOut`, and the four question rejections — so applications
  handle them by type, exhaustively, with no string discriminators.
- Unexpected infrastructure failures are **not** outcomes: they carry
  `TurnFailure` (safe facts: session, turn, phase, category, timestamps, and a
  message that keeps the provider/root-cause text when it is usable after key
  and approval-secret redaction, control-character stripping, and a
  500-character bound) and use `TurnFailedException` or the stream error
  channel. Mixing them into the ADT would force callers to treat infrastructure
  failure as successful protocol completion.
- Progress values are the *only* mid-turn projection: content, thinking, tool
  activity (summarized), and notices. Provider-native chunk fields, token
  counts per chunk, and raw tool arguments are deliberately absent.

## Tool-loop protection

Two independent, caller-configurable guards bound each turn. The repeated-error
guard trips on the configured Nth consecutive execution with both the same
canonical `(tool, arguments)` identity and the same typed failure identity
(default 5). The Nth failure executes because its result establishes the
match. A success or a different call/failure identity resets or restarts the
streak, so successful polling and unrelated failures remain valid.

The hard iteration guard counts tool-bearing model responses, not individual
calls (default 10). At a maximum of N, responses 1 through N-1 may execute;
response N executes none of its calls. Either guard closes tool use, emits
`TOOL_LOOP_WARNING`, and gives the model one tool-free repair invocation. A
further tool-bearing response fails the turn through the typed `TOOL` error
channel.

Parallel calls remain provider-protocol complete when either guard aborts. The
assistant message retains every call in the model response, and the engine adds
one ordered tool result for every call before repair. Results already produced
earlier in the batch are preserved; the triggering repeated failure and every
trailing unexecuted call receive typed `LOOP_EXCEEDED` results. At the hard
boundary, every call receives that synthetic result and no callback executes.
Synthetic results do not copy blocked-call arguments into result or audit
metadata; canonical guard identities retain only digests.

## Secret-redaction choices

Credentials are redacted at every boundary where humans or logs might see
them: `ModelConfig.toString()`, framework diagnostics, and validation errors
print `apiKey=<redacted>`. But serialized `ModelConfig` JSON deliberately
includes the key so configurations can round-trip through the same sealed
ADT — which is why the docs classify serialized configuration as
secret-bearing. Tool activity and stream summaries are a second redaction
boundary: the model and UI see only the summarizer's output, never raw
arguments or exception traces, while the audit writer (an explicit,
fire-and-forget seam) receives full fidelity.

## Why the schema check is a clean break

SQLite `CREATE IF NOT EXISTS` silently accepts an old table with the right
name and the wrong shape. The framework therefore compares identity, version,
exact schema hash, and full object structure *before* any DDL runs, and never
mutates incompatible state. This trades automatic migration of the pre-release
schema for deterministic startup: applications that need old data perform an
explicit, application-owned export/import. See
[Clean-break compatibility](compatibility.md) for the full tradeoff discussion.

## Model resolution

Alias resolution is deliberately a three-step cascade: the request's explicit
selection, then the session's stored alias, then the first registered model.

- An explicit request selection is authoritative for that turn and updates the
  session's stored alias, so later turns without an explicit selection keep
  using the same model. This gives callers per-request override power without
  making every request carry a model.
- The stored alias makes sessions self-describing: a rehydrated UI or a
  restarting process continues with the model the user last chose.
- The first registered alias is the deterministic fallback for brand-new
  sessions, which is why registration order matters and why the builder
  snapshots it immutably.

Unknown aliases fail fast with `ConfigurationException` rather than silently
falling back, because a silently changed model can change behavior (and cost)
without notice.

## Related

- [Architecture](architecture.md) — boundaries and composition.
- [Clean-break compatibility and tradeoffs](compatibility.md).
- [Sealed response protocol](../reference/response-protocol.md).
- [Lifecycle and cancellation how-to](../how-to/lifecycle-and-cancellation.md).
