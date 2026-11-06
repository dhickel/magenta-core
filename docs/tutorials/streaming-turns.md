# Stream a turn: content, thinking, tool activity, and final outcomes

This tutorial builds a live consumer that renders a turn as it happens: visible
content, model thinking kept separate, tool activity, framework notices, and
finally the terminal outcome. It uses the same execution engine as `send` — only
the observation mode differs.

## Prerequisites

- Complete [Build and run your first turn loop](getting-started.md).
- A model endpoint that supports streaming. Ollama's `llama3.2` does; if you use
  a custom capability provider, the alias must report `STREAMING`
  (see [Configure and route models](../how-to/configure-models.md)).
- Familiarity with Project Reactor's `Flux` (the library's streaming type).

## What you are building

`loop.stream(TurnRequest)` returns a **cold** `Flux<TurnResponse>`:

- Creating the `Flux` does nothing; **subscribing** starts the execution.
- The stream emits zero or more `TurnResponse.Progress` values, then exactly one
  expected `TurnResponse.Outcome`, then completes.
- Unexpected failures terminate the stream's error channel with
  `TurnFailedException` (or a more specific `TurnLoopException`); they are never
  emitted as outcome values.

The progress family is also sealed, so you can render it exhaustively:

| Progress variant | When it is emitted |
| --- | --- |
| `Started` | First value of every execution; carries turn ID, origin, model, initial context usage. |
| `ContentChunk` | Visible assistant text deltas. |
| `ThinkingChunk` | Reasoning deltas, kept separate from visible content. |
| `ToolActivity` | One per tool invocation, with a disclosure-safe `Completed`/`Failed` summary. |
| `Notice` | Non-terminal framework events (repair applied, tool-loop warning). |

## The program

<!-- tutorial-compile: StreamingConsumer.java -->
```java
import io.mindspice.magenta.core.ModelConfig;
import io.mindspice.magenta.core.TurnLoop;
import io.mindspice.magenta.core.TurnLoopBuilder;
import io.mindspice.magenta.core.TurnRequest;
import io.mindspice.magenta.core.TurnResponse;
import io.mindspice.magenta.core.ToolActivityData;
import reactor.core.Disposable;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class StreamingConsumer {

    public static void main(String[] args) throws InterruptedException {
        try (TurnLoop loop = TurnLoopBuilder.create()
                .dataRoot(Path.of("./data"))
                .model("local", ModelConfig.ollama()
                    .alias("local")
                    .remoteModelName("llama3.2")
                    .remoteEndpoint("http://localhost:11434")
                    .contextLength(32_000)
                    .thinkLevel(0)
                    .build())
                .build()) {

            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<String> activeTurn = new AtomicReference<>();

            Disposable subscription = loop.stream(TurnRequest.of(
                    "stream-session", "Explain how streaming works in one paragraph."))
                .subscribe(
                    response -> {
                        if (response instanceof TurnResponse.Progress progress) {
                            renderProgress(progress, activeTurn);
                        } else if (response instanceof TurnResponse.Outcome outcome) {
                            System.out.println();
                            System.out.println("== " + describeOutcome(outcome));
                        }
                    },
                    error -> {
                        System.out.println("stream failed: " + error.getMessage());
                        done.countDown();
                    },
                    done::countDown);

            if (!done.await(120, TimeUnit.SECONDS)) {
                subscription.dispose();
                System.out.println("timed out waiting for the stream");
            }

            String turnId = activeTurn.get();
            if (turnId != null) {
                System.out.println("observed turn id: " + turnId);
            }
        }
    }

    /** Exhaustive over the sealed Progress hierarchy; no default branch. */
    private static void renderProgress(TurnResponse.Progress progress,
                                       AtomicReference<String> activeTurn) {
        switch (progress) {
            case TurnResponse.Started started -> {
                activeTurn.set(started.turnId());
                System.out.println("[started] turn=" + started.turnId()
                    + " model=" + started.model()
                    + " origin=" + started.origin());
            }
            case TurnResponse.ContentChunk chunk -> System.out.print(chunk.text());
            case TurnResponse.ThinkingChunk chunk ->
                System.out.println("[thinking] " + chunk.text());
            case TurnResponse.ToolActivity activity -> {
                var data = activity.activity();
                String line = switch (data) {
                    case ToolActivityData.Completed completed ->
                        "tool " + completed.toolName() + " completed: " + completed.summary();
                    case ToolActivityData.Failed failed ->
                        "tool " + failed.toolName() + " failed: " + failed.summary()
                            + " (" + failed.category() + ")";
                };
                System.out.println("[tool] " + line);
            }
            case TurnResponse.Notice notice ->
                System.out.println("[notice] " + notice.kind() + ": " + notice.message());
        }
    }

    private static String describeOutcome(TurnResponse.Outcome outcome) {
        return switch (outcome) {
            case TurnResponse.Message message -> "message: " + message.text();
            case TurnResponse.Questions questions ->
                "questions: " + questions.questions();
            case TurnResponse.Queued queued ->
                "queued: " + queued.reason();
            case TurnResponse.Interrupted interrupted -> "interrupted: " + interrupted.reason();
            case TurnResponse.TimedOut timedOut -> "timed out: " + timedOut.reason();
            case TurnResponse.QuestionsCancelled cancelled ->
                "questions cancelled: " + cancelled.reason();
            case TurnResponse.QuestionsMissing missing ->
                "questions missing: " + missing.interactionId();
            case TurnResponse.QuestionsAlreadyAnswered answered ->
                "already answered: " + answered.interactionId();
            case TurnResponse.QuestionsAlreadyCancelled cancelled ->
                "already cancelled: " + cancelled.interactionId();
            case TurnResponse.TurnCancelled cancelled -> "turn cancelled: " + cancelled.reason();
        };
    }
}
```

Compile and run it exactly like [the first tutorial](getting-started.md#4-build-and-run),
substituting `StreamingConsumer` for `FirstTurn`.

## Expected behavior

```text
[started] turn=0b1c... model=local origin=Direct()
[thinking] The user asks about streaming...
Streaming emits incremental deltas...
== message: Streaming emits incremental deltas...
observed turn id: 0b1c...
```

Observations that hold regardless of model:

- `Started` is always first, with the turn ID you can later pass to `interrupt`.
- Content chunks are printed without newlines; thinking goes to a separate line.
- Exactly one outcome is emitted; the stream then completes and the latch opens.
- Tool calls, if the model makes any, appear as `ToolActivity` between content
  deltas, in execution order.

## Failure and edge behavior

| Situation | What you observe |
| --- | --- |
| Model alias lacks `STREAMING` | The stream's error channel carries `ConfigurationException` before any execution starts. |
| Session is already executing or awaiting answers | The stream emits **only** `Queued` and completes; no `Started`, no progress. |
| Subscriber disposes the subscription | The execution is fenced. The cancelling subscription receives no synthetic terminal value; lifecycle observers (and a later drain) see `TurnCancelled`. |
| `interrupt(turnId, reason)` during the stream | The stream emits `Interrupted` as its terminal outcome (see [lifecycle how-to](../how-to/lifecycle-and-cancellation.md)). |
| Unexpected provider/persistence failure | The error channel terminates with `TurnFailedException`; call `failure()` on it for safe `TurnFailure` facts. |

## Blocking convenience forms

When you only need the terminal value, `collectList().block(...)` gives you the
full ordered list, which lets you verify the "one outcome" invariant:

```java
List<TurnResponse> all = loop.stream(TurnRequest.of("s", "hi"))
    .collectList()
    .block(Duration.ofSeconds(30));

long outcomes = all.stream().filter(TurnResponse.Outcome.class::isInstance).count();
assert outcomes == 1;
```

Or use `blockLast()` for just the outcome. `send` is internally the same stream
reduced to its outcome — see [the architecture explanation](../explanation/architecture.md).

## What streaming deliberately does not do

- No HTTP, SSE, or WebSocket framing: `TurnResponseRenderer` produces one JSON
  document per value, and the embedding application owns transport framing.
- No per-chunk acknowledgement: ordering and completion are the stream contract;
  durable correctness is provided by the outcome and persistence layer, not by
  chunk-level acks.
- No progress beyond the sealed `Progress` family: chunk-level details such as
  token counts or provider-native fields are intentionally not projected.

## Next steps

- [Handle durable questions](durable-questions.md) — `streamAnswer` follows the
  same cold-stream contract for question continuations.
- [Queue and drain work](queueing-and-persistence.md) — a drain emits one
  execution sequence per claimed request.
- Reference: [Sealed response protocol](../reference/response-protocol.md).
