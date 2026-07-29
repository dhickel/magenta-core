# Phase 06 — Project Automation and Events

## Context

Migration 008 declares revision-qualified uniqueness while making `id` the primary key, so schedule/reaction updates cannot append revisions. Polling/dispatch do not select a sole current revision and delete mutates history. Repair the existing revisioned scheduler, event, and reaction engines; do not create project-local engines.

## Goal

repair revision heads and add project-scoped automation/events

## In Scope

**Write Targets**: src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/AgentSchedule.java, src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/AgentEventReaction.java, src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/OrchestrationEvent.java, src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/ScheduleService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/EventReactionService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/OrchestrationEventService.java, src/main/java/io/mindspice/magenta2/api/web/AgentOrchestrationController.java, src/main/java/io/mindspice/magenta2/api/web/ProjectAutomationController.java, src/main/java/io/mindspice/magenta2/api/web/ProjectAutomationFragments.java, src/main/resources/db/migrations/018-project-automation-events.sql, src/main/resources/schema.sql

Repair definition/head authority, typed immutable project attribution, CRON/ONE_TIME semantics, and bounded redacted project Automation fragments. Focused tests may be updated as needed. Phase 08 owns repository documentation updates.

## Out of Scope

Parallel project scheduler/dispatcher/event store, inferred executor replacement, synthetic one-time cron, exactly-once external effects, project workspace selection, raw event/template disclosure, and unrelated fragments, CSS/JS, repositories, runner code, or docs.

## Dependencies

phase-05-materialization-templates-files-outputs.md

## Constraints

- Revisions append; one head pointer selects the sole current revision. Historical definitions/audit are immutable.
- Project id is typed immutable server-derived attribution; agent id is explicit executor. Create, enable, fire, recovery, and dispatch require capability, active project, and current eligible member.
- Reactions match persisted trusted `OrchestrationEvent.projectId`, never payload JSON, route text, source names, or membership inference.
- CRON and ONE_TIME are exclusive. ONE_TIME uses a durable normalized instant/slot, rejects past requests, and cannot repeat after a terminal slot.
- Simulation is side-effect-free. Standard controls use HTMX; no new JavaScript transport is allowed.

## Required Guides

- `AGENTS.md`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/AGENTS.md`
- `src/main/java/io/mindspice/magenta2/api/web/AGENTS.md`
- `.internal-dev/specifications/architecture.md`
- `.internal-dev/specifications/service-graph.md`
- `.internal-dev/specifications/services.md`
- `.internal-dev/specifications/api.md`
- `.internal-dev/specifications/web.md`
- `.internal-dev/specifications/simplypages.md`
- `.internal-dev/knowledge/schedule-reaction-idempotency.md`
- `.internal-dev/knowledge/security-access-control-domain-validation.md`
- Relevant SimplyPages docs and matching demos before `ProjectAutomationFragments`.

## Technical Guidance

Before migration 018, prove 014–017 are contiguous/applied and the runner discovers through 018 with supported fresh/warm/checksum/partial-state behavior. If an unlisted target is required, stop for ledger correction; do not bypass migration ownership. Prove the Phase 01 caller-binding/gate, project archive/member guard, and assignment source/origin seams before enabling project automation.

Insert revision, expected-head CAS, and idempotency reservation in one transaction. A failed CAS conflicts. Definition deletes append tombstones. Historical recovery uses recorded revision, project, digest, and slot snapshot, never a later head. Persist reservation before lifecycle enqueue and verify owner/fence in every guarded mutation; stale owners affect zero rows. `ENQUEUED` means lifecycle acceptance, not completion.

Use the head join before eligibility filters:

```sql
from schedule_definition_heads h
join schedule_definitions d
  on d.id = h.schedule_id and d.revision = h.current_revision
```

Construct assignment requests from server-owned project/executor/revision/source/origin/key/digest facts. Templates/input/payload cannot override project, executor, workspace, context binding, or lifecycle identity. Claims/fences/owners, raw templates/payloads/errors, paths, checkpoints, and secrets never enter DTOs. Simulation takes immutable input and explicit clock with no mutation-capable collaborator.

## Implementation Steps

All A–F subphases are contiguous work for the one phase implementer. Complete all A–F before the single phase-level Validation and Exit Criteria; none is an independent validation gate.

### A. Rebuild immutable revision/head persistence

1. Inspect migration 008, runner behavior, affected FKs, Phase 01–05 migrations, and data. Reject malformed revision history rather than silently selecting it.
2. In 018, rebuild schedule/reaction definitions to composite `(id, revision)` identity and rebuild firing/dispatch/attempt storage as required to retain exact revision FKs. Copy valid ids, timestamps, and JSON snapshots unchanged; do not manufacture operations.
3. Add one head table per family, backfilled to the valid highest revision including tombstones. Add indexed immutable `project_id`, mode/slot/audit/linkage fields, and database checks for mode/state domains.
4. Make `schema.sql` the same final state and remove superseded fresh shapes. Prove keys, FKs, indexes, checks, and fresh/warm metadata equivalence.

### B. Repair writers, polling, and modes

1. Make domain records explicit for project, revision/head, mode-specific facts, and safe display facts; no ambiguous nullable combinations.
2. Create revision 1/head; edit/toggle/delete append next revision/tombstone with expected revision/digest and atomic head CAS. Never whole-row update history.
3. Normalize IANA zones. CRON validates six-field dialect/DST policy. ONE_TIME persists normalized instant and durable slot, has no cron, rejects past fire, and is excluded after terminal completion.
4. Recover using recorded snapshots. Disable/delete, archive, membership/capability loss, stale claim, and lifecycle conflict append typed outcomes rather than duplicate enqueue.

### C. Bind trusted events and reaction causation

1. Route project intents through Phase 01 sealed project scope/access service. Derive project/caller facts server-side; validate active project/current selected member before parsing templates or submitting work.
2. Extend events with typed immutable project attribution only trusted internal publishers can set. Generic publication cannot impersonate a project by JSON.
3. Dispatch only current reaction heads whose persisted project equals event project; filter only after that match. Preserve root/parent/depth/correlation/dedupe/snapshots before enqueue and append accurate duplicate/loop/depth/rate/enqueue-failure outcomes and attempts.

### D. Expose bounded HTMX project automation

1. Add thin `ProjectAutomationController`; structurally resolve project scope for list/detail/simulate/create/edit/enable/delete/fire/audit and re-resolve canonical rows before actions.
2. Retain agent routes only where agent scope proves ownership. Clamp audits and map errors without raw records.
3. Render stateless redacted `ProjectAutomationFragments` in independent schedule, reaction, simulation, and audit hosts. Replace only owning hosts; OOB remains in automation; render empty/disabled/unavailable/archive/stale/conflict states.
4. Use HTMX for controls and label simulation **Non-Executing**. Do not add raw markup construction, broad swaps, JavaScript transport, or a scheduling loop.

### E. Prove authority, migration, and concurrency

1. Add fixtures for supported pre-008/applied-008 history and 014–018 warm chain. Prove fresh/warm equivalence and fail closed on repeat, checksum, missing resource, partial rebuild, failed copy, FK/index drift, and invalid mode/revision state.
2. Test append/head selection, tombstones, historical lookup, expected-version conflict, head race, CRON/DST, ONE_TIME past/restart/non-repeat, duplicate pollers, stale fence, and recovery/idempotency.
3. Test gate/caller absence, archive/member loss, executor mismatch, template/payload spoofing, cross-project reactions, historical membership, and no fallback; every ineligible path audits without assignment creation.
4. Test exact event/revision identity, current-head-only reactions, suppression, attempts, bounded/redacted DTOs, and simulation purity; assert no sensitive content, paths, owners, fences, or checkpoints.

### F. Complete integrated proof

1. Add controller/fragment tests for scope, cross-scope ids, malformed ids, disabled/archive/member-loss state, conflicts, bounded audit, host isolation, and no active disabled controls.
2. Inspect final diff for duplicate authority, unscoped queries, payload-derived identity, sensitive DTOs, unguarded capability, unjustified JavaScript, placeholders, and out-of-target work.
3. Complete ordinary closeout only if the internal-development store is ready; never initialize it implicitly.

## Validation

After A–F complete:

- Run migration discovery/order, fresh-install, supported 014–018 warm upgrade, repeat, checksum, partial-state, failed-migration, FK/index, and metadata-equivalence tests.
- Run focused schedule/reaction/event/project-access/assignment-origin/service/controller/fragment/idempotency/concurrency/restart/fence/DST/ONE_TIME/simulation/redaction/archive/member/spoofing tests plus relevant Maven/static checks.
- Run bounded Spring Boot startup with capability enabled and disabled; report a blocking dependency rather than substituting sign-off.
- Delegate focused live-app Playwright to a subagent using the required model. Validate CRON/ONE_TIME, non-executing simulation, audit, disabled/ineligible/archive states, scoped deep links, host isolation, desktop/mobile screenshots, keyboard/focus/ARIA, and visual quality. Confirm HTMX and no new JavaScript transport.
- Obtain orchestration-required post-phase validator review-and-repair PASS.

## Exit Criteria

- Migration 018/fresh schema provide composite immutable revisions and exactly one current head, preserving valid historical reachability with proven fresh/warm integrity.
- Only current enabled non-tombstoned heads execute; historical snapshots remain exact and immutable.
- CRON/ONE_TIME are exclusive; past one-time requests fail and one-time schedules never repeat across restart.
- Project attribution is server-derived/immutable; project/member/archive/capability failure fails closed, audits, and never chooses fallback executor.
- Reservation, fences, idempotency, recovery, causation, suppression, lifecycle handoff, and append-only audit preserve at-least-once idempotent effects without exactly-once claims.
- Automation is scoped, bounded, redacted, HTMX-first, host-isolated, and disabled safely; agent routes cannot leak or act outside proven scope.
- No parallel engine, sensitive DTO, unscoped audit, historical revision mutation, payload-derived authority, placeholder, or out-of-target workaround remains.
- All validation, enabled/disabled startup, Playwright evidence, and validator PASS succeed. Documentation integration remains Phase 08 work.
