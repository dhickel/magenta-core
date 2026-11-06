# Consume a response stream

Use `TurnLoop.stream` when the embedding application needs progress before the
terminal outcome: incremental text, thinking, tool activity, and notices.

## Procedure

1. Build the loop as usual. The selected model must support streaming.
2. Call `loop.stream(TurnRequest.of(sessionId, message))` and subscribe.
   Subscription starts the execution; the returned `Flux` is **cold**.
3. Handle every `TurnResponse.Progress` variant and every `TurnResponse.Outcome`
   variant with exhaustive switches (no default branches).
4. Treat the error channel as the failure path: subscribe's second consumer or
   `doOnError`.

```java
import io.mindspice.magenta.core.TurnRequest;
import io.mindspice.magenta.core.TurnResponse;
import reactor.core.Disposable;

Disposable subscription = loop.stream(
        TurnRequest.of("session-1", "Explain the result."))
    .subscribe(
        response -> {
            if (response instanceof TurnResponse.Progress progress) {
                renderProgress(progress);
            } else if (response instanceof TurnResponse.Outcome outcome) {
                completeTurn(outcome);
            }
        },
        error -> reportFailure(error));
```

Exhaust the progress family without a fallback branch:

```java
static void renderProgress(TurnResponse.Progress progress) {
    switch (progress) {
        case TurnResponse.Started value ->
            System.out.println("turn=" + value.turnId());
        case TurnResponse.ContentChunk value ->
            System.out.print(value.text());
        case TurnResponse.ThinkingChunk value ->
            storeThinkingSeparately(value.text());
        case TurnResponse.ToolActivity value ->
            showToolActivity(value.activity());
        case TurnResponse.Notice value ->
            showNotice(value.kind(), value.message());
    }
}
```

## Exact emission contract

| Property | Contract |
| --- | --- |
| Cold | Creating the `Flux` starts nothing; each subscription starts one execution. |
| Ordering | Zero or more `Progress` values, then **exactly one** expected `Outcome`, then `onComplete`. `Started` is always the first progress value when execution starts. |
| Queued submission | When the session is not idle, the stream emits only `Queued` (with its `QueueReason`) and completes — no `Started`, no progress. |
| Durable rejection | `streamAnswer` with a missing/terminal interaction emits exactly one typed rejection outcome (`QuestionsMissing`, `QuestionsAlreadyAnswered`, `QuestionsAlreadyCancelled`) and completes without provider execution. |
| Drain streams | A drain can contain several execution sequences (one per claimed request); each still ends in one outcome. |
| Unexpected failure | The error channel terminates with `TurnFailedException` (or a specialized `TurnLoopException`); never an outcome value. |
| Timestamps | Every progress value carries nonblank session ID, turn ID, and timestamp. |

## Ordering and content guidance

- Keep `ThinkingChunk` separate from visible `ContentChunk` output; they are
  distinct protocol values because reasoning and answer text are distinct
  concerns (redaction, UI placement, logging).
- `ToolActivity` summaries are disclosure-safe: they contain tool name, call
  ID, summary, and (for failures) the `ToolFailureCategory` — never raw
  arguments or exception traces. Raw arguments are available only to your
  `AuditWriter` and `ToolResultSummarizer`, never on the stream.
- `Notice` kinds are `NoticeKind` values. The current engine emits
  `COMPACTION`, `TOOL_LOOP_WARNING`, and `REPAIR_APPLIED`; the other declared
  kinds (`CONTEXT_EXHAUSTED`, `BUFFER_OVERFLOW`) are part of the protocol
  contract and must be handled by consumers regardless.

## Blocking forms and timeouts

```java
// Full ordered list (useful for tests): throws on error channel.
List<TurnResponse> all = loop.stream(TurnRequest.of("s", "hi"))
    .collectList()
    .block(Duration.ofSeconds(30));

// Just the terminal outcome.
TurnResponse.Outcome outcome = loop.stream(TurnRequest.of("s", "hi"))
    .blockLast(Duration.ofSeconds(30));
```

`send` is the same execution reduced to its outcome. If `block` times out, the
subscription is disposed — which fences the execution (see below) — so use
`interrupt` when you need a controlled terminal `Interrupted` outcome.

## Stopping a stream

There are three distinct controls; they are **not** interchangeable:

| Control | Effect |
| --- | --- |
| `Disposable.dispose()` | Stops that subscriber. The execution is fenced and late persistence is suppressed; lifecycle observers receive a `TurnCancelled` outcome. The cancelling subscription receives no synthetic terminal value. |
| `loop.interrupt(turnId, reason)` | Requests a cooperative interrupt at the engine's next interruption point; the active stream terminates with `Interrupted`. |
| `loop.cancelQuestions(sessionId, interactionId, reason)` | Ends a pending question interaction (durable CAS); it does not affect an active execution stream. |

Capture the turn ID from `Started` to make `interrupt` usable:

```java
AtomicReference<String> turn = new AtomicReference<>();
// in renderProgress:
case TurnResponse.Started value -> turn.set(value.turnId());
// later:
boolean accepted = loop.interrupt(turn.get(), "User pressed stop");
```

## Failure and edge behavior

- **`ConfigurationException`** (`streaming_unsupported`) — the selected model
  does not advertise `STREAMING`; the error arrives on the error channel before
  any execution.
- **`TurnFailedException`** — unexpected provider/tool/persistence failure.
  Call `failure()` for safe `TurnFailure` facts (session, turn, phase, category,
  timestamps, message).
- **Subscriber cancellation during a drain** — the current claim is released
  (not acknowledged) and the drain stream completes without an error.
- **Backpressure** — the facade does not buffer unboundedly; standard Reactor
  backpressure applies at the boundary. `drain` uses a `BUFFER` sink strategy.

## What is deliberately not exposed

- No SSE/HTTP/WebSocket framing: `TurnResponseRenderer.json()` renders one JSON
  document per value; the embedding application owns transport framing.
- No chunk-level acknowledgement or ordering tokens: the stream contract is
  ordering plus exactly one outcome; durability comes from the persistence
  layer.
- No provider-native chunk fields: the sealed `Progress` family is the complete
  caller-visible projection.
- No separate "stream event" ADT: blocking and streaming share the same
  `TurnResponse` values, so a blocking result and a streamed terminal event can
  never assign different meanings to one execution.

## Related

- [Sealed response protocol](../reference/response-protocol.md) — complete
  variant table.
- [Streaming turns tutorial](../tutorials/streaming-turns.md) — runnable example.
- [Interrupt, cancel, and close safely](lifecycle-and-cancellation.md) — stream
  controls in context.
- [Queue and drain work](queue-and-drain.md) — drain streams.
