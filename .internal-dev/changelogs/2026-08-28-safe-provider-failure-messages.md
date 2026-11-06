# Safe Provider Failure Messages

## Date
2026-08-28

## Change Summary
Unexpected turn failures now retain their typed category while carrying a useful, disclosure-safe provider/root-cause message instead of always replacing it with category-generic text.

## Files
- `src/main/java/io/mindspice/magenta/core/DefaultTurnEngine.java`
- `src/main/java/io/mindspice/magenta/core/DefaultTurnLoop.java`
- `src/main/java/io/mindspice/magenta/core/DefaultModelRouter.java`
- `src/main/java/io/mindspice/magenta/core/ModelRouter.java`
- `src/main/java/io/mindspice/magenta/core/TurnFailedException.java`
- `src/test/java/io/mindspice/magenta/core/DefaultTurnLoopInteractionTest.java`
- `.internal-dev/specifications/architecture.md`
- `.internal-dev/specifications/api.md`
- `.internal-dev/knowledge/turn-response-protocol.md`

## Behavioral Impact
Provider/root-cause text is unwrapped, exact-key and capability/approval-secret redacted, stripped of control characters, and bounded to 500 characters before entering `TurnFailure`. Generic category text remains the fallback. `TurnFailedException` retains the original cause internally. A live DeepSeek Responses streaming probe with a real echo tool completed a two-invocation tool round trip successfully.

## Specification Impact
Updated the branch-specific architecture and API contracts to define useful bounded failure messages and internal cause retention.

## Risks
Provider SDK messages vary by transport and endpoint. HTTP status is retained when the SDK includes it, but provider-declared SSE error events can expose only the fields supplied by that event.

## Follow-up Items
None.
