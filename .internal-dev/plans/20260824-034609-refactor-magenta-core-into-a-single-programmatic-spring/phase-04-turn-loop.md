# Phase 04: Turn loop and interaction lifecycle

## Context
The migrated engine omitted current user prompt/memory insertion and had incomplete ask-user, queue, audit, metrics, and cancellation behavior.

## Goal
Deliver the complete transport-neutral sync/stream turn and pending-question lifecycle.

## In Scope
- Per-session serialization, interruption, stream cancellation, and close.
- User/assistant/tool/repair persistence and prompt assembly.
- Multi-question suspension/resumption, queueing, cancellation, restart recovery.
- Context length, audit, lifecycle, and metrics wiring.

## Out of Scope
- HTTP/SSE adapters, background queue workers, application orchestration.

## Implementation Steps
1. Repair prompt/memory and context usage.
2. Complete question state transitions and queued outcomes.
3. Add focused interaction/concurrency/lifecycle tests.

## Validation
Focused interaction tests and full Java 25 Maven verification.

## Exit Criteria
All retained facade capabilities are wired and race-safe without transport-specific code.
