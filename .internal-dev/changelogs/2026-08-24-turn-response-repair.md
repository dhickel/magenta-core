# Sealed TurnResponse repair

## Date
2026-08-24

## Change Summary
Repaired timeout termination, answered-continuation drain recovery, exact Questions execution snapshots, queue acknowledgement failure handling, reducer ownership, and the TurnGate acquire contract for the clean-break sealed response protocol.

## Files
- `src/main/java/io/mindspice/magenta/core/DefaultTurnLoop.java`
- `src/main/java/io/mindspice/magenta/core/DefaultTurnEngine.java`
- `src/main/java/io/mindspice/magenta/core/ContextManager.java`
- `src/main/java/io/mindspice/magenta/core/spi/TurnGate.java`
- `src/test/java/io/mindspice/magenta/core/DefaultTurnLoopInteractionTest.java`
- `src/test/java/io/mindspice/magenta/core/TurnResponseProtocolTest.java`
- `.internal-dev/knowledge/turn-response-protocol.md`

## Behavioral Impact
Timeout fencing now terminates the shared caller-visible execution stream with one `TimedOut` outcome and cancels the provider subscription while retaining late-write fencing. Drain resumes exact answered interactions before queue claims. Questions persist the asking turn's real start, projected final durable context usage, and accumulated tool activities. Failed queue acknowledgement stops draining and releases the retained claim for retry. `ResponseAccumulator` is the sole active reducer, and `TurnGate.AcquireResult` no longer contains a claim-token-bearing queue branch.

## Specification Impact
Updated the branch-specific `architecture.md`, `api.md`, and `decisions.md` entries to record the sealed response protocol, internal queue ownership, exact interaction semantics, and at-least-once drain behavior.

## Risks
Provider code that ignores interruption may continue consuming external resources until it returns, but it no longer blocks callers or retains write authority. Queue acknowledgement remains at-least-once across ambiguous store failures.

## Follow-up Items
None.
