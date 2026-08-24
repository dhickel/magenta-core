# Streaming

`stream` is a cold Reactor `Flux<TurnEvent>`. No model work starts until subscription, and subscriber cancellation releases the per-session gate and active-turn registration.

```java
Disposable subscription = loop.stream(new TurnRequest(
        "session-1", "Stream an answer", "assistant", null, true, Map.of()))
    .doOnNext(event -> {
        if (event instanceof TurnEvent.Chunk chunk) {
            System.out.print(chunk.text());
        }
        if (event instanceof TurnEvent.QuestionPending pending) {
            System.out.println(pending.questionText());
        }
    })
    .subscribe();

// Stop a long-running stream safely.
subscription.dispose();
```

When a pending question blocks the session, `stream` emits a `TurnEvent.Queued` terminal event containing the durable queued-message identity instead of invoking a provider.
