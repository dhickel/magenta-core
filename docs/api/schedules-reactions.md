# Schedule And Reaction API

Source: [`AgentOrchestrationController`](../../src/main/java/io/mindspice/magenta2/api/web/AgentOrchestrationController.java).

All routes are agent-scoped under `/api/agents/{agentId}`. Schedule routes return `404` when `magenta.features.schedules-enabled=false`; reaction routes do the same when `magenta.features.reactions-enabled=false`.

## Schedules

- `GET|POST /schedules`
- `GET|PUT|DELETE /schedules/{scheduleId}`
- `POST /schedules/{scheduleId}/enable` with `{ "enabled": true|false }`
- `GET /schedules/{scheduleId}/firings?revision=1&offset=0&limit=25`
- `GET /schedules/{scheduleId}/firings/{firingId}/attempts`
- `POST /schedules/simulate`
- `POST /schedules/{scheduleId}/cancel-linked?revision=1`

Create/update uses `ScheduleCreateRequest(cronExpression, timezone, enabled, jobId, assignmentTemplate)`. Cron is the Spring six-field dialect and timezone is a valid IANA identifier. Definitions are revisioned and soft-deleted; historical firing audit stays attached to its revision.

Simulation returns typed next-slot, DST, and misfire facts and performs no persistence or external action. A firing state of `ENQUEUED` proves assignment enqueue, not successful task completion.

**Security drift:** the firing-attempt JSON endpoint currently returns the persistence record, including `claimOwner` and `claimFence`, and does not verify that the firing belongs to the path schedule/agent. Treat it as blocked for external consumption until a secret-free, scope-checked response removes those fields. The HTMX attempt table does not render them.

## Reactions

- `GET|POST /event-reactions`
- `GET|PUT|DELETE /event-reactions/{reactionId}`
- `POST /event-reactions/{reactionId}/enable`
- `GET /event-reactions/{reactionId}/dispatches?revision=1&offset=0&limit=25`
- `GET /event-reactions/dispatches/by-root-event/{rootEventId}`
- `POST /event-reactions/simulate`
- `GET /orchestration-events?sourceType=&sourceId=&offset=0&limit=25`

Create/update uses `ReactionCreateRequest(eventType, filter, actionType, enabled, assignmentTemplate)`. The active action type is assignment enqueue. Dispatch audit preserves source/root/parent causation, depth, dedupe, revision, assignment/run links, and suppression outcome. Loop, depth, rate, and duplicate checks happen before enqueue.

Simulation evaluates matching and suppression without creating a dispatch, assignment, run, event, tool call, artifact, or external mutation.

`GET /event-reactions/dispatches/by-root-event/{rootEventId}` currently queries by root event without filtering the returned rows to the path agent. Treat it as blocked for external consumption until agent ownership is enforced before rows are returned.

## HTMX Fragments

- `/fragments/agents/{agentId}/schedules/list`
- `/fragments/agents/{agentId}/schedules/simulate-preview`
- `/fragments/agents/{agentId}/schedules/{scheduleId}/firings`
- `/fragments/agents/{agentId}/schedules/{scheduleId}/firings/{firingId}/attempts`
- `/fragments/agents/{agentId}/reactions/list`
- `/fragments/agents/{agentId}/reactions/simulate-preview`
- `/fragments/agents/{agentId}/reactions/{reactionId}/dispatches`

Simulation fragments visibly label results **Non-Executing**. Pagination inputs are bounded by the owning services/controllers.
