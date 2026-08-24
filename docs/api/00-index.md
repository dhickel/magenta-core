# API index

| Area | Entry points |
| --- | --- |
| Turns | `TurnLoop.send`, `TurnLoop.stream` |
| Questions | `TurnLoop.answer`, `TurnLoop.cancelQuestion` |
| Queue | `enqueue`, `claim`, `ack`, `release` |
| Sessions | `sessions()`, `memory()` |
| Tools | `tools().register`, `tools().registerToolBean` |
| Lifecycle | `interrupt`, `close` |

See [TurnLoop API](turn-loop.md), [tools and interactions](tools-and-interactions.md), and [persistence and queueing](persistence-and-queue.md). The public API uses Magenta-owned records and does not expose Spring AI or Spring context types.
