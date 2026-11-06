# Sealed ADT Consolidation

## Date

2026-08-24

## Git Commit

a58078069f095f3b1b61a50b17ed2938e12dce0d

## Change Summary

Consolidated the Magenta Core framework around sealed ADTs and exhaustive pattern matching following the TurnResponse refactor. Added typed tool results and activities, sealed conversation messages, sealed durable interaction snapshots and mutation outcomes, typed question-rejection outcomes, sealed provider model configuration, immutable sealed turn-gate state, and a new schema identity with structural incompatibility checks.

## Files

- Public protocol and configuration: `TurnResponse`, `ToolResult`, `ToolActivityData`, `TurnMessage`, `ModelConfig`, `TurnLoop`, `ToolCallback`.
- Engine and adapters: `DefaultTurnEngine`, `DefaultTurnLoop`, `DefaultToolRegistry`, provider adapters, memory/audit/metrics adapters, `SerialTurnGate`.
- Persistence: `InteractionStore`, SQLite/in-memory interaction stores, `MigrationRunner`, `schema.sql`, conversation memory.
- Tests and fixture: protocol, persistence, race/restart, provider, gate, schema safety tests, and `consumer-fixture`.
- Documentation/specification: README, API/technical docs, branch architecture/API/decision/schema contracts, and provider-routing knowledge.

## Behavioral Impact

This is a deliberate source and schema clean break. Tool callbacks return `ToolResult`; message roles, interaction lifecycle, mutation results, and provider configurations are closed pattern-matchable families. Missing/already-consumed question operations return typed expected outcomes without provider execution. Unexpected failures remain on the exception/error channel. Existing incompatible Magenta Core schemas are rejected without mutation; historical `turnloop_*` tables remain untouched. API keys serialize in ModelConfig JSON by decision but are redacted from framework diagnostics and string representations.

## Specification Impact

Updated branch-specific API, architecture, decisions, and schema specifications to describe the sealed ADT families, typed question rejection outcomes, schema identity/structural checks, and open SPI boundaries.

## Risks

The public callback, message, interaction SPI, provider configuration, and persistence contracts are source-incompatible by design. Existing Magenta Core databases require a fresh explicitly selected storage location. Live external provider credentials were not used; deterministic provider transport tests passed.

## Follow-up Items

None within the accepted job scope.

## Validation

- All domain Luna xhigh validation gates passed after repair.
- Senior repair completed for SQLite table-scoped index metadata validation; focused confirmation passed.
- `mvn clean verify`: 96 tests passed after final schema, consumer, migration-restart, contention, and typed tool-pipeline coverage.
- Standalone consumer fixture compiled and printed `consumer-ok`.
- Forbidden-symbol/JAR scans and `git diff --check` passed.
