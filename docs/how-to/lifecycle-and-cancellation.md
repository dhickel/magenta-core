# Interrupt, cancel, and close safely

Magenta Core separates four controls that are easy to confuse: an interrupt
request, reactive subscriber cancellation, durable question cancellation, and
loop shutdown. This page states exactly what each one does.

## Interrupt an active turn

`interrupt(turnId, reason)` is a **cooperative request**, safe for concurrent
use. It is observed at the engine's next interruption point (between model
invocations and between tool calls) and produces `TurnResponse.Interrupted` as
the terminal outcome of that execution.

```java
AtomicReference<String> activeTurn = new AtomicReference<>();

loop.stream(TurnRequest.of("session-1", "Long operation"))
    .doOnNext(response -> {
        if (response instanceof TurnResponse.Started started) {
            activeTurn.set(started.turnId());
        }
    })
    .subscribe(this::handleResponse, this::handleFailure);

String turnId = activeTurn.get();
if (turnId != null) {
    boolean accepted = loop.interrupt(turnId, "User pressed stop");
    // accepted == true when a live turn or pending-question interaction took it
}
```

Contract details:

- Returns `true` when an active turn accepted the ID; `false` when the turn ID
  is unknown, already finished, or already fenced.
- The reason is normalized into the outcome (`Interrupted`, or `TimedOut` for
  the reserved `turn_timeout`, or `TurnCancelled` for the reserved
  `subscriber_cancelled`/`turn_cancelled`/`cancelled` reasons).
- If the turn ID belongs to **pending questions** (the turn that emitted
  `Questions`), interruption durably cancels that interaction with the given
  reason and emits `QuestionsCancelled`; it does not leave the session
  awaiting answers.
- Interrupting a blocking `send` (from another thread) makes it return
  `Interrupted`.

## Cancel a subscription

`Disposable.dispose()` stops that subscriber. Magenta fences the execution
(revokes the active turn, suppresses late persistence) and records a lifecycle
`TurnCancelled` outcome — but does **not** send that synthetic outcome to the
subscription that cancelled itself. Use `interrupt` when the caller needs a
terminal `Interrupted` value on the active stream.

## Cancel pending questions

`cancelQuestions(sessionId, interactionId, reason)` is a durable compare-and-set
on the exact interaction; see [Handle durable questions](handle-questions.md)
for the full rejection table. It cannot affect an active execution.

## Timeouts

The default `SerialTurnGate` applies a **ten-minute per-turn timeout**, scanned
every 30 seconds. A fenced timeout produces `TurnResponse.TimedOut` with reason
`turn_timeout` on the execution stream (and as the return of a blocking call).

```java
SerialTurnGate gate = new SerialTurnGate(Duration.ofMinutes(2));
TurnLoop loop = builder.turnGate(gate).build();
```

Ownership and fencing:

- A caller-supplied gate remains **caller-owned**: the loop wires its fence
  handler but never closes it; the caller must close it.
- The gate's timeout scanner never force-releases an executing session: it
  marks the turn `TimingOut` and releases only after the loop confirms the
  matching execution token was revoked and fenced. This prevents stale
  provider work from becoming a second authorized writer.
- `SerialTurnGate.setTimeoutCallback(Consumer<String>)` observes timeouts
  without authorizing release.

`modelTimeout(Duration)` on the builder is a different knob: it bounds
individual provider requests (default 360 s), while the gate bounds the whole
turn.

## Clear and delete sessions

| Operation | Effect | Deferred when active? |
| --- | --- | --- |
| `memory().clear(sessionId)` (same as `clearSession`) | Clears conversation memory **and** pending interaction state; preserves session metadata, queued requests, and audit rows. | Yes — cancels drains, interrupts the active turn, then applies when the turn finishes. |
| `sessions().delete(sessionId)` | Clears memory and interactions, deletes queued work including active claims, and deletes session metadata (cascading to audit rows). | Yes — same deferral. |

Both cancel an owning drain and interrupt the active turn with a reserved
reason (`session_cleared` / `session_deleted`).

## Close the loop

Prefer try-with-resources. `close()` is idempotent and thread-safe; concurrent
callers observe the same shutdown. In order, it:

1. marks the loop closed (all later calls throw `TurnLoopException`);
2. cancels active drains (claims released);
3. cancels pending question interactions with reason `shutdown`;
4. releases `AWAITING_ANSWER` gates;
5. revokes and interrupts active turns (reserved reason `shutdown`);
6. waits up to `drainTimeoutSeconds` (default 30; `0` skips the wait) for the
   executor and gate to drain, then interrupts them;
7. closes framework-owned provider model clients;
8. closes only a **framework-owned** default turn gate; a caller-supplied gate
   is left open for the caller.

## Error model recap

Expected operational states are outcomes, never exceptions: interruption,
timeout, queue acceptance, question state conflicts, and lifecycle
cancellation are all `TurnResponse.Outcome` values. Unexpected failures —
provider errors, persistence errors, internal errors — use `TurnFailedException`
(blocking) or the stream error channel, carrying safe `TurnFailure` facts
(session, turn, phase, category, timestamps, message). Do not treat those
failures as cancellation outcomes.

## Related

- [Lifecycle explanation](../explanation/turn-lifecycle.md) — gate states and
  fencing in detail.
- [Stream responses](stream-responses.md) — dispose versus interrupt.
- [Handle durable questions](handle-questions.md) — question cancellation.
- [Turn lifecycle listener and observability](observe-and-audit.md) — what
  observers see for each outcome.
