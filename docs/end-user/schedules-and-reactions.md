# Schedules And Reactions

Schedules and event reactions automate assignment enqueueing for an agent. They are available from the selected agent's **Schedules** and **Reactions** tabs when their feature flags are enabled.

## Schedules

A schedule contains a Spring six-field cron expression, mandatory IANA timezone, enabled state, optional job, and assignment template.

1. Open `/agents/{agentId}` and select **Schedules**.
2. Enter the cron expression and timezone.
3. Choose the target/template fields shown by the form.
4. Use **Simulate** to inspect upcoming slots.
5. Save, then enable the schedule.
6. Open **Firings** to inspect enqueue attempts and linked assignment/run IDs.

Simulation is explicitly **Non-Executing**. It creates no firing, assignment, run, tool call, artifact, or external mutation. `ENQUEUED` means work was accepted into the runtime; it does not mean that work completed successfully.

Create/edit validation keeps entered cron, timezone, target, status, and template values in place. Successful HTMX mutations refresh the schedule list without replacing unrelated audit panels. Definition edits create a new revision. Historical firings remain attached to the revision that produced them. Disable/delete stops future unclaimed firing; it does not silently cancel work already enqueued. Use the linked-work cancellation action when available and verify the recorded result.

## Event Reactions

A reaction matches an orchestration event type and optional filter, then enqueues an assignment using its saved template.

1. Select **Reactions** on the agent page.
2. Choose the event type and enter filter/input JSON when needed.
3. Use **Simulate** with a sample event payload.
4. Save and enable the reaction.
5. Open **Dispatches** to inspect matched, enqueued, suppressed, or conflicting outcomes.

Reaction create/edit validation likewise preserves filter and assignment-template JSON, and successful HTMX mutations refresh the reaction list in place. Reaction simulation also has no side effects. Runtime dispatches preserve root event, parent causation, depth, dedupe, and revision facts. Loop, depth, duplicate, and rate protections can suppress a dispatch before work is enqueued; suppression is an auditable outcome, not a silent drop.

## Time And Recovery Basics

- Cron uses the documented six-field Spring dialect.
- Timezone must be a valid IANA zone such as `UTC` or `America/New_York`.
- DST gaps are skipped; overlaps fire once at the earlier offset.
- Restart misfires coalesce rather than creating one synthetic firing per missed slot.
- Retry/recovery attempts remain in append-only audit.
- Feature-disabled schedule/reaction APIs return `404`.

## Common Problems

- **Schedules/Reactions unavailable**: enable `magenta.features.schedules-enabled` or `magenta.features.reactions-enabled` and restart.
- **Invalid cron/timezone**: correct the six-field cron or IANA zone.
- **Suppressed dispatch**: inspect loop, depth, rate, and dedupe facts.
- **Enqueued but not complete**: follow the linked assignment/task run; enqueue state is not run outcome.
