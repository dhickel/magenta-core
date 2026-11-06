# Architecture

Magenta Core is one transport-neutral Java 25 library. It owns persistent turn
execution but not an application transport. This page maps the runtime
architecture; the [turn lifecycle page](turn-lifecycle.md) explains the state
machine and durable-state design in depth.

## Boundary

The caller-facing package uses Magenta-owned records, sealed ADTs, callbacks,
exceptions, and SPIs. Spring AI models, messages, options, and provider clients
remain internal adapters confined behind the model-router boundary. There is no
Spring Boot application, application-context discovery, auto-configuration,
HTTP route, servlet layer, Actuator endpoint, or UI. A Maven enforcer rule bans
web dependencies from the artifact.

```
┌─────────────────────────────────────────────────────────────┐
│ Embedding application (CLI, desktop, HTTP/SSE, WebSocket...) │
│   uses only io.mindspice.magenta.core types                 │
└──────────────────────────┬──────────────────────────────────┘
                           │ TurnLoop facade (sealed TurnResponse, exceptions)
┌──────────────────────────▼──────────────────────────────────┐
│ TurnLoopBuilder (composition root)                          │
│  - immutable model snapshot, storage validation             │
│  - schema identity check + fresh schema install             │
│  - default collaborator resolution                          │
└──────────────────────────┬──────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────┐
│ DefaultTurnLoop (facade)                                    │
│  - per-session serialization, gate, drain, interactions,    │
│    interruption, lifecycle/observability                    │
│  - same engine stream for blocking and streaming paths      │
└──────────────────────────┬──────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────┐
│ DefaultTurnEngine (7-phase turn state machine)              │
│  - tools, thinking extraction, repair, compaction           │
│  - ask_user interaction persistence                         │
└──────────┬──────────────────────────────┬───────────────────┘
           │                              │
┌──────────▼──────────┐        ┌──────────▼──────────────────┐
│ ModelRouter (SPI-   │        │ Persistence SPIs (SQLite    │
│ neutral)            │        │ defaults; caller-replaceable)│
│  Ollama/OpenAI/     │        │ memory, sessions, queue,    │
│  DeepSeek/Responses │        │ interactions, audit         │
│  adapters           │        │                             │
└─────────────────────┘        └─────────────────────────────┘
```

`TurnLoopBuilder` is the composition root: it snapshots model registrations,
validates exactly one storage source, validates/installs the schema, supplies
defaults for optional collaborators, and constructs one `TurnLoop`. The loop
exposes turns, interactions, queue drain, sessions, memory, tools,
interruption, and lifecycle control. There is no runtime reconfiguration: the
model registry is an immutable build-time snapshot, and collaborators are
fixed at construction.

## Execution flow

1. Resolve the model alias: request → stored session → first registration.
2. Acquire per-session gate ownership (token-qualified).
3. Assemble persisted context, system prompt, and prompt contributors.
4. Invoke the internal provider adapter (blocking or streaming).
5. Separate visible content and thinking.
6. Execute selected tools through Magenta callbacks, applying the access
   policy and loop guards.
7. Persist messages, interactions, and audit facts.
8. Project progress and terminal state into the sealed `TurnResponse`
   protocol.
9. Release the gate or move it to `AWAITING_ANSWER`.

Blocking and streaming calls use the same response stream and outcome
accumulator. This prevents a blocking result and a streaming terminal event
from assigning different meanings to one execution. A drain wraps the same
engine execution per claimed request, with queued origins.

## Per-session serialization

A session has at most one authorized execution. New submissions made while
executing or awaiting answers are persisted rather than rejected. Question
answering transitions the same session back into execution. A separate
subscribed drain owns queue processing for that session and rejects competing
drains.

The default `SerialTurnGate` uses token-qualified ownership. A timeout does not
release the gate until the active execution is fenced, preventing stale
provider work from becoming a second writer. See
[Turn lifecycle, serialization, and durable state](turn-lifecycle.md).

## Durable interactions and queue

A `Questions` outcome is persisted before publication. Its interaction UUID is
separate from the execution turn ID. Answers and cancellation qualify both
session and interaction ID, so stale clients cannot mutate a newer prompt —
compare-and-set semantics with typed rejection outcomes for every race.

Queue receipts expose message identity but hide claim tokens. `drain` owns
claim, acknowledgement, release, lease recovery, and pending-interaction
ordering. This supports restart recovery while keeping queue-processing policy
out of the facade. The delivery guarantee is at-least-once.

## Persistence

Default stores share one SQLite schema under `magenta_core_*`. The schema
records a clean-break identity (`io.mindspice:magenta-core:sealed-adts-v1`,
version 1, exact resource SHA-256). Structural checks run before idempotent
schema statements so incompatible databases are rejected without partial reuse;
incompatible state is never rewritten. Historical `turnloop_*` tables are
ignored. A caller can replace stores through SPIs, but the builder still
requires one `dataRoot` or `DataSource` and initializes the framework schema.
Caller migrations (`MigrationRunner`) extend the schema contiguously and
transactionally after version 1.

## Compaction and context

Context is assembled from the durable transcript within the model's configured
window (fallback 128k tokens) with headroom. When usage reaches the configured
trigger (default 75% of the window, set with `compactionTriggerPercent`), the
next completed message turn triggers asynchronous compaction: the oldest
messages are replaced by a `CompactionStrategy` summary, and a `COMPACTION`
notice is emitted before the terminal outcome. If context
remains exhausted after mitigation, the turn fails with
`TurnFailedException.ContextExhaustedException` carrying the final
`ContextUsage`.

## Transport neutrality

`TurnResponseRenderer` produces one JSON document per protocol value. An
embedding application may map those documents to a CLI, desktop event, HTTP
response, SSE event, WebSocket frame, or another transport, but Magenta Core
does not define that framing. Subscriber cancellation and backpressure remain
Reactor concerns at the facade boundary. Observability (lifecycle listener,
metrics, audit) is SPI-based; no Micrometer or Actuator adapter ships.

## Related

- [Turn lifecycle, serialization, and durable state](turn-lifecycle.md)
- [Clean-break compatibility and tradeoffs](compatibility.md)
- [Sealed response protocol](../reference/response-protocol.md)
- [Extension SPIs](../reference/spis.md)
