# Handle durable questions

The framework-internal `ask_user` tool turns a model request for input into a
durable `TurnResponse.Questions` interaction. This page is the complete
procedure: answer, rehydrate, cancel, and handle every race outcome.

## How a question becomes durable

1. The model emits a tool call named `ask_user` with a `questions` argument.
2. The engine normalizes the questions (nonblank, order preserved; a missing or
   all-blank list becomes `["The assistant has a question for you."]`).
3. A dedicated interaction UUID is created — distinct from the execution turn ID.
4. The full `Questions` snapshot is persisted **before** it is published, then
   emitted as the terminal outcome of that turn.
5. The session gate moves to `AWAITING_ANSWER`: ordinary submissions are queued
   with `QueueReason.AWAITING_QUESTIONS` instead of executing.

## Answer a pending interaction

```java
TurnResponse.Outcome first = loop.send(
    TurnRequest.of("session-1", "Help me choose a deployment target."));

if (first instanceof TurnResponse.Questions questions) {
    TurnResponse.Outcome continuation = loop.answer(
        questions.execution().sessionId(),
        questions.interactionId(),
        List.of("A single Linux server"));
    handle(continuation);
}
```

### Answer validation (before any provider call)

| Input | Result |
| --- | --- |
| `null` or empty list | `IllegalArgumentException` |
| Any blank answer | `IllegalArgumentException` |
| Wrong cardinality (not exactly one per question) | `IllegalArgumentException` |
| Valid answers, pending interaction | Continuation executes |
| Valid answers, non-pending interaction | Typed rejection outcome, no provider call |

Answers are submitted in the original question order; the continuation
transcript pairs them as `Question N:` / `Answer N:` lines.

For progress during the continuation, subscribe to
`streamAnswer(sessionId, interactionId, answers)`. It is cold and follows the
same compare-and-set interaction rules as `answer`; rejections are emitted as a
single outcome value followed by completion, and a busy session fails the
stream with `TurnLoopBusyException`.

## Rehydrate after restart

```java
loop.pendingQuestions("session-1").ifPresent(questions -> {
    display(questions.interactionId(), questions.questions());
});
```

`pendingQuestions` is **read-only**: it does not acknowledge delivery, mark the
interaction read, or change gate state. It returns the exact snapshot that was
persisted, so the interaction ID and question order match what the original
`Questions` outcome carried. At most one interaction is pending per session.

If the process died *after* answers were durably recorded but before the
continuation finished, the answered interaction is resumed by the next
`drain("session-1")` (or by a new submission) before any queued work — see
[Queue and drain work](queue-and-drain.md).

## Cancel a question

```java
TurnResponse.Outcome cancelled = loop.cancelQuestions(
    "session-1", interactionId, "User closed the prompt");
```

Mutations must include both session ID and interaction ID; a nonblank reason is
required. The returned outcome is one of:

| Outcome | When |
| --- | --- |
| `QuestionsCancelled` | Cancellation won the CAS; carries `sessionId`, `interactionId`, `cancelledAt`, `reason`. |
| `QuestionsMissing` | The exact session/interaction pair does not exist (never existed, or was consumed by an earlier answer). |
| `QuestionsAlreadyAnswered` | Another caller's answer won; the interaction was already answered. |
| `QuestionsAlreadyCancelled` | Cancellation already won; your call is an idempotent duplicate. |

A `cancelQuestions` call while the session is executing or owned by a drain
throws `TurnLoopBusyException` instead of returning an outcome.

## Stale, duplicate, and cross-session submissions

The session+interaction ID qualification means a stale client cannot affect a
newer interaction:

```java
// First interaction answered successfully -> interaction row consumed.
loop.answer("session", firstId, List.of("answer"));            // Message

// A late duplicate cannot replay the continuation.
loop.answer("session", firstId, List.of("duplicate"));         // QuestionsMissing

// A stale cancellation cannot touch a newer pending interaction.
loop.cancelQuestions("session", firstId, "late cancel");       // QuestionsMissing

// Wrong session can never reach the interaction.
loop.answer("other-session", secondId, List.of("x"));          // QuestionsMissing

// Repeating a cancellation is an idempotent typed rejection.
loop.cancelQuestions("session", secondId, "stop");             // QuestionsCancelled
loop.cancelQuestions("session", secondId, "stop again");       // QuestionsAlreadyCancelled
```

Rejections are observed like any outcome: the lifecycle listener receives them
exactly once, and the audit writer records a rejection event.

## Interruption interaction

`interrupt(turnId, reason)` targets an active execution. If the turn ID belongs
to a pending question interaction (the turn that produced it), the interrupt
**cancels that interaction** durably with the given reason and emits a
`QuestionsCancelled` outcome — it does not leave the session awaiting answers.

## Close behavior

`loop.close()` cancels any pending interaction with reason `shutdown` before
releasing resources, so a restart after a graceful close has no dangling
`PENDING` interaction.

## What is deliberately not exposed

- No acknowledge-delivery API: the interaction store's `pending`/`find` never
  mutate delivery state.
- No partial answers: an interaction is either `PENDING`, fully `ANSWERED`
  (one nonblank answer per question), or `CANCELLED` — there is no partial
  state.
- No interaction state machine leak: callers see only the sealed
  `InteractionStore.Interaction` variants if they implement the SPI.

## Related

- [Durable questions tutorial](../tutorials/durable-questions.md) — runnable
  end-to-end flow including restart rehydration.
- [Sealed response protocol](../reference/response-protocol.md) — the four
  question rejection outcomes.
- [Extension SPIs](../reference/spis.md#interactionstore) — the store contract.
- [Queue and drain work](queue-and-drain.md) — `AWAITING_QUESTIONS` behavior.
