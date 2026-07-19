# Data Model

Primary Magenta SQLite schema is initialized from [`schema.sql`](../../src/main/resources/schema.sql). Avatar user-centric data is initialized separately from [`avatar-schema.sql`](../../src/main/resources/avatar-schema.sql) into `avatar.sqlite`. Repositories also create tables defensively and add compatibility columns for warm local databases, so code inspection should include the relevant schema file and owning repository before changing schema assumptions.

## Avatar Personal Data

Source package: [`avatar`](../../src/main/java/io/mindspice/magenta2/avatar).

Avatar personal dashboard and organizer state is stored in a separate `<magenta.root.path>/avatar.sqlite` database through the `avatarDataSource` and `avatarJdbcTemplate` beans. This keeps user-centric Avatar data out of the primary `magenta.sqlite` runtime database and avoids cross-database foreign keys.

Tables in `avatar.sqlite`:

- `avatar_profile`: singleton Avatar personal profile row.
- `avatar_preferences`: key/value preference records with JSON values.
- `avatar_dashboard_layout`: singleton dashboard widget layout JSON.
- `avatar_todos`: personal todo records with status, priority, optional due time, and optional project/task/output references.
- `avatar_daily_tasks`: date-scoped daily task rows.
- `avatar_calendar_items`: local calendar entries.
- `avatar_notes`: notes with tag JSON and optional source references.
- `avatar_facts`: durable facts keyed by namespace and key.
- `avatar_events`: append-only Avatar event records.

The backing `agent_profiles` row for `Avatar` remains in the primary Magenta database because agent profiles are part of the existing orchestration/runtime surface. Phase 01 reserves that profile as disabled and direct-line-off without changing runtime defaults.

## Chat, Sessions, and Audit

Source packages: [`ai/chat/repository`](../../src/main/java/io/mindspice/magenta2/ai/chat/repository), [`ai/agent/job`](../../src/main/java/io/mindspice/magenta2/ai/agent/job).

- `ai_chat_memory`: ordered chat messages by `conversation_id` and `message_order`, including message type, text, and metadata JSON.
- `ai_chat_pending_messages`: browser `/chat` mid-turn message queue rows with deterministic `message_order`, message/model/planning model/surface fields, `PENDING` or `CLAIMED` status, claim token/timestamp, and created/updated timestamps. Rows are not chat memory until the browser claims and sends them through the normal stream route.
- `ai_chat_session_metadata`: per-conversation model, title, active task run, planning model, favorite/archive flags, origin, agent id, and updated timestamp.
- `plan_chat_messages`: saved `/plans` planning chat messages by `plan_id`, separate from `/api/chat` session memory.
- `audit_event`: append-only conversation event log for user/assistant messages, tool execution, compaction/context snapshots, errors, token usage, and result previews.
- `agent_jobs`: background chat jobs such as conversation title generation, with selected model, input/result JSON, status, and timestamps.

Compatibility notes:

- `ChatMemoryRepository` can add `message_metadata_json`.
- `ChatPendingMessageRepository` creates the pending-message table and indexes defensively and recovers stale claimed rows before list/claim operations.
- `ChatSessionMetadataRepository` can add title, favorite, archived, updated, planning model, active task run, origin, and agent columns.
- `AuditRepository` can add audit columns as the audit event shape evolves.

## Plans, Tasks, and Runs

Source package: [`ai/chat/plan`](../../src/main/java/io/mindspice/magenta2/ai/chat/plan). Task-facing records live in [`ai/chat/task`](../../src/main/java/io/mindspice/magenta2/ai/chat/task).

- `plan_definitions`: unified saved plan/task definitions. It stores kind, status, title, summary, goal, notes, deliverables, inputs, outputs, assumptions, steps, validation criteria, execution evidence, validation feedback, prompt/work profile, planning/execution model, settings overrides, planning task text, pending questions/index, plan start order, final message, conversation id, and timestamps.
- `plan_runs`: execution history for saved definitions. It snapshots the full definition, input/output values, workspace type/key/identity, evidence, validation feedback, deliverable evidence, final/error messages, status, and timestamps.

Important invariants:

- `SESSION_PLAN` uses `id = conversation_id`.
- Task templates use UUID ids and may carry conversation id for draft tracking.
- Saved plan chats are keyed by saved `plan_id`; anonymous `/chat` planning remains keyed by conversation id and does not use `plan_chat_messages`.
- Runs snapshot definitions at start time so later edits do not change historical run meaning.
- Execution selection is persisted as `workspace_type`, `workspace_key`, and internal `workspace_identity`; project ids remain attribution metadata.
- Declared outputs publish directly beneath the selected workspace's lazy `outputs/` subtree.

## Workflows

Source package: [`ai/orchestration/workflow`](../../src/main/java/io/mindspice/magenta2/ai/orchestration/workflow).

- `workflow_definitions`: v2 workflow definitions with schema version, title, summary, max concurrency, nodes JSON, routes JSON, UI layout JSON, and timestamps.
- `workflow_runs`: workflow execution records with current node index, denormalized node runs JSON, nullable agent/job/job-assignment/job-run/project/run-type attribution, workspace type/key/identity, workflow snapshot JSON, final outputs, artifact ids, final/error messages, status, and timestamps.
- `workflow_node_runs`: queryable per-node run rows denormalized from `workflow_runs.node_runs_json`.
- `inbox_messages`: workflow-owned inbox messages for user approvals, workflow agent approvals, notifications, and run-output delivery.

Compatibility notes:

- `WorkflowRepository` adds typed workspace columns to warm databases without recreating obsolete path/id columns.
- Workflow inbox messages are intentionally separate from runtime direct-line agent inbox messages.
- `WAITING` runs retain the exact typed selection and re-resolve it for the resumed attempt.

## Agents, Assignments, Runtime Jobs, Inbox, Schedules, Reactions

Source packages: [`ai/orchestration/agents`](../../src/main/java/io/mindspice/magenta2/ai/orchestration/agents), [`ai/orchestration/runtime`](../../src/main/java/io/mindspice/magenta2/ai/orchestration/runtime).

- `agent_profiles`: durable agent profiles with name, status, default model, system prompt, approved tools JSON, shell allowlist JSON, direct-line flag, and timestamps.
- `orchestration_jobs` and `orchestration_job_items`: runtime orchestration job records with typed workspace selection.
- `work_assignments`: durable assignment queue. Fields include agent id, job/job item ids, assignment type, priority, status, model override, project attribution, workspace type/key/identity, current item index, checkpoint/input/output/evidence JSON, error, queue lease owner/expires, progress/heartbeat timestamps, and lifecycle timestamps.
- `assignment_conversation_links`: durable mapping from assignment ids to chat conversation ids so transcripts remain visible even if checkpoint output is incomplete.
- `agent_inbox_messages`: runtime direct-line agent/operator inbox messages with read/handled flags.
- `agent_schedules` and `schedule_firings`: cron-like assignment scheduling and de-duplication of due firings.
- `agent_event_reactions`: event-to-assignment reaction rules.
- `orchestration_events`: append-only runtime event records with handled timestamp.

Compatibility notes:

- `OrchestrationRuntimeRepository` creates runtime tables and can add retry/continue fields to job items plus project/effective-workspace/progress/heartbeat fields to work assignments.
- New assignments keep `input_json.projectId` for compatibility, but first-class `project_id` is the assignment context source for new UI/API reads.
- Runtime direct-line inbox and workflow approval inbox use different tables and lifecycle models.

## User-Facing Jobs

Source package: [`ai/orchestration/runtime`](../../src/main/java/io/mindspice/magenta2/ai/orchestration/runtime).

- `job_definitions`: user-facing jobs with owner agent, project attribution, workspace type/key/identity, status, title, summary, ordered `items_json`, prompt profile, model, settings override JSON, and timestamps.
- `job_runs`: execution records with job assignment id, workspace type/key/identity, work item run JSON, final/error messages, status, and timestamps.
- `job_recurrences`: one recurrence per job, with cron expression, timezone, next fire time, enabled flag, and timestamps.

Compatibility notes:

- `JobRepository` adds only `workspace_type`, `workspace_key`, `workspace_identity`, assignment id, and other active columns to older job tables; it does not recreate obsolete routing/path columns.
- Public job definitions can be saved as empty `DRAFT` rows before items are added.
- Job execution creates no job-owned, run-staging, or eager output directory.
- Job execution summaries are service read models, not separate tables. They combine definition, assignment, run, project/agent attribution, typed workspace identity, child run ids, and output counts.

## Projects

Source package: [`ai/orchestration/runtime`](../../src/main/java/io/mindspice/magenta2/ai/orchestration/runtime).

- `projects`: project metadata, nullable legacy owner agent id, optional git repo URL, prompt profile, model, settings override JSON, and timestamps.
- `project_agent_memberships`: unique project/agent membership rows with roles.
- `project_events`: append-only project event log.

Projects are membership, tracking, and visibility records, not executable work units or filesystem selectors.

## Typed Workspaces and Outputs

Source package: [`ai/orchestration/workspaces`](../../src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces).

Filesystem workspace identity is derived from type and key and therefore has no active registry table. Execution tables persist `workspace_type`, `workspace_key`, and `workspace_identity`.

- `run_output_artifacts`: output artifact metadata including run/plan and optional agent/job/job-assignment/job-run/project attribution, workspace type/key/identity, run type, output name, artifact type, portable data-root-relative file path, content JSON, and timestamp.
- Artifact queries are indexed by run and major attribution fields.
- `OutputArtifactRepository` exclusively owns artifact schema compatibility, persistence, mapping, queries, and deletion.
- Obsolete `workspaces`, links, leases, Work Areas, and file metadata structures may remain in existing databases but are inert pending deletion; active startup does not migrate or recreate them.

## Runtime Settings

Source package: [`ai/orchestration/settings`](../../src/main/java/io/mindspice/magenta2/ai/orchestration/settings).

- `runtime_settings`: singleton-style persisted defaults for default agent/model, planning/summary/compaction models, context buffer, system chat model/prompt/tools/context limit/enabled flag, assignment history auto-purge days, and temp work retention.

`RuntimeSettingsRepository` adds settings columns as needed for warm databases. `RuntimeSettingsService` falls back to legacy file config when persisted values are absent.
