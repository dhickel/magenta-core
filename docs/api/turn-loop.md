# TurnLoop API

`TurnLoopBuilder.create()` is the programmatic composition root. Register one or more `ModelConfig` values, select exactly one `dataRoot(Path)` or `dataSource(DataSource)`, add optional Magenta-owned SPIs, and call `build()`.

## Request and execution

`TurnRequest` contains `sessionId`, `message`, optional `model`, optional `toolNames`, and `metadata`. The facade method selects blocking or streaming behavior; the request has no stream selector.

```java
import io.mindspice.magenta.core.TurnRequest;
import io.mindspice.magenta.core.TurnResponse;

import java.util.Map;

TurnRequest request = new TurnRequest(
    "session-1", "Summarize this", null, null, Map.of("source", "ui"));

TurnResponse.Outcome outcome = loop.send(request);
```

`send` blocks until one expected `TurnResponse.Outcome` is available. A direct execution normally returns `Message` or `Questions`; interruption, timeout, and cancellation use their typed outcomes. A non-idle session returns `Queued` instead of starting another provider execution. Non-idle includes both an executing turn and a pending question interaction.

`stream` is cold. A direct subscription emits zero or more `Progress` values, exactly one expected `Outcome`, and then completes. Unexpected execution failures throw from `send` or terminate the stream with `TurnFailedException`; they are not an outcome.

Execution-related outcomes (`Message`, `Questions`, `Interrupted`, `TimedOut`, and `TurnCancelled`) carry `ExecutionInfo` with session and turn IDs, timestamps, final context usage, tool activities, and a typed `ExecutionOrigin`:

- `Direct` for a normal submission;
- `Answer(interactionId)` for a question continuation;
- `Queued(messageId)` for framework-managed queue execution.

`Progress` records all carry session ID, turn ID, and timestamp. `Message` text is nonblank; `Questions` exposes a dedicated interaction ID and ordered nonempty questions. `Queued` and `QuestionsCancelled` are control outcomes with their own typed fields. A `Queued` receipt is not an execution and therefore has no execution turn or origin. `ExecutionOrigin` uses typed `direct`, `answer`, and `queued` JSON subtypes rather than nullable IDs.

## Response JSON

`TurnResponseRenderer.json()` produces one JSON document. The discriminator property is `type` and its stable values are exactly:

`started`, `content_chunk`, `thinking_chunk`, `tool_activity`, `notice`, `message`, `questions`, `queued`, `interrupted`, `timed_out`, `questions_cancelled`, `turn_cancelled`.

Embedding applications own transport framing and can map these JSON documents to their transport of choice.

## Related operations

Use `answer(sessionId, interactionId, List<String>)` or the cold `streamAnswer(sessionId, interactionId, List<String>)` for an ordered question continuation. Use `pendingQuestions(sessionId)` to rehydrate a durable pending interaction and `cancelQuestions(...)` to consume it with a typed `QuestionsCancelled` outcome. Use `enqueue(TurnRequest)` for an explicit durable receipt and `drain(sessionId)` for caller-subscribed framework-managed processing.

Session IDs are caller-owned and session metadata is created lazily. `close()` is idempotent and closes owned model clients, executors, and coordination resources.
