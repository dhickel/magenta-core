# Phase 04 Project Jobs and Reassignment Review

## Scope
Post-phase validation and repair of Job authority, CAS, one-JobRun linkage, atomic queued reassignment, migration 017, and the Project Jobs HTMX seam.

## Findings
- Repaired a critical atomicity defect: successor creation failure had been caught after source revocation, allowing a cancelled source with no successor to commit. It now escapes the transaction and rolls back all reassignment writes.
- Repaired successor workspace/context selection. A reassignment no longer reuses the source agent workspace; the successor captures the destination agent default workspace and immutable binding.
- Repaired missing successor origin and operation lineage facts. Successors record `REASSIGNMENT/<operation>` and operations persist successor task-run and predecessor assignment IDs.
- Repaired warm migration 017: an unconditional SQLite `ADD COLUMN` failed when legacy repository bootstrap had already created `definition_version`. The migration runner now conditionally installs/backfills the column before the resource executes.
- Enforced one-JobRun-per-assignment in repository bootstrap and converges duplicate allocation to the existing same-job run.
- Removed public job submission fallback to an arbitrary active agent; an absent preferred executor now requires an explicit agent.
- Added focused integration and repository coverage for rollback injection, replay, digest conflict, no second successor, source fencing before JobRun allocation, CAS, and unique JobRun linkage.

## Risk Assessment
- Work Task provenance remains intentionally unavailable: `WorkTaskService` has no transactional append-attempt participant. No Task state is queried or changed by reassignment.
- Jobs-tab Playwright validation remains blocked because this session has no permitted Playwright subagent workflow. Server-rendered UI remains HTMX-only by inspection and controller tests pass.

## Recommendations
- Add the atomic Work Task append-attempt participant before enabling reassignment provenance linking.
- Run the focused Jobs-tab Playwright desktop/mobile pass when the required subagent workflow is available.

## Follow-ups
- Design and authorize a reassignment browser control before exposing the service through a Project Console mutation route.
