# TurnLoop API

`TurnLoopBuilder.create()` is the only construction path. Configure storage, one or more `ModelConfig` values, optional Magenta SPIs, and call `build()`.

`send` returns a `TurnResult`. `stream` returns a cold Reactor `Flux<TurnEvent>`; subscription starts work and cancellation releases the session gate. While a session is awaiting an answer, new submissions are persisted as queued messages and produce a queued diagnostic/event rather than invoking a model.

Session IDs are caller-owned and session metadata is created lazily on the first valid turn. `close()` is idempotent and drains active work, pending interactions, claims, models, and executors.
