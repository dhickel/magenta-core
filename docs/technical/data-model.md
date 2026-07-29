# Data Model

Primary Magenta SQLite schema is initialized from [`schema.sql`](../../src/main/resources/schema.sql) and evolved by checksum-protected ordered resources 001-018 in [`db/migrations`](../../src/main/resources/db/migrations). Migration-owned structures use `SchemaMigrationRunner`; older unrelated repositories may still add compatibility columns for warm databases. The [001–009 guide](migrations-001-009.md) covers the original immutable foundation; migrations 010-018 are documented in their domain sections below.

## Avatar Personal Data (Removed)

The Avatar package, `avatar.sqlite`, `avatar-schema.sql`, and all Avatar tables (`avatar_profile`, `avatar_preferences`, `avatar_dashboard_layout`, `avatar_todos`, `avatar_daily_tasks`, `avatar_calendar_items`, `avatar_notes`, `avatar_facts`, `avatar_events`) are removed. The `avatarDataSource` and `avatarJdbcTemplate` beans are deleted. Legacy `avatar.sqlite` files remain untouched on disk but are inactive.

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
- `work_task_definitions`, `work_task_attempts`, and `work_task_operations` (migrations 015-016): lightweight project task definitions, append-only assignment attempts, designated-attempt state, promotion links, and operation-keyed outcomes. The promoted-Plan delete guard is installed by runner reconciliation because its SQLite trigger body cannot be processed safely by the generic SQL script splitter.

Important invariants:

- `SESSION_PLAN` uses `id = conversation_id`.
- Task templates use UUID ids and may carry conversation id for draft tracking.
- Saved plan chats are keyed by saved `plan_id`; anonymous `/chat` planning remains keyed by conversation id and does not use `plan_chat_messages`.
- Runs snapshot definitions at start time so later edits do not change historical run meaning.
- Execution selection is persisted as `workspace_type`, `workspace_key`, and internal `workspace_identity`; project ids remain attribution metadata.
- New task-run outputs publish beneath `tasks/<taskPathKey>/<taskRunId>/outputs/`. Workspace-root `outputs/` is legacy/non-task only.

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
- `work_assignments`: durable assignment queue. Fields include agent id, job/job item ids, assignment type, priority, status, model override, project attribution, immutable source/origin definition ids, context binding key, row version, workspace type/key/identity, current item index, checkpoint/input/output/evidence JSON, error, queue lease owner/expires, progress/heartbeat timestamps, and lifecycle timestamps. Migration 014 owns assignment authority, archive state, scoped query snapshots, and control-operation persistence.
- `assignment_conversation_links`: durable mapping from assignment ids to chat conversation ids so transcripts remain visible even if checkpoint output is incomplete.
- `agent_inbox_messages`: runtime direct-line agent/operator inbox messages with read/handled flags.
- `schedule_definitions`, `scheduler_claims`, `schedule_firings`, and `schedule_firing_attempts`: revisioned schedule state, fenced poller ownership, deterministic due-slot identity, and append-only enqueue/recovery audit.
- `reaction_definitions`, `reaction_dispatches`, and `reaction_dispatch_attempts`: revisioned event rules, exact causation/dedupe identity, suppression outcomes, and append-only dispatch audit.
- `orchestration_events`: append-only runtime event records and causation source facts.
- `run_summaries`: rebuildable normalized runtime cache with attempt/model/tool counts, wall/active elapsed, completeness, workspace scope, and retry/replay lineage. Native family tables remain status truth.
- `run_measurements`: one immutable invocation row per model/tool call with attempt, correlation, outcome, elapsed, token/process facts, and completeness.

Active/unavailable runs are excluded from terminal health denominators. Null usage, elapsed, timeout, exit, or completion facts remain unavailable rather than becoming zero.

Compatibility notes:

- `OrchestrationRuntimeRepository` creates runtime tables and can add retry/continue fields to job items plus project/effective-workspace/progress/heartbeat fields to work assignments.
- New assignments keep `input_json.projectId` for compatibility, but first-class `project_id` is the assignment context source for new UI/API reads.
- Runtime direct-line inbox and workflow approval inbox use different tables and lifecycle models.

## User-Facing Jobs

Source package: [`ai/orchestration/runtime`](../../src/main/java/io/mindspice/magenta2/ai/orchestration/runtime).

- `job_definitions`: user-facing jobs with owner agent, project attribution, workspace type/key/identity, status, title, summary, ordered `items_json`, prompt profile, model, settings override JSON, and timestamps.
- `job_runs`: execution records with job assignment id, workspace type/key/identity, work item run JSON, final/error messages, status, and timestamps.
- `job_recurrences`: one recurrence per job, with cron expression, timezone, next fire time, enabled flag, and timestamps.
- `job_reassignment_operations` (migration 017): operation-keyed atomic queued-reassignment state, source/destination identity, expected version, successor and lineage links, terminal outcome, and bounded failure class.
- `project_materialization_templates` (migration 018): append-only project-owned revisions with a canonical JSON directory snapshot. The unique `(project_id, revision)` sequence preserves historical layouts.
- `agent_materialization_generations` (migration 018): immutable `(agent_id, project_key, generation)` V2 generation snapshots, their template revision/snapshot, generation root assertion, and lifecycle CAS facts. `GENERATION_V2` roots are under the agent workspace's `materializations/v2/` tree, not a project workspace or the legacy `projects/` subtree.

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

## Agent Materialization, Context, And Bindings

Source package: [`ai/orchestration/context`](../../src/main/java/io/mindspice/magenta2/ai/orchestration/context). Migrations 010-012.

- `agent_materializations`: generation-safe project materializations per agent. Fields: `agent_id`, `materialization_key` (`v1-PROJECT-<sha256>`), `materialization_kind` (`PROJECT`), `source_kind` (`project`), `source_id` (project id), `generation` (monotonic >= 1), `state` (`PROVISIONING`, `READY`, `FAILED`, `REVOKED`), `root_relative_path` (data-root-relative, verified but not authorizing), `failure_detail`, timestamps. Unique per `(agent_id, materialization_key)`.
- `context_defaults`: versioned context defaults per agent. Fields: `agent_id` (PK), `version` (monotonic >= 1), `selection_kind` (`MATERIALIZATION` or `PERSONAL`), `materialization_key`/`materialization_generation` (when MATERIALIZATION), `personal_selection` (`HOME`, `SCRIPTS`, `APPS` when PERSONAL), `reason`, timestamps. Updated with expected-version CAS.
- `context_bindings`: insert-only immutable binding snapshots. Fields: `id` (autoincrement PK), `agent_id`, globally unique `binding_key`, `selection_kind`, `materialization_key`/`materialization_generation` or `personal_selection`, and `captured_at`. Bindings are never retargeted.
- `binding_owner_links`: typed owner-to-binding links. Fields: `owner_type`, `owner_id`, `binding_key`, and `created_at`; an owner pair is unique and cannot be retargeted.
- `operation_idempotency`: durable binding-capture and owner-link operation results keyed by operation type/key with a request digest.
- `assignment_binding_operations`: one durable, race-safe submission decision per `operation_key` (PK). Fields: `request_digest` (64-char hex), `operation_state` (`RESERVED` or `COMPLETED`), `binding_key`, `assignment_id`, `task_run_id`, timestamps. `RESERVED` rows have null binding/assignment/run; `COMPLETED` rows have binding and assignment (the run id is recorded when available). Same key/digest converges; contradictory reuse conflicts.

Important invariants:
- Default changes affect only future captures; existing bindings retain their captured selection indefinitely.
- Materialization `READY` means safe local availability; content is out-of-band.
- Assignment-binding operations are the single durable decision point preventing races between context capture and work submission.

## System Chat Proposals

Source package: [`ai/chat/system`](../../src/main/java/io/mindspice/magenta2/ai/chat/system). Migration 013.

- `system_chat_action_proposals`: persisted queue-operation proposals. Fields: `id` (PK), `conversation_id`, `agent_id`, `target_kind` (`ASSIGNMENT`), `target_id` (assignment id), `action` (`PAUSE`, `RESUME`, `RETRY`, `REPLAY`, `CANCEL`), `expected_state_or_revision`, `canonical_payload_digest` (64-char hex), `operation_key`, `proposal_state` (`PENDING`, `CONFIRMING`, `CONFIRMED`, `DENIED`, `CANCELLED`, `STALE`, `UNAVAILABLE`), `confirmation_operation_key`, `linked_operation_id`, `confirmed_at`, `audit_facts_json`, timestamps. Unique per `operation_key` and per `(target_kind, target_id, action, canonical_payload_digest)`.

Proposals are non-executing; confirmation passes through the ordinary scoped lifecycle control plane.

## Typed Workspaces and Outputs

Source package: [`ai/orchestration/workspaces`](../../src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces).

Filesystem workspace identity is derived from type and key and therefore has no active registry table. Execution tables persist `workspace_type`, `workspace_key`, and `workspace_identity`.

- `task_run_locations`: canonical task/workflow/job run identity, workspace kind/key, task-path key, data-root-relative run path, source/native identity, and optional retry/replay lineage.
- `task_run_attempts`, scoped idempotent operation tables, native/child link tables, assignment fence generations, and writer-claim generations: lifecycle, dispatch linkage, and independent monotonic capability state.
- `output_publications`: task/chat publication reservation and state, scoped workspace/run attribution, data-root-relative staging/final paths, digest/size, reconciliation state, and create-only lifecycle facts.
- `artifact_extraction_documents` and optional `artifact_extraction_fts`: bounded extracted text and capability-dependent FTS5 search.
- `artifact_lineage_edges`, `task_run_retention_policies`, `artifact_retention_pins`, and `active_artifact_sessions`: exact producer/consumer linkage, preservation policy, deletion blockers, and operation-scoped byte access.
- `run_output_artifacts`: inert/compatibility metadata for legacy and non-task rows; it is not canonical task-run publication state.
- `OutputArtifactRepository` owns publication and compatibility persistence operations; ordered migrations own DDL.
- Obsolete generic workspace, link, lease, database-backed file identity, and related structures may remain in existing databases as inert historical data; active startup does not migrate, recreate, read, or write them.

## Skill Candidates, Trust, And Snapshots

- `skill_candidate_versions`: immutable logical key, scope/provenance, digest, relative source identity, parse status, and candidate facts.
- `skill_candidate_observations`: discovery scan observations for a candidate.
- `skill_exact_assignments`: exact candidate overlays by target type/key and operation identity.
- `skill_legacy_conversion_diagnostics`: bounded conversion findings without invented trust.
- `skill_trust_decisions`: approve/reject/revoke audit tied to candidate and optional snapshot.
- `skill_snapshots` and `skill_snapshot_resources`: immutable approved manifest/metadata/resource bytes.
- `skill_activations`: exact snapshot used by a task run/conversation.
- `skill_executable_authorizations`, `skill_package_operations`, and `skill_resource_executions`: separate executable/package authorization and audit planes.

Approval never mutates a snapshot. Source changes create a new candidate. Revocation blocks new activation without deleting historical run evidence. Executable authorization remains fail-closed while process confinement is unavailable.

## Revisioned Schedules And Reactions

- `schedule_definitions`: agent-scoped revision, six-field cron, IANA timezone, dialect/policy versions, enabled/deleted state, and assignment-template snapshot source.
- `scheduler_claims`: owner, monotonic fence, heartbeat, and expiry for the supported scheduler topology.
- `schedule_firings`: unique schedule/revision/slot/timezone identity, operation key/digest, immutable template snapshot, enqueue state, and assignment/run links.
- `schedule_firing_attempts`: append-only, bounded enqueue/recovery attempt audit including claim fence and outcome.
- `reaction_definitions`: revisioned event/filter/action/template state.
- `reaction_dispatches`: unique reaction/revision/source-event identity with request digest, immutable template snapshot, root/parent causation, depth, dedupe, suppression, and assignment/run links.
- `reaction_dispatch_attempts`: append-only dispatch enqueue/recovery attempts.

Automation schema remains owned by immutable `008-schedule-reaction-hardening.sql`. `009-warm-database-compatibility.sql` is the contiguous no-schema marker for transactional legacy-bookkeeping normalization; it does not rename or rewrite migration 008.

## Runtime Settings

Source package: [`ai/orchestration/settings`](../../src/main/java/io/mindspice/magenta2/ai/orchestration/settings).

- `runtime_settings`: singleton-style persisted defaults for default agent/model, planning/summary/compaction models, context buffer, system chat model/prompt/tools/context limit/enabled flag, assignment history auto-purge days, and temp work retention.

`RuntimeSettingsRepository` adds settings columns as needed for warm databases. `RuntimeSettingsService` falls back to legacy file config when persisted values are absent.
