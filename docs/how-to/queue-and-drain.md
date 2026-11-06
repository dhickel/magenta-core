# Queue and drain work

Queueing persists caller intent for later oldest-first execution within one
session. The queue is durable, framework-managed, and the only consumer is
`drain`.

## Enqueue explicitly

```java
TurnResponse.Queued receipt = loop.enqueue(
    TurnRequest.of("session-1", "Run this after earlier work."));
System.out.println(receipt.messageId());   // durable message id
System.out.println(receipt.reason());      // EXPLICIT
```

The receipt is **durable acceptance, not completion**: it contains session,
message ID, enqueue timestamp, and reason — and deliberately no claim token or
acknowledgement handle. A missing session row is created on demand; the
request is then inserted in one transaction and the method returns immediately.

## Implicit queueing

`send` and `stream` produce the same `Queued` receipt when the session cannot
execute right now:

| `QueueReason` | Condition |
| --- | --- |
| `EXPLICIT` | Caller used `enqueue`. |
| `SESSION_ACTIVE` | Session is executing another turn, or a drain owns it. |
| `AWAITING_QUESTIONS` | Session is waiting for answers to a pending interaction. |

Model, tool selection, and metadata are retained with the queued request
exactly as submitted: a `null` model or tool list preserves "normal
resolution", an explicit empty tool list preserves "no tools".

## Start a drain

```java
loop.drain("session-1")
    .doOnNext(this::handleResponse)
    .doOnError(this::handleFailure)
    .blockLast();
```

`drain` is cold and caller-subscribed. On subscription, for one session, it
runs this loop:

1. **Recover expired claims** left by crashed or cancelled drains
   (`recoverExpiredClaims`).
2. **Resume a durably answered interaction** before any queued intent
   (crash recovery between an answer and its continuation).
3. **Claim the oldest available request** with a bounded lease.
4. **Execute it**, emitting its normal progress sequence and one outcome whose
   `ExecutionOrigin.Queued(messageId)` matches the receipt.
5. **Acknowledge internally** after a `Message` or `Questions` outcome.
6. **Continue after `Message`; stop after `Questions`.** If questions are
   already pending, the exact `Questions` outcome is emitted and the drain
   completes without claiming queue work.
7. Complete when the queue is empty.

## Failure, retry, and recovery behavior

| Event | Drain behavior |
| --- | --- |
| Another execution or drain owns the session | The stream fails with `TurnLoopBusyException` (thrown at subscription). |
| Interruption, timeout, or subscriber cancellation mid-claim | The claim is **released**, not acknowledged; a later drain retries it. |
| Unexpected execution failure | Claim released, error channel terminated with `TurnFailedException`. |
| Durable acknowledgement fails | Claim released and the drain fails with `TurnLoopException`; the request stays queued and is retried. |
| Claim lease expires (crash before release) | The next drain's `recoverExpiredClaims` makes the request claimable again. |
| Session deleted | `deleteBySessionId` removes queued requests including active claims. |
| `close()` | Active drains are cancelled and their claims released. |

The delivery guarantee is **at-least-once across crash windows**, not
exactly-once: a provider call can complete before the durable acknowledgement.
Tool callbacks and downstream side effects should use their own idempotency
keys when duplicate execution would be harmful.

## Tuning the claim lease

The default claim lease is 5 minutes and bounded:

```java
TurnLoop loop = TurnLoopBuilder.create()
    .dataRoot(Path.of("./data"))
    .model("local", modelConfig)
    .queueClaimLease(Duration.ofMinutes(2))
    .build();
```

Values must be positive and no longer than one hour
(`ConfigurationException` with detail key `invalid_queue_claim_lease`
otherwise). A short lease retries crashed work sooner; a long lease reduces the
chance of concurrent duplicate execution of a slow request.

## Ordering

Queue order is `(session_id, enqueued_at, message_id)` — oldest enqueue first,
ties broken by message ID. Drains process one session only; different sessions
are independent. Competing drains on the same session fail fast with
`TurnLoopBusyException`; the winner is whoever registered first.

## What is deliberately not exposed

- **Claim tokens**: `MessageQueueStore.Claim` and its token stay behind the SPI.
  The facade never hands out processing ownership.
- **Acknowledgement/release calls**: `ack` and `release` are internal to the
  drain. Callers cannot acknowledge, reorder, or drop queue items (except by
  deleting the session).
- **Queue mutation**: there is no public "remove one queued item" API.
  `clearSession` does not clear the queue; `sessions().delete` removes it with
  the session.

## Related

- [Queue and drain tutorial](../tutorials/queueing-and-persistence.md) —
  runnable restart demonstration.
- [Configure persistence](configure-persistence.md) — storage setup and schema.
- [MessageQueueStore SPI](../reference/spis.md#messagequeuestore) — the
  persistence contract.
- [Persistence schema reference](../reference/persistence-schema.md#queue-constraints)
  — queue table constraints.
