# API index

| Area | Entry points |
| --- | --- |
| Turns | `TurnLoop.send`, `TurnLoop.stream` |
| Questions | `answer`, `streamAnswer`, `pendingQuestions`, `cancelQuestions` |
| Queue and drain | `enqueue`, `drain` |
| Sessions | `sessions()`, `memory()`, `clearSession` |
| Tools | `tools().register`, `tools().registerToolBean` |
| Lifecycle | `interrupt`, `close` |
| JSON | `TurnResponseRenderer.json()` |

The caller-visible protocol is the sealed `TurnResponse` hierarchy. `Progress` variants are `Started`, `ContentChunk`, `ThinkingChunk`, `ToolActivity`, and `Notice`. `Outcome` variants are `Message`, `Questions`, `Queued`, `Interrupted`, `TimedOut`, `QuestionsCancelled`, and `TurnCancelled`.

See [TurnLoop API](turn-loop.md), [streaming and responses](streaming.md), [tools and questions](tools-and-interactions.md), [lifecycle](lifecycle.md), and [persistence and queueing](persistence-and-queue.md). The public API uses Magenta-owned records and does not expose provider or application-context types.
