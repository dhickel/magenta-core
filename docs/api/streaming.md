# Streaming and responses

`stream` is a cold Reactor `Flux<TurnResponse>`. No model work starts until subscription. A direct stream emits zero or more progress records, one expected outcome, and completion.

```java
import io.mindspice.magenta.core.TurnRequest;
import io.mindspice.magenta.core.TurnResponse;
import reactor.core.Disposable;

Disposable subscription = loop.stream(
        TurnRequest.of("session-1", "Stream an answer"))
    .doOnNext(response -> {
        if (response instanceof TurnResponse.ContentChunk chunk) {
            System.out.print(chunk.text());
        } else if (response instanceof TurnResponse.ThinkingChunk chunk) {
            // Keep model thinking separate from visible content.
            System.out.println("thinking: " + chunk.text());
        } else if (response instanceof TurnResponse.Outcome outcome) {
            System.out.println("outcome: " + outcome);
        }
    })
    .subscribe();

// Subscriber cancellation fences the execution and suppresses late writes.
subscription.dispose();
```

`Started` supplies stable session/turn correlation, `ContentChunk` and `ThinkingChunk` are separate variants, `ToolActivity` reports tool summaries, and `Notice` reports non-terminal framework facts. `streamAnswer(sessionId, interactionId, List<String>)` follows the same progress-and-outcome contract for a question continuation.

When a session is executing or awaiting questions, `stream` persists the caller intent and emits one `Queued` receipt instead of invoking a provider. `drain(sessionId)` is the cold stream for processing those durable requests; it emits the normal progress and outcome sequence for each claimed execution.

Unexpected failures terminate the `Flux` with a typed `TurnFailedException`. No failure outcome is emitted alongside the stream error. For JSON-only rendering, use `TurnResponseRenderer.json()`; transport framing remains an embedding-application concern.
