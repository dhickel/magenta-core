# Magenta Core documentation

Magenta Core is a transport-neutral Java 25 library for embedding persistent AI
turn loops. Choose a page by what you need to do or learn.

## Tutorials

Learning-oriented, end-to-end paths. Each has prerequisites, complete code, and
expected behavior; work through them in order.

- [Build and run your first turn loop](tutorials/getting-started.md) — first
  blocking turn and exhaustive `TurnResponse` handling.
- [Stream a turn](tutorials/streaming-turns.md) — content, thinking, tool
  activity, and final outcomes on a cold `Flux`.
- [Handle durable questions](tutorials/durable-questions.md) — `ask_user`,
  rehydration, ordered answers, cancellation, and restart recovery.
- [Add tools](tutorials/tool-use.md) — `ToolCallback` and annotated tools,
  typed results, safe summaries, and loop protection.
- [Queue work and persist across restarts](tutorials/queueing-and-persistence.md)
  — queue/drain, `dataRoot` versus caller-owned `DataSource`.

## How-to guides

Task-oriented procedures with exact failure and edge behavior.

- [Consume a response stream](how-to/stream-responses.md)
- [Handle durable questions](how-to/handle-questions.md)
- [Queue and drain work](how-to/queue-and-drain.md)
- [Register tool callbacks](how-to/register-tools.md)
- [Configure and route models](how-to/configure-models.md)
- [Configure persistence](how-to/configure-persistence.md)
- [Interrupt, cancel, and close safely](how-to/lifecycle-and-cancellation.md)
- [Manage sessions and conversation memory](how-to/manage-sessions-and-memory.md)
- [Observe and audit turns](how-to/observe-and-audit.md)

## Reference

Exact contracts and supported values.

- [Public API reference](reference/api.md)
- [Configuration reference](reference/configuration.md)
- [Sealed response protocol](reference/response-protocol.md)
- [Persistence schema](reference/persistence-schema.md)
- [Extension SPIs](reference/spis.md)

## Explanation

Architecture and design rationale.

- [Architecture](explanation/architecture.md)
- [Turn lifecycle, serialization, and durable state](explanation/turn-lifecycle.md)
- [Clean-break compatibility and tradeoffs](explanation/compatibility.md)

## Executable examples

- [`consumer-fixture/`](../consumer-fixture/README.md) is the repository's
  standalone Java 25 consumer. It exercises blocking and streaming turns,
  durable questions, queue drain, both storage modes, JSON rendering, and
  exhaustive matching over the sealed public ADTs without Spring Boot or
  external credentials. It prints `consumer-ok`.
- The tutorial programs (`FirstTurn`, `StreamingConsumer`, `DeployWizard`,
  `Toolbox`, `QueueDemo`) are complete, self-contained sources embedded in the
  tutorial pages; each compiles against the built JAR with plain `javac
  --release 25`.
