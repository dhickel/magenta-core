# Persistence, queueing, and drain

The builder initializes one fresh Magenta Core schema. A file-backed SQLite database is created beneath `dataRoot`; a caller-supplied JDBC `DataSource` is used directly. Configure exactly one storage source.

## Durable queue receipts

```java
import io.mindspice.magenta.core.TurnRequest;
import io.mindspice.magenta.core.TurnResponse;

import java.util.Map;

TurnResponse.Queued receipt = loop.enqueue(
    new TurnRequest("session-1", "Run this later", null, null,
        Map.of("source", "ui")));

System.out.println(receipt.messageId());
System.out.println(receipt.reason());
```

`Queued` is a durable receipt containing `sessionId`, stable `messageId`, `enqueuedAt`, and `QueueReason`: `EXPLICIT`, `SESSION_ACTIVE`, or `AWAITING_QUESTIONS`. It is not a processing claim and contains no claim token. `send` and `stream` use the same receipt shape whenever the session is executing or awaiting questions. Queued caller intent preserves the message, explicitly selected model and tools, metadata, and unresolved null selections until execution.

## Framework-managed drain

```java
import io.mindspice.magenta.core.TurnResponse;

import java.util.List;

List<TurnResponse> responses = loop.drain("session-1")
    .collectList()
    .block();
```

`drain` is cold and caller-subscribed. The framework performs oldest-first claim, execution, internal acknowledgement or release, and repeated processing. Each queued execution uses `ExecutionOrigin.Queued(messageId)` and emits no synthetic response for the receipt. Message and Questions outcomes are acknowledged internally; drain stops after Questions. Interrupted, timed-out, cancelled, or failed work is released and remains retryable. If questions are already pending, drain emits the durable Questions snapshot and completes without claiming work. A conflicting execution or drain reports `TurnLoopBusyException`.

Claims use bounded leases and only expired claims are recovered. Queue processing is at-least-once across process-crash windows; provider effects are not exactly-once. Applications do not acknowledge question delivery, queue receipts, or queue processing.

## Schema boundary

Fresh framework tables use the `magenta_core_*` namespace. An existing incompatible Magenta Core schema fails fast with an explicit incompatible-schema error; initialization never drops or rewrites it. Historical `turnloop_*` tables are unrelated and remain untouched.
