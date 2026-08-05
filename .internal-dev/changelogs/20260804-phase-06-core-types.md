# Changelog — Phase 06: Core Types and Records

## Date
2026-08-04

## Git Commit

8b6cdcc19f9caa622e29c2e3889608c6f9f3a7b0

## Change Summary
Created the framework-native data-carrier types in the `mindspice-core` module (package `io.mindspice.core`, user-approved naming override of the plan's `io.mindspice.turnloop`):
- 16 ledger types matching handoff Appendix A: `TurnRequest`, `TurnResult`, `TurnMessage` (4 static factories), `ContextUsage` (utilizationPercent), `TurnDiagnostic` (inner `OutcomeKind` enum), `ToolActivityData`, `ToolActivityStatus`, `NoticeKind` (5 values with defaultMessage), `TurnContext` (mutable turn-scoped carrier with typed `AttributeKey<T>` access, messagesToPersist, currentContextUsage, toolRoundCount, activeTools), `AttributeKey`, `CompactionContext`, `CompactionResult`, `SessionFilter` (with `all()`), `ConversationSession`, `PendingMessage`, `ClaimedMessage`.
- 3 dependency types: `TurnPhase` (7-value enum), `ToolCallback`, `ToolDefinition`.
- 2 package annotations: `NonNull`, `Nullable`.
- POM dependencies: required jackson-databind, reactor-core, spring-boot-starter-jdbc, sqlite-jdbc 3.50.3.0, jtokkit 1.1.0; optional spring-ai-starter-model-ollama, commonmark 0.24.0, owasp-java-html-sanitizer 20240325.1.

## Files
21 new Java files under `mindspice-core/src/main/java/io/mindspice/core/` + `mindspice-core/pom.xml`.

## Behavioral Impact
The framework module now compiles independently with zero Spring AI or servlet imports in its public data types. These types are the framework's public data contract; `TurnContext` is the turn-scoped carrier with typed attribute access and thread-safe reads from async callbacks.

## Specification Impact
None. The framework contract is specified in the plan (concepts.md / handoff Appendix A) and realized here per that specification; no existing Magenta specification is affected.

## Risks
- Optional `spring-ai-starter-model-ollama` transitively pulls `spring-web` through the optional chain (spring-ai-retry → spring-web); Enforcer correctly ignores optional transients today, but if Spring AI later makes spring-web non-optional in the ollama starter, the Enforcer ban would trip (Phase 08 concern).
- `jtokkit 1.1.0` chosen at implementation time per phase guidance (verify against handoff if a newer version is preferred later).

## Follow-up Items
- Phase 07: TurnEvent sealed hierarchy, exception hierarchy, and the 20 SPI interfaces (replacing the temporary Phase 03 SPIs).
