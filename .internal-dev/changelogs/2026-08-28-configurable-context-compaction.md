# Configurable context compaction

## Date
2026-08-28

## Change Summary
Added a caller-configurable context compaction threshold and a streamed notice after successful compaction.

## Files
- `src/main/java/io/mindspice/magenta/core/ContextManager.java`
- `src/main/java/io/mindspice/magenta/core/DefaultTurnEngine.java`
- `src/main/java/io/mindspice/magenta/core/TurnLoopBuilder.java`
- `src/test/java/io/mindspice/magenta/core/ContextManagerTest.java`
- `src/test/java/io/mindspice/magenta/core/DefaultTurnLoopInteractionTest.java`
- `src/test/java/io/mindspice/magenta/core/TurnLoopBuilderTest.java`
- `docs/reference/configuration.md`
- `.internal-dev/specifications/architecture.md`
- `.internal-dev/knowledge/turn-response-protocol.md`

## Behavioral Impact
`compactionTriggerPercent(int)` defaults to 75 and clamps to 1–99. The same threshold now produces `ContextUsage.triggerTokens` and controls compaction. A successful guarded replacement emits one `COMPACTION` notice with the compacted message count before the terminal outcome.

## Specification Impact
Updated the branch-specific architecture contract to define the configurable threshold and observable successful-compaction notice ordering.

## Risks
Token usage remains estimator-dependent. Terminal usage is calculated before post-turn compaction, and revoked or cancelled streams intentionally do not receive the maintenance notice.

## Follow-up Items
None.
