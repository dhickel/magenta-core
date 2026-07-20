# changelog

## Date

2026-07-20

## Git Commit

01c4e347261b6d6d9e00709d5d9166ecf7d80f91

## Change Summary

Phase 09 schedule/reaction hardening: replaced raw polling and map-based reaction dispatch with revisioned, fenced schedule/reaction services including append-only audit, operation-key digest idempotency, claim-based poller fencing, causation loop/rate protection, timezone-aware DST/misfire semantics, side-effect-free simulation, and typed management APIs plus HTMX fragments.

## Files

- `src/main/resources/db/migrations/008-schedule-reaction-hardening.sql` — Migration: drops legacy agent_schedules/agent_event_reactions/schedule_firings; creates revisioned schedule_definitions, scheduler_claims, schedule_firings, schedule_firing_attempts, reaction_definitions, reaction_dispatches, reaction_dispatch_attempts
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/ScheduleService.java` — Rewritten: revisioned definitions, fenced claims, due-slot deduplication, operation-key idempotency, retry/recovery, pure simulation
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/EventReactionService.java` — Rewritten: revisioned definitions, causation chains, loop/rate/depth suppression, pure simulation
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/OrchestrationEventService.java` — Delegates reaction dispatch to EventReactionService
- `src/main/java/io/mindspice/magenta2/api/web/AgentOrchestrationController.java` — Typed CRUD + simulation + firing/dispatch audit endpoints
- `src/main/java/io/mindspice/magenta2/api/web/ScheduleReactionFragments.java` — New: reusable HTMX fragments (lists, editors, simulation previews, bounded audit)
- `src/main/java/io/mindspice/magenta2/core/persistence/SchemaMigrationRunner.java` — V8 foundation tables/columns/indexes + validation
- `src/main/java/io/mindspice/magenta2/core/config/MagentaRootConfiguration.java` — Added Clock bean
- `src/main/resources/schema.sql` — V8 tables appended
- Test files updated for new constructor signatures and semantics

## Behavioral Impact

- Schedule/reaction definitions are now revisioned: edits create new revisions; historical audit bound to original revision
- Firing identity is (schedule_id, revision, slot_instant, timezone) — prevents duplicate external work across pollers
- Reaction dispatch includes causation tracking (root_event, parent, depth, dedupe_key) with loop/rate/depth suppression
- Polling uses a claim+fence model suitable for single-process deployment topology
- Simulation endpoints are side-effect-free and visibly labelled non-executing
- APIs return typed ScheduleDefinition/ReactionDefinition instead of raw records; backward-compatible AgentSchedule/AgentEventReaction wrappers retained

## Specification Impact

- `specifications/services.md` — New schedule and reaction service entries needed
- `specifications/architecture.md` — Scheduler claim topology contract
- `specifications/schema.md` — V8 schema tables

## Risks

- Existing schedule/reaction data in agent_schedules/agent_event_reactions tables is dropped by migration 008; no automatic migration path for existing definitions
- Claim-based fencing assumes single-process deployment; multi-process topologies need coordination layer
- Polling deduplication depends on unique constraint; no distributed lock

## Follow-up Items

- Create schedule/reaction definition data migration path if pre-existing definitions exist in production
- Playwright validation for schedule/reaction CRUD, simulation, and fragment UI
- Post-phase validator review-and-repair gate (required before Phase 10)
