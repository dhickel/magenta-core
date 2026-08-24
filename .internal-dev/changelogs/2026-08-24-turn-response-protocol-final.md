# Sealed TurnResponse protocol refactor final

## Date
2026-08-24

## Git Commit

8196f698b110352c07951f1473ec88ce178a2f35

## Change Summary
Completed the clean-break replacement of the split turn result/event/diagnostic/answer and facade queue protocols with one sealed `TurnResponse` hierarchy. Added typed progress and outcomes, durable interaction IDs and ordered answers, pending-question rehydration and cancellation, typed queue receipts, framework-owned cold draining, failure/lifecycle integration, JSON-only rendering, clean-break schema initialization, and a standalone consumer fixture.

## Files
- `src/main/java/io/mindspice/magenta/core/TurnResponse.java` and response support records/enums
- `src/main/java/io/mindspice/magenta/core/TurnLoop.java`, `TurnRequest.java`, `DefaultTurnLoop.java`, and `DefaultTurnEngine.java`
- `src/main/java/io/mindspice/magenta/core/spi/InteractionStore.java`, `MessageQueueStore.java`, `TurnGate.java`, and persistence implementations
- `src/main/resources/schema.sql` and schema initialization
- `src/test/java/io/mindspice/magenta/core/**`
- `README.md`, `docs/**`, and `consumer-fixture/**`
- `.internal-dev/specifications/**`, `.internal-dev/knowledge/turn-response-protocol.md`, and archived plan record

## Behavioral Impact
`send` returns `TurnResponse.Outcome`; cold `stream`, `streamAnswer`, and `drain` return progress followed by expected outcomes. Unexpected failures use typed `TurnFailure` facts and the exception/error channel. Question answering requires exact interaction IDs and one nonblank answer per ordered question. Queue claim tokens and acknowledgement mechanics are internal; queue draining is caller-subscribed and at-least-once across crash windows. Existing incompatible `magenta_core_*` schemas fail fast without deletion or rewrite, while historical `turnloop_*` tables remain untouched.

## Specification Impact
Updated branch-specific architecture, API, and decision specifications with the sealed response protocol, exact interaction semantics, internal queue ownership, JSON tags, schema behavior, and at-least-once drain tradeoff.

## Risks
Live external provider credentials were not required. Provider implementations that ignore interruption may continue consuming resources until they return, but caller-visible timeout/cancellation paths are fenced and terminate without late framework writes. Queue processing remains intentionally at-least-once across ambiguous crash windows.

## Follow-up Items
None for the accepted pre-release branch scope.
