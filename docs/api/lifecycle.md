# Lifecycle, interruption, and observability

Interrupt an active turn with the `turnId` from `TurnEvent.Started`:

```java
loop.interrupt(turnId, "Operator stopped the request");
```

Register Magenta-owned lifecycle and metrics SPIs directly on the builder:

```java
TurnLoop loop = TurnLoopBuilder.create()
    .dataRoot(Path.of("data"))
    .model("assistant", config)
    .turnLifecycleListener(result -> auditSink.accept(result.diagnostic()))
    .turnLoopMetrics(new TurnLoopMetrics() {
        public void recordTurnDuration(String model, String outcome, long durationMs) { }
        public void recordToolCall(String toolName, String status) { }
        public void setActiveTurns(long count) { }
        public void setQueueDepth(String sessionId, long depth) { }
    })
    .build();
```

`close()` is idempotent. It drains active work, clears pending interactions, releases claims, closes owned provider clients, and shuts down executors. Audit writes use the framework's `magenta_core_audit_log` table by default or a caller-supplied `AuditWriter`.
