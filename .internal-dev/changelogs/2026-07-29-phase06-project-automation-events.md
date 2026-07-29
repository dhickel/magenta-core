# Changelog: Phase 06 - Project Automation and Events

## Date
2026-07-29

## Change Summary
Implemented project-scoped automation with typed immutable project attribution, revision-head authority repair for schedules and reactions, exclusive CRON/ONE_TIME schedule modes, project-trust event matching, side-effect-free simulation, and HTMX-only project console Schedules and Events tabs.

## Files

### New Files
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/ScheduleMode.java` - CRON/ONE_TIME exclusive enum
- `src/main/java/io/mindspice/magenta2/api/web/ProjectAutomationController.java` - Project-scoped schedule/reaction CRUD, simulation, audit
- `src/main/java/io/mindspice/magenta2/api/web/ProjectAutomationFragments.java` - HTMX fragments for Schedules/Events tabs
- `src/main/resources/db/migrations/019-project-automation-events.sql` - Revision-head repair, project attribution, mode enum, FK/indexes
- `src/test/java/io/mindspice/magenta2/ai/orchestration/runtime/Phase06AutomationTest.java` - Focused tests (21 tests)

### Modified Files
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/AgentSchedule.java` - Added mode, fireAt, projectId; backward-compat constructor
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/AgentEventReaction.java` - Added projectId; backward-compat constructor
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/OrchestrationEvent.java` - Added projectId; backward-compat constructor
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/ScheduleService.java` - Revision heads, CRON/ONE_TIME modes, project-scoped methods, new ProjectService dependency
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/EventReactionService.java` - Revision heads, project trust matching, project-scoped methods, new ProjectService dependency
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/OrchestrationEventService.java` - publishProject() method, project_id column mapping
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/EventType.java` - Added SCHEDULE_CREATED
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/OrchestrationRuntimeRepository.java` - Updated toEvent row mapper
- `src/main/java/io/mindspice/magenta2/api/web/AgentOrchestrationController.java` - Updated ScheduleDefinition/ReactionDefinition constructors for new fields
- `src/main/java/io/mindspice/magenta2/api/web/ProjectConsoleFragments.java` - Enabled Schedules/Events tabs (available=true)
- `src/main/java/io/mindspice/magenta2/api/web/ProjectConsoleController.java` - Removed unavailable stubs for schedules/events (routes moved to ProjectAutomationController)
- `src/main/java/io/mindspice/magenta2/api/web/ScheduleReactionFragments.java` - Updated constructors for new ScheduleSimRequest/ReactionSimRequest fields
- `src/main/resources/schema.sql` - Declares the same v19 schedule/reaction/event end state used by fresh databases
- `src/main/java/io/mindspice/magenta2/core/persistence/SchemaMigrationRunner.java` - V19 foundation validation plus idempotent event-column installation before migration execution
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/AssignmentTemplateParser.java` - Rejects automation-template attempts to override server-owned attribution
- `src/test/java/io/mindspice/magenta2/ai/orchestration/runtime/ScheduleReactionFeatureParitySpringTest.java` - Covers appended revisions and immutable tombstones
- `src/test/java/io/mindspice/magenta2/api/web/AgentOrchestrationControllerTest.java` - Updated stub constructors for new service dependencies
- `src/test/java/io/mindspice/magenta2/api/web/ProjectConsoleControllerTest.java` - Updated to reflect routes moved to ProjectAutomationController

## Behavioral Impact
- Schedule definitions now have explicit `current_revision` flag and composite `(id, revision)` primary key
- Schedules support exclusive CRON (recurring) and ONE_TIME (single fire) modes
- ONE_TIME schedules auto-disable after firing; past fireAt is rejected at create time
- Optional `project_id` on schedules, reactions, and events enables project-scoped automation
- Reactions match events by trusted project_id: null→null or identical non-null values; mismatches are silently skipped
- Project-scoped schedule/reaction CRUD validates active project + member executor at create/enable/fire/dispatch time; automation templates cannot override server-owned executor/project/workspace/lifecycle facts
- Schedules and Events tabs are now available in the project console UI (HTMX-only)
- Simulation endpoints are side-effect-free (no persistence query/write, claim, assignment, event, or external action)
- Polling/dispatch selects definitions via `current_revision = 1` instead of `max(revision)`

## Specification Impact
Specification Impact: none. The active architecture and service specifications already define revisioned schedule/reaction state, trusted project metadata, and pure simulation; Phase 08 owns broader documentation integration.

## Risks
- Migration 019 is destructive for schedule/reaction tables (acceptable in current development phase)
- Migration 019 must remain immutable once applied; its event-column repair is runner-owned so fresh schema adoption does not duplicate the column
- No data migration path from v8→v19 for existing schedule/reaction records (tables are dropped/recreated)

## Follow-up Items
- Multi-process scheduler deployment topology (current: single-process with owner+fence claims)
- Chat tab enablement (currently unavailable placeholder)
- End-to-end Playwright validation for project automation UI tabs (not available through this validator harness)
