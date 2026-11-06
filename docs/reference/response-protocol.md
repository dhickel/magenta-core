# Sealed response protocol

`TurnResponse` is the complete caller-visible response hierarchy. Java 25
pattern switches over `TurnResponse.Progress`, `TurnResponse.Outcome`, and
`TurnResponse` itself can remain exhaustive without a fallback branch.

The hierarchy:

```text
TurnResponse (sealed)
├── TurnResponse.Progress (sealed)      non-terminal facts
│   ├── Started
│   ├── ContentChunk
│   ├── ThinkingChunk
│   ├── ToolActivity
│   └── Notice
└── TurnResponse.Outcome (sealed)       terminal, expected results
    ├── Message
    ├── Questions
    ├── Queued
    ├── Interrupted
    ├── TimedOut
    ├── QuestionsCancelled
    ├── QuestionsMissing
    ├── QuestionsAlreadyAnswered
    ├── QuestionsAlreadyCancelled
    └── TurnCancelled
```

## Progress variants

Every progress value carries nonblank `sessionId`, nonblank `turnId`, and a
`timestamp`; all variants are records with defensive validation.

| Variant / JSON `type` | Additional components | Meaning |
| --- | --- | --- |
| `Started` / `started` | `origin: ExecutionOrigin`, `model` (nonblank), `initialContextUsage: ContextUsage` | Always the first value of an executed turn: correlation (session, turn), origin, model, initial context facts. |
| `ContentChunk` / `content_chunk` | `text` (non-null) | Visible assistant content delta; may be empty. |
| `ThinkingChunk` / `thinking_chunk` | `text` (non-null) | Reasoning delta, kept separate from visible content. |
| `ToolActivity` / `tool_activity` | `activity: ToolActivityData` | One per tool invocation, with a sealed `Completed`/`Failed` disclosure-safe summary. |
| `Notice` / `notice` | `kind: NoticeKind`, `message` (nonblank) | Non-terminal framework notice. Current engine emissions: `COMPACTION`, `TOOL_LOOP_WARNING`, `REPAIR_APPLIED`. |

## Outcome variants

Execution outcomes (`Message`, `Questions`, `Interrupted`, `TimedOut`,
`TurnCancelled`) carry `ExecutionInfo`:

- `sessionId`, `turnId`, `origin` (sealed `ExecutionOrigin`), ordered
  `startedAt`/`completedAt`, `finalContextUsage`, and the immutable ordered
  `toolActivities` list accumulated by the turn.

| Variant / JSON `type` | Meaning |
| --- | --- |
| `Message` / `message` | Completed assistant response; `text` is nonblank (the engine fails the turn when the model produced no visible text). |
| `Questions` / `questions` | Durable interaction: `interactionId` (UUID, distinct from the execution turn ID) and ordered nonblank `questions`; persisted before publication. |
| `Queued` / `queued` | Durable receipt: `sessionId`, `messageId`, `enqueuedAt`, `reason: QueueReason`. No execution occurred; there is **no turn ID** on a queue receipt. |
| `Interrupted` / `interrupted` | Execution stopped by an accepted `interrupt` request; carries the normalized reason. |
| `TimedOut` / `timed_out` | Execution stopped after its configured turn budget; reason is `turn_timeout`. |
| `QuestionsCancelled` / `questions_cancelled` | Caller won durable question cancellation; carries `sessionId`, `interactionId`, `cancelledAt`, nonblank `reason`. |
| `QuestionsMissing` / `questions_missing` | The exact session/interaction pair was absent (never existed or already consumed). |
| `QuestionsAlreadyAnswered` / `questions_already_answered` | The exact interaction was already answered by the winning CAS. |
| `QuestionsAlreadyCancelled` / `questions_already_cancelled` | The exact interaction was already cancelled. |
| `TurnCancelled` / `turn_cancelled` | Execution cancelled by its owning subscriber or lifecycle control (reserved reasons `subscriber_cancelled`, `turn_cancelled`, `cancelled`). |

## Stream invariant

A normal execution stream emits zero or more progress values, **exactly one**
expected outcome, then completes. A queued submission or durable question
rejection can emit one outcome with no progress. A drain can contain multiple
execution sequences (one per claimed request); each claimed execution still has
one outcome. The blocking facade (`send`, `answer`) is the same stream reduced
to its outcome, so both paths can never assign different meanings to one
execution.

Unexpected failures are deliberately outside this ADT: blocking methods throw
`TurnFailedException`; reactive methods terminate with an error. Expected
lifecycle states — interruption, timeout, queue acceptance, question-state
conflicts, cancellation — remain distinguishable outcome values.

## JSON serialization

`TurnResponseRenderer.json()` (or `json(ObjectMapper)`) serializes one response
value as one JSON document with the stable `type` discriminator shown above.
There is no transport framing: HTTP, SSE, WebSocket, and CLI mapping are the
embedding application's responsibility. Deserialization works with the same
tags (the annotations are `@JsonTypeInfo(Id.NAME)` + `@JsonSubTypes`).

## Related sealed ADTs

| ADT | Variants / JSON tags | Notes |
| --- | --- | --- |
| `TurnMessage` | `System`/`system`, `User`/`user`, `Assistant`/`assistant`, `Tool`/`tool` | `Tool` requires nonblank `toolName` + `toolCallId`; all carry immutable metadata. |
| `ToolResult` | `Success`/`success`, `Failure`/`failure` | Failure requires nonblank message + `ToolFailureCategory`. |
| `ToolActivityData` | `Completed`/`completed`, `Failed`/`failed` | Both require nonblank name/call ID/summary; `Failed` adds the category. |
| `ExecutionOrigin` | `Direct`/`direct`, `Answer`/`answer`, `Queued`/`queued` | `Answer` carries the interaction ID; `Queued` the message ID. |
| `ModelConfig` | `Ollama`/`ollama`, `OpenAiCompatible`/`openai_compatible`, `DeepSeek`/`deepseek`, `Responses`/`responses` | See [Configuration reference](configuration.md). |
| `InteractionStore.Interaction` | `Pending`/`pending`, `Answered`/`answered`, `Cancelled`/`cancelled` | SPI-level durable snapshots; `Answered` requires exactly one answer per question. |
| `InteractionStore.MutationResult` | `Applied`/`applied`, `Missing`/`missing`, `AlreadyAnswered`/`already_answered`, `AlreadyCancelled`/`already_cancelled` | SPI-level CAS results; `Applied` carries the terminal snapshot. |

## Related

- [Public API reference](api.md)
- [Stream responses how-to](../how-to/stream-responses.md)
- [Turn lifecycle explanation](../explanation/turn-lifecycle.md)
