create table if not exists ai_chat_memory (
    conversation_id text not null,
    message_order integer not null,
    message_type text not null,
    message_text text,
    message_metadata_json text,
    primary key (conversation_id, message_order)
);

create index if not exists idx_ai_chat_memory_conversation
    on ai_chat_memory (conversation_id);

create table if not exists ai_chat_pending_messages (
    id text primary key,
    conversation_id text not null,
    message_order integer not null,
    message_text text not null,
    model text,
    planning_model text,
    surface text,
    status text not null,
    claim_token text,
    claimed_at text,
    created_at text not null,
    updated_at text not null
);

create index if not exists idx_ai_chat_pending_messages_status_order
    on ai_chat_pending_messages (conversation_id, status, message_order);

create index if not exists idx_ai_chat_pending_messages_order
    on ai_chat_pending_messages (conversation_id, message_order);

create table if not exists ai_chat_session_metadata (
    conversation_id text primary key,
    model text,
    title text,
    active_task_run_id text,
    planning_model text,
    favorite integer not null default 0,
    archived integer not null default 0,
    origin text,
    surface text,
    agent_id text,
    updated_at text
);

create table if not exists agent_jobs (
    id text primary key,
    type text not null,
    status text not null,
    conversation_id text,
    selected_model text,
    input_json text,
    result_json text,
    error_text text,
    created_at text not null,
    updated_at text not null,
    started_at text,
    completed_at text
);

create index if not exists idx_agent_jobs_conversation
    on agent_jobs (conversation_id);

create unique index if not exists idx_agent_jobs_conversation_title_active
    on agent_jobs (type, conversation_id)
    where type = 'CONVERSATION_TITLE'
      and status in ('QUEUED', 'RUNNING', 'SUCCEEDED');

create table if not exists plan_chat_messages (
    id text primary key,
    plan_id text not null,
    role text not null,
    text text not null,
    created_at text not null
);

create index if not exists idx_plan_chat_messages_plan
    on plan_chat_messages (plan_id, created_at);

-- Unified plan/task definitions.
-- SESSION_PLAN uses id = conversation_id.
-- TASK_TEMPLATE uses a UUID id with optional conversation_id for draft tracking.
-- Steps, inputs, outputs, assumptions, validation criteria, and evidence are
-- stored as JSON arrays/objects in text columns.
create table if not exists plan_definitions (
    id text primary key,
    kind text not null,
    status text not null,
    title text not null,
    summary text,
    goal text,
    notes text,
    deliverables_json text not null,
    inputs_json text not null,
    outputs_json text not null,
    assumptions_json text not null,
    steps_json text not null,
    validation_criteria_json text not null,
    execution_evidence_json text not null,
    validation_feedback_json text not null,
    prompt_profile text,
    planning_model text,
    execution_model text,
    settings_override_json text,
    planning_task text,
    pending_questions_json text,
    pending_question_index integer not null default 0,
    plan_start_message_order integer not null default 0,
    final_message text,
    conversation_id text,
    created_at text not null,
    updated_at text not null
);

create index if not exists idx_plan_definitions_conversation
    on plan_definitions (conversation_id)
    where conversation_id is not null;

-- Execution runs of plan definitions.
-- Snapshots the full definition at start time so later edits do not mutate
-- historical run meaning.
create table if not exists plan_runs (
    id text primary key,
    plan_id text not null,
    run_display_name text,
    status text not null,
    input_values_json text not null,
    output_values_json text not null,
    plan_snapshot_json text not null,
    workspace_type text,
    workspace_key text,
    workspace_identity text,
    execution_evidence_json text not null,
    validation_feedback_json text not null,
    deliverable_evidence_json text not null,
    final_message text,
    error_text text,
    created_at text not null,
    updated_at text not null,
    started_at text,
    completed_at text,
    foreign key (plan_id) references plan_definitions(id) on delete cascade
);

create index if not exists idx_plan_runs_plan
    on plan_runs (plan_id, created_at desc);

-- Immutable append-only event log. Each row is a discrete, known event in the
-- chat lifecycle, recorded explicitly at the point it occurs. Never deduped, never
-- derived from ai_chat_memory state — we control the flow and know when each event happens.
create table if not exists audit_event (
    id integer primary key autoincrement,
    conversation_id text not null,
    sequence integer not null,
    event_type text not null,
    -- message content (user_msg, assistant_msg)
    message_text text,
    message_metadata_json text,
    model text,
    -- tool execution (tool_exec)
    tool_call_id text,
    tool_name text,
    arguments_json text,
    arguments_summary text,
    call_preview text,
    result_text text,
    result_summary text,
    result_preview text,
    tool_status text,
    result_truncated integer default 0,
    result_large integer default 0,
    -- compaction (compaction)
    compaction_method text,
    compaction_summary text,
    -- context snapshot (context, compaction)
    used_tokens integer,
    max_tokens integer,
    trigger_tokens integer,
    percent_used real,
    stored_message_count integer,
    error_type text,
    stack_trace text,
    recorded_at text not null
);

create unique index if not exists idx_audit_event_conversation
    on audit_event (conversation_id, sequence);

-- Workflow definitions with nodes and routes stored as JSON.
create table if not exists workflow_definitions (
    id text primary key,
    schema_version integer not null default 2,
    title text not null,
    summary text,
    max_concurrency integer not null default 4,
    nodes_json text not null,
    routes_json text not null default '[]',
    ui_layout_json text not null default '{}',
    created_at text not null,
    updated_at text not null
);

-- Execution runs of workflow definitions. Snapshots the full definition
-- at start time so later edits do not mutate historical run meaning.
-- currentNodeIndex points to the active or next node to execute.
create table if not exists workflow_runs (
    id text primary key,
    workflow_id text not null,
    run_display_name text,
    status text not null,
    current_node_index integer not null default 0,
    node_runs_json text not null,
    agent_id text,
    job_id text,
    job_assignment_id text,
    job_run_id text,
    project_id text,
    workspace_type text,
    workspace_key text,
    workspace_identity text,
    run_type text,
    workflow_snapshot_json text not null,
    final_outputs_json text not null default '{}',
    artifact_ids_json text not null default '[]',
    final_message text,
    error_text text,
    created_at text not null,
    updated_at text not null,
    started_at text,
    completed_at text,
    foreign key (workflow_id) references workflow_definitions(id) on delete cascade
);

create index if not exists idx_workflow_runs_workflow
    on workflow_runs (workflow_id, created_at desc);

-- Per-node run state. Denormalized from workflow_runs.node_runs_json
-- for queryability and indexing.
create table if not exists workflow_node_runs (
    id text primary key,
    workflow_run_id text not null,
    node_key text not null,
    node_type text not null,
    node_index integer not null,
    status text not null,
    input_values_json text not null,
    output_values_json text not null,
    started_at text,
    completed_at text,
    foreign key (workflow_run_id) references workflow_runs(id) on delete cascade
);

create index if not exists idx_workflow_node_runs_run
    on workflow_node_runs (workflow_run_id, node_index);

-- Orchestration and agent tables (repository-owned bootstrapping with schema.sql as canonical source)

create table if not exists agent_profiles (
    id text primary key,
    name text not null unique,
    status text not null,
    default_model text,
    system_prompt_text text,
    approved_tool_names_json text not null,
    allowed_shell_commands_json text not null,
    direct_line_enabled integer not null,
    created_at text not null,
    updated_at text not null
);

create table if not exists orchestration_jobs (
    id text primary key,
    owner_agent_id text not null,
    title text not null,
    summary text,
    default_model text,
    workspace_type text,
    workspace_key text,
    workspace_identity text,
    status text not null,
    created_at text not null,
    updated_at text not null
);

create table if not exists orchestration_job_items (
    id text primary key,
    job_id text not null,
    item_order integer not null,
    item_type text not null,
    task_id text,
    workflow_id text,
    model_override text,
    priority integer not null,
    retry_count integer not null default 0,
    continue_on_failure integer not null default 0,
    config_json text,
    created_at text not null,
    updated_at text not null,
    foreign key(job_id) references orchestration_jobs(id)
);

create table if not exists work_assignments (
    id text primary key,
    agent_id text not null,
    job_id text,
    job_item_id text,
    assignment_type text not null,
    priority integer not null,
    status text not null,
    model_override text,
    run_display_name text,
    workspace_type text,
    workspace_key text,
    workspace_identity text,
    project_id text,
    current_item_index integer not null,
    checkpoint_json text,
    input_json text,
    output_json text,
    evidence_json text,
    error_text text,
    lease_owner text,
    lease_expires_at text,
    lease_fence integer not null default 0 check (lease_fence >= 0),
    last_progress_at text,
    last_heartbeat_at text,
    created_at text not null,
    updated_at text not null,
    started_at text,
    completed_at text
);

create index if not exists idx_work_assignments_queue
    on work_assignments(status, priority, created_at);

create table if not exists assignment_conversation_links (
    assignment_id text not null,
    conversation_id text not null,
    created_at text not null,
    primary key (assignment_id, conversation_id)
);

create index if not exists idx_assignment_conversation_links_assignment
    on assignment_conversation_links(assignment_id, created_at);

-- Workflow inbox messages owned by ai.orchestration.workflow.
-- Used for workflow/user approvals, workflow agent approval nodes, notifications,
-- and run-output delivery. Runtime direct-line agent/operator inbox messages
-- are intentionally stored in agent_inbox_messages below.
create table if not exists inbox_messages (
    id text primary key,
    to_type text not null,
    to_id text,
    from_id text,
    message_type text not null,
    body text,
    metadata_json text,
    response_json text,
    responded_at text,
    handled_at text,
    created_at text not null,
    updated_at text not null
);

create index if not exists idx_inbox_messages_to
    on inbox_messages (to_type, to_id, created_at desc);

-- Runtime direct-line agent inbox messages owned by ai.orchestration.runtime.
-- Used by agent/operator inbox surfaces for direct-line messages, read state,
-- handled state, and runtime inbox events. This table is intentionally separate
-- from workflow-owned inbox_messages because the message models and lifecycle
-- states differ.
create table if not exists agent_inbox_messages (
    id text primary key,
    to_agent_id text not null,
    from_id text,
    message_type text not null,
    body text,
    metadata_json text,
    read_flag integer not null,
    handled_flag integer not null,
    created_at text not null,
    updated_at text not null
);

create index if not exists idx_agent_inbox_messages_to
    on agent_inbox_messages (to_agent_id, created_at desc);

create table if not exists agent_schedules (
    id text primary key,
    agent_id text not null,
    job_id text,
    assignment_template_json text,
    cron_expression text not null,
    timezone text not null,
    enabled_flag integer not null,
    next_run_at text,
    created_at text not null,
    updated_at text not null
);

create table if not exists schedule_firings (
    id text primary key,
    schedule_id text not null,
    due_at text not null,
    assignment_id text not null,
    created_at text not null,
    unique(schedule_id, due_at),
    foreign key(schedule_id) references agent_schedules(id)
);

create table if not exists agent_event_reactions (
    id text primary key,
    agent_id text not null,
    event_type text not null,
    filter_json text,
    action_type text not null,
    assignment_template_json text,
    enabled_flag integer not null,
    created_at text not null,
    updated_at text not null
);

create table if not exists orchestration_events (
    id text primary key,
    event_type text not null,
    source_type text,
    source_id text,
    payload_json text,
    created_at text not null,
    handled_at text
);

create table if not exists runtime_settings (
    id text primary key,
    default_agent_id text,
    default_agent_name text,
    default_model text,
    planning_model text,
    summary_model text,
    compaction_model text,
    context_buffer_percent integer,
    system_chat_model text,
    system_chat_prompt text,
    system_chat_approved_tools text,
    system_chat_context_limit integer,
    system_chat_enabled integer,
    assignment_history_auto_purge_days integer not null default -1,
    retain_temp_work integer not null default 0
);

create table if not exists workspace_file_labels (
    id text primary key,
    slug text not null unique,
    display_name text not null,
    color text,
    system_flag integer not null default 0,
    metadata_json text not null default '{}',
    created_at text not null,
    updated_at text not null
);

-- Canonical workspace annotations. Obsolete warm-database tables are not
-- declared, altered, queried, or dropped by active explorer persistence.
create table if not exists workspace_file_annotations (
    id text primary key,
    workspace_identity text not null,
    file_relative_path text not null,
    label_id text not null,
    metadata_json text not null default '{}',
    created_at text not null,
    updated_at text not null,
    unique(workspace_identity, file_relative_path, label_id),
    foreign key(label_id) references workspace_file_labels(id)
);

create index if not exists idx_workspace_file_annotations_path
    on workspace_file_annotations(workspace_identity, file_relative_path);

create index if not exists idx_workspace_file_annotations_label
    on workspace_file_annotations(label_id);

-- Canonical workspace action log.
create table if not exists workspace_file_action_log (
    id text primary key,
    workspace_identity text not null,
    actor_type text,
    actor_id text,
    action_type text not null,
    source_relative_path text,
    target_relative_path text,
    result text not null,
    payload_json text not null default '{}',
    created_at text not null
);

create index if not exists idx_workspace_file_action_log_identity
    on workspace_file_action_log(workspace_identity, created_at desc);

create index if not exists idx_workspace_file_action_log_type
    on workspace_file_action_log(action_type, created_at desc);

-- Output artifacts materialized during plan/task runs. Each row records
-- a single output file written or copied into the run's output directory.
create table if not exists run_output_artifacts (
    id text primary key,
    run_id text not null,
    plan_id text not null,
    agent_id text,
    job_id text,
    job_assignment_id text,
    job_run_id text,
    project_id text,
    workspace_type text,
    workspace_key text,
    workspace_identity text,
    run_type text,
    output_name text not null,
    artifact_type text not null,
    file_name text not null,
    file_path text not null,
    content_json text,
    created_at text not null
);

create index if not exists idx_run_output_artifacts_run
    on run_output_artifacts(run_id);

create index if not exists idx_run_output_artifacts_agent
    on run_output_artifacts(agent_id);

create index if not exists idx_run_output_artifacts_job
    on run_output_artifacts(job_id);

create index if not exists idx_run_output_artifacts_project
    on run_output_artifacts(project_id);

-- ════════════════════════════════════════════════════════════════
--  Phase 04: Jobs, Projects, Agent Networks
-- ════════════════════════════════════════════════════════════════

-- Job definitions: coordinate multiple plan or workflow work items.
-- items_json is a JSON array of JobWorkItem objects.
create table if not exists job_definitions (
    id text primary key,
    owner_agent_id text,
    project_id text,
    workspace_type text,
    workspace_key text,
    workspace_identity text,
    status text,
    title text not null,
    summary text,
    items_json text not null,
    prompt_profile text,
    model text,
    settings_override_json text,
    created_at text not null,
    updated_at text not null
);

-- A single execution run of a job definition.
-- work_item_runs_json is a JSON array of JobWorkItemRun objects.
create table if not exists job_runs (
    id text primary key,
    job_id text not null,
    job_assignment_id text,
    workspace_type text,
    workspace_key text,
    workspace_identity text,
    status text not null,
    work_item_runs_json text not null,
    final_message text,
    error_text text,
    created_at text not null,
    updated_at text not null,
    started_at text,
    completed_at text,
    foreign key (job_id) references job_definitions(id) on delete cascade
);

create index if not exists idx_job_runs_job
    on job_runs (job_id, created_at desc);

-- Recurrence rules for repeated job scheduling.
create table if not exists job_recurrences (
    id text primary key,
    job_id text not null unique,
    cron_expression text not null,
    timezone text not null,
    next_fire_time text,
    enabled integer not null default 1,
    created_at text not null,
    updated_at text not null,
    foreign key (job_id) references job_definitions(id) on delete cascade
);

-- Projects: durable data-space and tracking wrappers.
create table if not exists projects (
    id text primary key,
    name text not null,
    description text,
    owner_agent_id text,
    git_repo_url text,
    prompt_profile text,
    model text,
    settings_override_json text,
    created_at text not null,
    updated_at text not null
);

-- Agent membership in projects.
create table if not exists project_agent_memberships (
    id text primary key,
    project_id text not null,
    agent_id text not null,
    role text not null default 'member',
    joined_at text not null,
    foreign key (project_id) references projects(id) on delete cascade
);

create unique index if not exists idx_project_membership_unique
    on project_agent_memberships (project_id, agent_id);

-- Project-scoped events (immutable append-only log).
create table if not exists project_events (
    id text primary key,
    project_id text not null,
    type text not null,
    payload_json text,
    created_at text not null,
    foreign key (project_id) references projects(id) on delete cascade
);

create index if not exists idx_project_events_project
    on project_events (project_id, created_at desc);

-- ════════════════════════════════════════════════════════════════
--  Task-Run Foundation (migration 001 equivalent)
-- ════════════════════════════════════════════════════════════════

-- Migration 001: canonical task-run companion state.
create table if not exists task_run_locations (
    task_run_id        text primary key
        check (length(task_run_id) between 1 and 255
            and task_run_id not in ('.', '..', 'tasks', 'outputs', 'staging', '.skills', 'workspace')
            and instr(task_run_id, '/') = 0 and instr(task_run_id, char(92)) = 0),
    source_kind        text not null check (source_kind in ('task', 'workflow', 'job')),
    source_id          text not null check (length(source_id) > 0),
    workspace_type     text not null check (workspace_type in ('agent', 'user')),
    workspace_key      text not null check (length(workspace_key) > 0
        and workspace_key not in ('.', '..')
        and instr(workspace_key, '/') = 0 and instr(workspace_key, char(92)) = 0),
    workspace_identity text not null
        check (workspace_identity = workspace_type || '/' || workspace_key),
    task_path_key      text not null check (
        (source_kind = 'task' and substr(task_path_key, 1, 8) = 'v1-task-'
            and length(task_path_key) = 72 and substr(task_path_key, 9) not glob '*[^0-9a-f]*')
        or (source_kind = 'workflow' and substr(task_path_key, 1, 12) = 'v1-workflow-'
            and length(task_path_key) = 76 and substr(task_path_key, 13) not glob '*[^0-9a-f]*')
        or (source_kind = 'job' and substr(task_path_key, 1, 7) = 'v1-job-'
            and length(task_path_key) = 71 and substr(task_path_key, 8) not glob '*[^0-9a-f]*')),
    root_relative_path text not null check (
        root_relative_path = 'workspace/' || workspace_type || '/' || workspace_key
            || '/tasks/' || task_path_key || '/' || task_run_id),
    created_at         text not null,
    updated_at         text not null,
    lineage_parent_task_run_id text references task_run_locations(task_run_id),
    lineage_mode       text check (lineage_mode in ('RETRY_OF', 'REPLAY_OF')),
    unique (workspace_type, workspace_key, root_relative_path)
);

create index if not exists idx_task_run_locations_workspace
    on task_run_locations (workspace_identity, created_at desc);
create index if not exists idx_task_run_locations_source
    on task_run_locations (source_kind, source_id, created_at desc);

create table if not exists task_run_attempts (
    task_run_id        text not null,
    attempt_number     integer not null check (attempt_number > 0),
    status             text not null,
    started_at         text,
    completed_at       text,
    error_text         text,
    created_at         text not null,
    updated_at         text not null,
    primary key (task_run_id, attempt_number),
    foreign key (task_run_id) references task_run_locations(task_run_id) on delete cascade
) without rowid;

create index if not exists idx_task_run_attempts_status
    on task_run_attempts (status, created_at desc);

create table if not exists task_run_idempotent_ops (
    id                 integer primary key,
    operation_type     text check (operation_type is null or operation_type in ('RESERVE', 'RESUME', 'RETRY', 'REPLAY')),
    operation_key      text not null check (length(operation_key) > 0),
    request_digest     text not null check (
        length(request_digest) = 64 and request_digest not glob '*[^0-9a-f]*'),
    operation_state    text not null check (operation_state in ('RESERVED', 'COMPLETED')),
    task_run_id        text,
    result_json        text,
    created_at         text not null,
    updated_at         text not null,
    unique (operation_type, operation_key),
    foreign key (task_run_id) references task_run_locations(task_run_id)
);

create table if not exists task_run_native_links (
    task_run_id        text primary key,
    native_kind        text not null check (native_kind in ('task', 'workflow', 'job')),
    native_run_id      text not null check (length(native_run_id) > 0),
    assignment_id      text,
    workflow_child_id  text,
    job_work_item_id   text,
    created_at         text not null,
    unique (native_kind, native_run_id),
    check (workflow_child_id is null or native_kind = 'workflow'),
    check (job_work_item_id is null or native_kind = 'job'),
    foreign key (task_run_id) references task_run_locations(task_run_id) on delete cascade,
    foreign key (assignment_id) references work_assignments(id)
) without rowid;

create index if not exists idx_task_run_native_links_assignment
    on task_run_native_links (assignment_id) where assignment_id is not null;
create index if not exists idx_task_run_native_links_workflow_child
    on task_run_native_links (workflow_child_id) where workflow_child_id is not null;
create index if not exists idx_task_run_native_links_job_work_item
    on task_run_native_links (job_work_item_id) where job_work_item_id is not null;

-- Assignment fence generation tracking.
create table if not exists assignment_fence_generations (
    assignment_id    text not null,
    generation       integer not null check (generation > 0),
    owner_id         text not null check (length(owner_id) > 0),
    claimed_at       text not null,
    primary key (assignment_id, generation),
    foreign key (assignment_id) references work_assignments(id)
) without rowid;

-- Writer claim generation tracking with current-claim flag.
create table if not exists writer_claim_generations (
    task_run_id      text not null,
    attempt_number   integer not null check (attempt_number > 0),
    generation       integer not null check (generation > 0),
    owner_id         text not null check (length(owner_id) > 0),
    fence_token      text not null unique check (length(fence_token) > 0),
    is_current       integer not null default 1 check (is_current in (0, 1)),
    superseded_at    text,
    created_at       text not null,
    primary key (task_run_id, attempt_number, generation),
    unique (task_run_id, generation),
    foreign key (task_run_id, attempt_number)
        references task_run_attempts(task_run_id, attempt_number) on delete cascade
) without rowid;

create unique index if not exists idx_writer_claim_generations_current
    on writer_claim_generations (task_run_id, attempt_number)
    where is_current = 1;

create table if not exists task_run_links (
    parent_task_run_id text not null,
    child_task_run_id  text not null,
    link_type          text not null check (link_type in ('WORKFLOW_CHILD', 'JOB_WORK_ITEM')),
    link_key           text not null check (length(link_key) > 0),
    created_at         text not null,
    primary key (parent_task_run_id, link_type, link_key),
    unique (child_task_run_id, link_type),
    check (parent_task_run_id <> child_task_run_id),
    foreign key (parent_task_run_id) references task_run_locations(task_run_id) on delete cascade,
    foreign key (child_task_run_id) references task_run_locations(task_run_id) on delete cascade
) without rowid;

create index if not exists idx_task_run_links_child on task_run_links(child_task_run_id);

-- ════════════════════════════════════════════════════════════════
--  Artifact Publication (migration 003 equivalent)
-- ════════════════════════════════════════════════════════════════

create table if not exists output_publications (
    artifact_id          text primary key
        check (length(artifact_id) between 1 and 255
            and instr(artifact_id, '/') = 0 and instr(artifact_id, char(92)) = 0
            and artifact_id not in ('.', '..')),
    publication_origin   text not null check (publication_origin in ('TASK_RUN', 'CHAT', 'LEGACY')),
    workspace_kind       text not null check (workspace_kind in ('agent', 'chat', 'user')),
    workspace_key        text not null check (length(workspace_key) > 0
        and workspace_key not in ('.', '..')
        and instr(workspace_key, '/') = 0 and instr(workspace_key, char(92)) = 0),
    workspace_identity   text not null
        check (workspace_identity = workspace_kind || '/' || workspace_key),
    task_run_id          text,
    task_path_key        text,
    native_run_id        text,
    attempt_number       integer check (attempt_number is null or attempt_number > 0),
    root_relative_path   text not null
        check (length(root_relative_path) > 0
            and substr(root_relative_path, 1, 1) <> '/'
            and instr(root_relative_path, '\\') = 0
            and instr(root_relative_path, char(10)) = 0),
    staging_path         text
        check (staging_path is null or (length(staging_path) > 0
            and substr(staging_path, 1, 1) <> '/'
            and instr(staging_path, '\\') = 0)),
    operation_key        text not null check (length(operation_key) > 0),
    request_digest       text not null check (
        length(request_digest) = 64 and request_digest not glob '*[^0-9a-f]*'),
    content_sha256       text not null check (
        length(content_sha256) = 64 and content_sha256 not glob '*[^0-9a-f]*'),
    publication_state    text not null check (publication_state in (
        'RESERVED', 'STAGED', 'PUBLISHED', 'FAILED', 'INTEGRITY_FAILED')),
    reconciliation_state text check (reconciliation_state is null or reconciliation_state in (
        'RECONCILED', 'UNRESOLVED', 'CLEANUP_PENDING', 'DELETING')),
    failure_detail       text,
    expected_sha256      text not null check (
        length(expected_sha256) = 64 and expected_sha256 not glob '*[^0-9a-f]*'),
    verified_sha256      text check (
        verified_sha256 is null or (length(verified_sha256) = 64
            and verified_sha256 not glob '*[^0-9a-f]*')),
    verified_size        integer check (verified_size is null or verified_size >= 0),
    media_type           text check (media_type is null or length(media_type) > 0),
    writer_owner_id      text,
    fence_generation     integer check (fence_generation is null or fence_generation > 0),
    logical_name         text not null check (length(logical_name) > 0),
    file_extension       text,
    created_at           text not null,
    updated_at           text not null,
    staged_at            text,
    published_at         text,
    unique (publication_origin, operation_key),
    unique (workspace_kind, workspace_key, root_relative_path)
);

create index if not exists idx_output_publications_workspace
    on output_publications (workspace_identity, created_at desc);
create index if not exists idx_output_publications_task_run
    on output_publications (task_run_id, created_at desc)
    where task_run_id is not null;
create index if not exists idx_output_publications_state
    on output_publications (publication_state, workspace_identity);
create index if not exists idx_output_publications_reconciliation
    on output_publications (reconciliation_state, updated_at)
    where reconciliation_state is not null;

create table if not exists schema_migrations (
    version       integer primary key,
    resource_name text not null unique,
    checksum      text not null check (length(checksum) = 64),
    applied_at    text not null
);

-- ════════════════════════════════════════════════════════════════
--  Scoped Skill Resolution (migration 004 equivalent)
-- ════════════════════════════════════════════════════════════════

create table if not exists skill_candidate_versions (
    id                    integer primary key autoincrement,
    logical_key           text not null check (length(logical_key) between 1 and 64
        and logical_key not glob '*[^a-z0-9-]*' and logical_key not like '%--%'),
    scope_type            text not null check (scope_type in ('WORKSPACE', 'BOUND_AGENT', 'PROJECT', 'GLOBAL')),
    authoritative_root_id text not null check (length(authoritative_root_id) between 1 and 263
        and instr(authoritative_root_id, char(92)) = 0
        and substr(authoritative_root_id, 1, 1) <> '/'
        and instr(authoritative_root_id, '..') = 0
        and ((scope_type = 'GLOBAL' and authoritative_root_id = 'global')
          or (scope_type = 'PROJECT' and authoritative_root_id like 'project/%')
          or (scope_type in ('WORKSPACE', 'BOUND_AGENT')
              and (authoritative_root_id like 'agent/%' or authoritative_root_id like 'user/%')))),
    provenance            text not null check (provenance in ('GLOBAL_PACKAGE', 'WORKSPACE_FLAT')),
    format_version        integer not null,
    content_digest        text not null check (length(content_digest) = 64 and content_digest not glob '*[^0-9a-f]*'),
    created_at            text not null,
    unique (logical_key, scope_type, authoritative_root_id, provenance, format_version, content_digest)
);

create index if not exists idx_skill_candidate_versions_lookup
    on skill_candidate_versions (logical_key, scope_type, authoritative_root_id);

create table if not exists skill_candidate_observations (
    id                    integer primary key autoincrement,
    candidate_version_id  integer not null,
    is_available          integer not null check (is_available in (0, 1)),
    diagnostics_json      text not null check (length(diagnostics_json) <= 32768),
    observed_at           text not null,
    scan_id               text not null check (length(scan_id) between 1 and 255
        and instr(scan_id, char(92)) = 0),
    foreign key (candidate_version_id) references skill_candidate_versions(id)
);

create index if not exists idx_skill_candidate_observations_candidate
    on skill_candidate_observations (candidate_version_id, observed_at desc);
create index if not exists idx_skill_candidate_observations_scan
    on skill_candidate_observations (scan_id);

create table if not exists skill_exact_assignments (
    id                    integer primary key autoincrement,
    candidate_version_id  integer not null,
    logical_key           text not null,
    target_type           text not null check (target_type in ('AGENT', 'PROJECT', 'JOB', 'TASK', 'WORKFLOW', 'CHAT_SESSION')),
    target_key            text not null check (length(target_key) between 1 and 255
        and instr(target_key, '/') = 0 and instr(target_key, char(92)) = 0),
    enabled               integer not null check (enabled in (0, 1)),
    operation_key         text check (operation_key is null or length(operation_key) between 1 and 255),
    actor                 text check (actor is null or (length(actor) between 1 and 255
        and instr(actor, '/') = 0 and instr(actor, char(92)) = 0)),
    created_at            text not null,
    updated_at            text not null,
    unique (logical_key, target_type, target_key),
    foreign key (candidate_version_id) references skill_candidate_versions(id)
);

create index if not exists idx_skill_exact_assignments_target
    on skill_exact_assignments (target_type, target_key);
create unique index if not exists idx_skill_exact_assignments_operation_key
    on skill_exact_assignments (operation_key) where operation_key is not null;

create table if not exists skill_legacy_conversion_diagnostics (
    id                    integer primary key autoincrement,
    agent_id              text not null,
    logical_key           text not null,
    result                text not null check (result in ('CONVERTED', 'UNAMBIGUOUS', 'AMBIGUOUS', 'NO_MATCH')),
    candidate_version_id  integer,
    diagnostics_json      text not null check (length(diagnostics_json) <= 32768),
    converted_at          text not null,
    unique (agent_id, logical_key),
    foreign key (candidate_version_id) references skill_candidate_versions(id)
);

-- ════════════════════════════════════════════════════════════════
--  Artifact Access, Search, Retention (migration 005 equivalent)
-- ════════════════════════════════════════════════════════════════

create table if not exists artifact_extraction_documents (
    id              integer primary key autoincrement,
    artifact_id     text not null unique
        check (length(artifact_id) between 1 and 255
            and instr(artifact_id, '/') = 0 and instr(artifact_id, char(92)) = 0),
    content_sha256  text not null check (
        length(content_sha256) = 64 and content_sha256 not glob '*[^0-9a-f]*'),
    extracted_text  text not null,
    byte_count      integer not null check (byte_count >= 0),
    line_count      integer not null check (line_count >= 0),
    language_hint   text,
    extraction_state text not null check (extraction_state in (
        'EXTRACTED', 'TRUNCATED', 'UNSUPPORTED', 'FAILED')),
    extracted_at    text not null,
    foreign key (artifact_id) references output_publications(artifact_id) on delete cascade
);

create virtual table if not exists artifact_extraction_fts using fts5 (
    extracted_text,
    language_hint,
    content='artifact_extraction_documents',
    content_rowid='id',
    tokenize='porter unicode61'
);

-- FTS5 synchronization is managed programmatically in ArtifactSearchService.
-- Triggers are omitted to avoid Spring ScriptUtils semicolon splitting issues.

create table if not exists artifact_lineage_edges (
    id                      integer primary key autoincrement,
    parent_artifact_id      text not null
        check (length(parent_artifact_id) between 1 and 255),
    child_artifact_id       text not null
        check (length(child_artifact_id) between 1 and 255),
    lineage_kind            text not null check (lineage_kind in (
        'DERIVED_FROM', 'GENERATED_BY', 'TRANSFORMED_FROM')),
    producer_workspace_kind text not null check (producer_workspace_kind in ('agent', 'user')),
    producer_workspace_key  text not null check (length(producer_workspace_key) > 0),
    producer_task_run_id    text,
    declaration_digest      text not null check (
        length(declaration_digest) = 64 and declaration_digest not glob '*[^0-9a-f]*'),
    created_at              text not null,
    unique (parent_artifact_id, child_artifact_id, lineage_kind),
    check (parent_artifact_id <> child_artifact_id),
    foreign key (parent_artifact_id) references output_publications(artifact_id)
        on delete cascade,
    foreign key (child_artifact_id) references output_publications(artifact_id)
        on delete cascade
);

create index if not exists idx_lineage_edges_parent
    on artifact_lineage_edges (parent_artifact_id);
create index if not exists idx_lineage_edges_child
    on artifact_lineage_edges (child_artifact_id);
create index if not exists idx_lineage_edges_task_run
    on artifact_lineage_edges (producer_task_run_id)
    where producer_task_run_id is not null;

create table if not exists task_run_retention_policies (
    id                  integer primary key autoincrement,
    workspace_kind      text not null check (workspace_kind in ('agent', 'user')),
    workspace_key       text not null check (length(workspace_key) > 0),
    task_path_key       text,
    policy_type         text not null check (policy_type in ('PRESERVE', 'SOFT_ARCHIVE', 'HARD_EXPIRE')),
    retain_days         integer check (retain_days is null or retain_days > 0),
    max_artifact_count  integer check (max_artifact_count is null or max_artifact_count > 0),
    max_total_bytes     integer check (max_total_bytes is null or max_total_bytes > 0),
    enabled             integer not null default 1 check (enabled in (0, 1)),
    created_at          text not null,
    updated_at          text not null,
    unique (workspace_kind, workspace_key, task_path_key)
);

create index if not exists idx_retention_policies_workspace
    on task_run_retention_policies (workspace_kind, workspace_key);

create table if not exists artifact_retention_pins (
    id              integer primary key autoincrement,
    artifact_id     text check (artifact_id is null or
        (length(artifact_id) between 1 and 255
            and instr(artifact_id, '/') = 0 and instr(artifact_id, char(92)) = 0)),
    task_run_id     text check (task_run_id is null or
        (length(task_run_id) between 1 and 255
            and task_run_id not in ('.', '..')
            and instr(task_run_id, '/') = 0 and instr(task_run_id, char(92)) = 0)),
    pin_kind        text not null check (pin_kind in (
        'LINEAGE', 'RECONCILIATION_PENDING', 'ACTIVE_CLAIM',
        'LEGAL_HOLD', 'MANUAL')),
    reason          text not null check (length(reason) > 0),
    pinned_by       text,
    pinned_at       text not null,
    expires_at      text,
    check (artifact_id is not null or task_run_id is not null),
    check (pin_kind <> 'LINEAGE' or artifact_id is not null)
);

create index if not exists idx_retention_pins_artifact
    on artifact_retention_pins (artifact_id)
    where artifact_id is not null;
create index if not exists idx_retention_pins_task_run
    on artifact_retention_pins (task_run_id)
    where task_run_id is not null;
create index if not exists idx_retention_pins_kind
    on artifact_retention_pins (pin_kind);

create table if not exists active_artifact_sessions (
    id              integer primary key autoincrement,
    artifact_id     text not null
        check (length(artifact_id) between 1 and 255),
    session_token   text not null unique check (length(session_token) between 1 and 128),
    owner_identity  text not null check (length(owner_identity) > 0),
    bytes_read      integer not null default 0 check (bytes_read >= 0),
    max_bytes       integer check (max_bytes is null or max_bytes > 0),
    state           text not null check (state in ('ACTIVE', 'COMPLETED', 'CANCELLED',
        'ABANDONED')),
    created_at      text not null,
    last_activity   text not null,
    completed_at    text,
    foreign key (artifact_id) references output_publications(artifact_id) on delete cascade
);

create index if not exists idx_active_sessions_artifact
    on active_artifact_sessions (artifact_id)
    where state = 'ACTIVE';
create index if not exists idx_active_sessions_token
    on active_artifact_sessions (session_token);

-- ════════════════════════════════════════════════════════════════
--  Skill Trust, Snapshots, Activation (migration 007 equivalent)
-- ════════════════════════════════════════════════════════════════

create table if not exists skill_snapshots (
    id                    integer primary key autoincrement,
    candidate_version_id  integer not null,
    logical_key           text not null,
    provenance            text not null check (provenance in ('GLOBAL_PACKAGE', 'WORKSPACE_FLAT')),
    format_version        integer not null,
    snapshot_digest       text not null check (
        length(snapshot_digest) = 64 and snapshot_digest not glob '*[^0-9a-f]*'),
    skill_name            text not null check (length(skill_name) between 1 and 128),
    description           text not null check (length(description) between 1 and 1024),
    instructions          text not null check (length(instructions) <= 262144),
    hints                 text check (hints is null or length(hints) <= 4096),
    allowed_tools         text check (allowed_tools is null or length(allowed_tools) <= 4096),
    resource_manifest_json text not null check (length(resource_manifest_json) <= 65536),
    created_at            text not null,
    unique (snapshot_digest),
    unique (candidate_version_id),
    foreign key (candidate_version_id) references skill_candidate_versions(id)
);

create index if not exists idx_skill_snapshots_logical_key
    on skill_snapshots (logical_key, created_at desc);

create table if not exists skill_trust_decisions (
    id                    integer primary key autoincrement,
    candidate_version_id  integer not null,
    logical_key           text not null,
    decision              text not null check (decision in ('APPROVE', 'REJECT', 'REVOKE')),
    snapshot_id           integer,
    actor                 text check (actor is null or (length(actor) between 1 and 255
        and instr(actor, '/') = 0 and instr(actor, char(92)) = 0)),
    reason                text check (reason is null or length(reason) <= 4096),
    scope                 text not null check (scope in ('INSTRUCTION', 'EXECUTABLE_RESOURCE', 'PACKAGE_INGEST')),
    operation_key         text not null check (length(operation_key) between 1 and 255),
    request_digest        text not null check (
        length(request_digest) = 64 and request_digest not glob '*[^0-9a-f]*'),
    decided_at            text not null,
    unique (operation_key),
    foreign key (candidate_version_id) references skill_candidate_versions(id),
    foreign key (snapshot_id) references skill_snapshots(id)
);

create index if not exists idx_skill_trust_decisions_candidate
    on skill_trust_decisions (candidate_version_id, decided_at desc);
create index if not exists idx_skill_trust_decisions_logical_key
    on skill_trust_decisions (logical_key, decision, decided_at desc);

create table if not exists skill_snapshot_resources (
    id                    integer primary key autoincrement,
    snapshot_id           integer not null,
    relative_path         text not null check (
        length(relative_path) between 1 and 1024
        and substr(relative_path, 1, 1) <> '/'
        and instr(relative_path, char(92)) = 0
        and instr(relative_path, '..') = 0
        and instr(relative_path, char(10)) = 0),
    resource_type         text not null check (
        resource_type in ('SCRIPT', 'REFERENCE', 'ASSET', 'SKILL_MD')),
    content_length        integer not null check (content_length >= 0),
    content_bytes         blob not null,
    content_digest        text not null check (
        length(content_digest) = 64 and content_digest not glob '*[^0-9a-f]*'),
    unique (snapshot_id, relative_path),
    foreign key (snapshot_id) references skill_snapshots(id)
);

create index if not exists idx_skill_snapshot_resources_snapshot
    on skill_snapshot_resources (snapshot_id, relative_path);

create table if not exists skill_activations (
    id                    integer primary key autoincrement,
    snapshot_id           integer not null,
    logical_key           text not null,
    task_run_id           text not null check (length(task_run_id) between 1 and 255
        and instr(task_run_id, '/') = 0 and instr(task_run_id, char(92)) = 0),
    conversation_id       text check (conversation_id is null or length(conversation_id) between 1 and 255),
    agent_id              text not null check (length(agent_id) between 1 and 255
        and instr(agent_id, '/') = 0 and instr(agent_id, char(92)) = 0),
    activation_context    text not null check (length(activation_context) between 1 and 512),
    activated_at          text not null,
    unique (snapshot_id, activation_context),
    foreign key (snapshot_id) references skill_snapshots(id)
);

create index if not exists idx_skill_activations_task_run
    on skill_activations (task_run_id, activated_at desc);
create index if not exists idx_skill_activations_agent
    on skill_activations (agent_id, logical_key, activated_at desc);

create table if not exists skill_executable_authorizations (
    id                    integer primary key autoincrement,
    snapshot_id           integer not null,
    snapshot_resource_id  integer not null,
    logical_key           text not null,
    decision              text not null check (decision in ('AUTHORIZED', 'DENIED', 'REVOKED')),
    actor                 text check (actor is null or (length(actor) between 1 and 255
        and instr(actor, '/') = 0 and instr(actor, char(92)) = 0)),
    reason                text check (reason is null or length(reason) <= 4096),
    confinement_capability text not null check (
        confinement_capability in ('PROVEN', 'UNAVAILABLE', 'DISABLED')),
    operation_key         text not null check (length(operation_key) between 1 and 255),
    request_digest        text not null check (
        length(request_digest) = 64 and request_digest not glob '*[^0-9a-f]*'),
    decided_at            text not null,
    unique (operation_key),
    unique (snapshot_id, snapshot_resource_id),
    foreign key (snapshot_id) references skill_snapshots(id),
    foreign key (snapshot_resource_id) references skill_snapshot_resources(id)
);

create index if not exists idx_skill_executable_authorizations_resource
    on skill_executable_authorizations (snapshot_resource_id, decision);

create table if not exists skill_package_operations (
    id                    integer primary key autoincrement,
    operation_type        text not null check (operation_type in ('INGEST', 'INSTALL', 'REJECT')),
    operation_key         text not null check (length(operation_key) between 1 and 255),
    request_digest        text not null check (
        length(request_digest) = 64 and request_digest not glob '*[^0-9a-f]*'),
    operation_state       text not null check (operation_state in (
        'STAGING', 'STAGED', 'INSTALLING', 'INSTALLED', 'FAILED', 'REJECTED')),
    staging_path          text check (staging_path is null or (
        length(staging_path) > 0
        and substr(staging_path, 1, 1) <> '/'
        and instr(staging_path, char(92)) = 0)),
    package_identity      text check (package_identity is null or length(package_identity) between 1 and 255),
    entry_count           integer check (entry_count is null or entry_count >= 0),
    total_bytes           integer check (total_bytes is null or total_bytes >= 0),
    validation_evidence   text check (validation_evidence is null or length(validation_evidence) <= 16384),
    failure_detail        text check (failure_detail is null or length(failure_detail) <= 4096),
    created_at            text not null,
    updated_at            text not null,
    unique (operation_type, operation_key)
);

create index if not exists idx_skill_package_operations_state
    on skill_package_operations (operation_state, created_at);

create table if not exists skill_resource_executions (
    id                    integer primary key autoincrement,
    snapshot_id           integer not null,
    snapshot_resource_id  integer not null,
    task_run_id           text not null check (length(task_run_id) between 1 and 255
        and instr(task_run_id, '/') = 0 and instr(task_run_id, char(92)) = 0),
    agent_id              text not null check (length(agent_id) between 1 and 255
        and instr(agent_id, '/') = 0 and instr(agent_id, char(92)) = 0),
    writer_owner_id       text check (writer_owner_id is null or length(writer_owner_id) between 1 and 255),
    fence_generation      integer check (fence_generation is null or fence_generation > 0),
    attempt_number        integer check (attempt_number is null or attempt_number > 0),
    launch_state          text not null check (launch_state in (
        'PREFLIGHT', 'LAUNCHED', 'COMPLETED', 'FAILED', 'REJECTED')),
    process_policy        text check (process_policy is null or length(process_policy) <= 16384),
    args_json             text check (args_json is null or length(args_json) <= 8192),
    env_json              text check (env_json is null or length(env_json) <= 8192),
    start_result_state    text check (start_result_state is null or length(start_result_state) <= 1024),
    exit_code             integer,
    exit_facts_json       text check (exit_facts_json is null or length(exit_facts_json) <= 8192),
    failure_detail        text check (failure_detail is null or length(failure_detail) <= 4096),
    started_at            text,
    completed_at          text,
    created_at            text not null,
    foreign key (snapshot_id) references skill_snapshots(id),
    foreign key (snapshot_resource_id) references skill_snapshot_resources(id)
);

create index if not exists idx_skill_resource_executions_task_run
    on skill_resource_executions (task_run_id, created_at desc);
create index if not exists idx_skill_resource_executions_resource
    on skill_resource_executions (snapshot_resource_id, created_at desc);

-- ════════════════════════════════════════════════════════════════
--  Runtime Summaries and Measurements (migration 008 equivalent)
-- ════════════════════════════════════════════════════════════════

create table if not exists run_summaries (
    task_run_id          text primary key,
    normalized_status    text not null check (normalized_status in (
        'SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT',
        'INTERRUPTED', 'PARTIAL', 'ABANDONED', 'UNAVAILABLE',
        'ACTIVE')),
    attempt_count        integer not null check (attempt_count >= 0),
    model_call_count     integer not null check (model_call_count >= 0),
    tool_call_count      integer not null check (tool_call_count >= 0),
    wall_elapsed_ms      integer,
    active_elapsed_ms    integer,
    completeness         text not null check (completeness in (
        'COMPLETE', 'PARTIAL', 'UNAVAILABLE')),
    workspace_type       text not null check (workspace_type in ('agent', 'user')),
    workspace_key        text not null check (length(workspace_key) > 0
        and workspace_key not in ('.', '..')
        and instr(workspace_key, '/') = 0 and instr(workspace_key, char(92)) = 0),
    source_kind          text not null check (source_kind in ('task', 'workflow', 'job')),
    lineage_parent_task_run_id text,
    lineage_mode         text check (lineage_mode in ('RETRY_OF', 'REPLAY_OF')),
    created_at           text not null,
    updated_at           text not null,
    foreign key (task_run_id) references task_run_locations(task_run_id) on delete cascade
);

create index if not exists idx_run_summaries_workspace
    on run_summaries (workspace_type, workspace_key, normalized_status, updated_at desc);
create index if not exists idx_run_summaries_task_run
    on run_summaries (task_run_id);

create table if not exists run_measurements (
    invocation_id        text primary key check (
        length(invocation_id) between 1 and 255
        and instr(invocation_id, '/') = 0 and instr(invocation_id, char(92)) = 0),
    task_run_id          text not null,
    attempt_number       integer not null check (attempt_number > 0),
    kind                 text not null check (kind in ('MODEL', 'TOOL')),
    call_id              text not null check (length(call_id) between 1 and 255),
    correlation_id       text check (correlation_id is null or length(correlation_id) between 1 and 255),
    provider             text,
    model_name           text,
    tool_name            text,
    outcome              text not null check (outcome in (
        'SUCCEEDED', 'FAILED', 'TIMED_OUT', 'REJECTED',
        'INTERRUPTED', 'TRUNCATED', 'DISABLED', 'UNAVAILABLE')),
    started_at           text,
    completed_at         text,
    elapsed_ms           integer,
    input_token_count    integer,
    output_token_count   integer,
    total_token_count    integer,
    timeout_ms           integer,
    exit_code            integer,
    truncation_kind      text,
    interruption_kind    text,
    rejection_reason     text,
    failure_detail       text,
    completeness         text not null check (completeness in (
        'COMPLETE', 'INCOMPLETE', 'UNAVAILABLE')),
    created_at           text not null,
    updated_at           text not null,
    foreign key (task_run_id) references task_run_locations(task_run_id) on delete cascade,
    foreign key (task_run_id, attempt_number)
        references task_run_attempts(task_run_id, attempt_number) on delete cascade
);

create index if not exists idx_run_measurements_task_run
    on run_measurements (task_run_id, attempt_number, kind, created_at desc);
create index if not exists idx_run_measurements_call_id
    on run_measurements (task_run_id, call_id);
create index if not exists idx_run_measurements_correlation
    on run_measurements (correlation_id) where correlation_id is not null;

-- ── Schedule and reaction management (migration 008) ──
drop table if exists schedule_firings;
drop table if exists agent_schedules;
drop table if exists agent_event_reactions;

create table if not exists schedule_definitions (
    id                text primary key check (length(id) between 1 and 128),
    agent_id          text not null check (length(agent_id) > 0
        and instr(agent_id, '/') = 0 and instr(agent_id, char(92)) = 0),
    revision          integer not null check (revision >= 1),
    cron_expression   text not null check (length(cron_expression) between 1 and 256),
    timezone          text not null check (length(timezone) between 1 and 128),
    dialect_version   text not null check (length(dialect_version) between 1 and 32),
    policy_version    text not null check (length(policy_version) between 1 and 32),
    enabled           integer not null check (enabled in (0, 1)),
    deleted           integer not null default 0 check (deleted in (0, 1)),
    job_id            text,
    assignment_template_json text not null,
    created_at        text not null,
    updated_at        text not null,
    unique (id, revision)
);
create index if not exists idx_schedule_defs_agent on schedule_definitions (agent_id, deleted, enabled);
create index if not exists idx_schedule_defs_enabled on schedule_definitions (enabled, deleted);

create table if not exists scheduler_claims (
    id                text primary key check (length(id) between 1 and 128),
    owner_id          text not null check (length(owner_id) > 0),
    fence             integer not null check (fence >= 0),
    heartbeat_at      text not null,
    expires_at        text not null,
    claimed_at        text not null,
    created_at        text not null,
    updated_at        text not null
);

create table if not exists schedule_firings (
    firing_id         text primary key check (length(firing_id) between 1 and 128),
    schedule_id       text not null,
    revision          integer not null check (revision >= 1),
    slot_instant      text not null,
    timezone          text not null,
    offset_policy     text not null check (offset_policy in ('GAP_SKIP', 'OVERLAP_FIRE_ONCE')),
    firing_state      text not null check (firing_state in (
        'CLAIMED', 'ENQUEUED', 'EXHAUSTED', 'RECOVERED', 'CONFLICT')),
    operation_key     text not null check (length(operation_key) between 1 and 255),
    request_digest    text not null check (length(request_digest) = 64),
    template_snapshot_json text not null,
    assignment_id     text,
    task_run_id       text,
    native_run_id     text,
    correlation_id    text,
    error_class       text,
    created_at        text not null,
    updated_at        text not null,
    foreign key (schedule_id, revision) references schedule_definitions(id, revision),
    unique (schedule_id, revision, slot_instant, timezone)
);
create index if not exists idx_schedule_firings_schedule on schedule_firings (schedule_id, revision, firing_state);
create index if not exists idx_schedule_firings_op_key on schedule_firings (operation_key, request_digest);
create index if not exists idx_schedule_firings_assignment on schedule_firings (assignment_id);

create table if not exists schedule_firing_attempts (
    attempt_id        text primary key check (length(attempt_id) between 1 and 128),
    firing_id         text not null,
    attempt_number    integer not null check (attempt_number between 1 and 3),
    eligible_at       text not null,
    attempted_at      text,
    claim_owner       text not null,
    claim_fence       integer not null check (claim_fence >= 0),
    outcome           text not null check (outcome in (
        'ENQUEUED', 'FAILED', 'RECOVERED', 'DUPLICATE')),
    error_class       text,
    assignment_id     text,
    task_run_id       text,
    native_run_id     text,
    correlation_id    text,
    created_at        text not null,
    foreign key (firing_id) references schedule_firings(firing_id)
);
create index if not exists idx_schedule_firing_attempts_firing on schedule_firing_attempts (firing_id, attempt_number);
create index if not exists idx_schedule_firing_attempts_assignment on schedule_firing_attempts (assignment_id);

create table if not exists reaction_definitions (
    id                text primary key check (length(id) between 1 and 128),
    agent_id          text not null check (length(agent_id) > 0
        and instr(agent_id, '/') = 0 and instr(agent_id, char(92)) = 0),
    revision          integer not null check (revision >= 1),
    event_type        text not null check (length(event_type) between 1 and 64),
    filter_json       text,
    action_type       text not null check (action_type in ('ENQUEUE_ASSIGNMENT')),
    assignment_template_json text not null,
    enabled           integer not null check (enabled in (0, 1)),
    deleted           integer not null default 0 check (deleted in (0, 1)),
    created_at        text not null,
    updated_at        text not null,
    unique (id, revision)
);
create index if not exists idx_reaction_defs_agent on reaction_definitions (agent_id, deleted, enabled);
create index if not exists idx_reaction_defs_enabled_type on reaction_definitions (enabled, event_type, deleted);

create table if not exists reaction_dispatches (
    dispatch_id       text primary key check (length(dispatch_id) between 1 and 128),
    reaction_id       text not null,
    revision          integer not null check (revision >= 1),
    source_event_id   text not null,
    operation_key     text not null check (length(operation_key) between 1 and 255),
    request_digest    text not null check (length(request_digest) = 64),
    template_snapshot_json text not null,
    root_event_id     text not null,
    parent_event_id   text,
    parent_dispatch_id text,
    depth             integer not null check (depth >= 1 and depth <= 8),
    dedupe_key        text not null,
    dispatch_state    text not null check (dispatch_state in (
        'ENQUEUED', 'SUPPRESSED_LOOP', 'SUPPRESSED_RATE', 'SUPPRESSED_DEPTH', 'CONFLICT')),
    suppression_reason text,
    assignment_id     text,
    task_run_id       text,
    native_run_id     text,
    correlation_id    text,
    created_at        text not null,
    updated_at        text not null,
    foreign key (reaction_id, revision) references reaction_definitions(id, revision),
    unique (reaction_id, revision, source_event_id)
);
create index if not exists idx_reaction_dispatches_reaction on reaction_dispatches (reaction_id, revision, dispatch_state);
create index if not exists idx_reaction_dispatches_root_event on reaction_dispatches (root_event_id);
create index if not exists idx_reaction_dispatches_op_key on reaction_dispatches (operation_key, request_digest);
create index if not exists idx_reaction_dispatches_dedupe on reaction_dispatches (dedupe_key, created_at);
create index if not exists idx_reaction_dispatches_assignment on reaction_dispatches (assignment_id);

create table if not exists reaction_dispatch_attempts (
    attempt_id        text primary key check (length(attempt_id) between 1 and 128),
    dispatch_id       text not null,
    attempt_number    integer not null check (attempt_number between 1 and 3),
    eligible_at       text not null,
    attempted_at      text,
    claim_owner       text not null,
    claim_fence       integer not null check (claim_fence >= 0),
    outcome           text not null check (outcome in (
        'ENQUEUED', 'FAILED', 'RECOVERED', 'DUPLICATE')),
    error_class       text,
    assignment_id     text,
    task_run_id       text,
    native_run_id     text,
    correlation_id    text,
    created_at        text not null,
    foreign key (dispatch_id) references reaction_dispatches(dispatch_id)
);
create index if not exists idx_reaction_dispatch_attempts_dispatch on reaction_dispatch_attempts (dispatch_id, attempt_number);
create index if not exists idx_reaction_dispatch_attempts_assignment on reaction_dispatch_attempts (assignment_id);

-- ════════════════════════════════════════════════════════════════
--  Agent Materialization and Context (migration 010 equivalent)
-- ════════════════════════════════════════════════════════════════

create table if not exists agent_materializations (
    agent_id              text not null check (
        length(agent_id) > 0
        and instr(agent_id, '/') = 0 and instr(agent_id, char(92)) = 0),
    materialization_key   text not null check (
        length(materialization_key) = 75
        and substr(materialization_key, 1, 11) = 'v1-PROJECT-'
        and substr(materialization_key, 12) not glob '*[^0-9a-f]*'),
    materialization_kind  text not null check (materialization_kind = 'PROJECT'),
    source_kind           text not null check (source_kind = 'project'),
    source_id             text not null check (length(source_id) > 0),
    generation            integer not null check (generation >= 1),
    state                 text not null check (
        state in ('PROVISIONING', 'READY', 'FAILED', 'REVOKED')),
    root_relative_path    text check (root_relative_path is null or (
        length(root_relative_path) > 0
        and substr(root_relative_path, 1, 1) <> '/'
        and instr(root_relative_path, char(92)) = 0
        and instr(root_relative_path, '..') = 0
        and instr(root_relative_path, char(10)) = 0)),
    failure_detail        text check (failure_detail is null or length(failure_detail) <= 4096),
    created_at            text not null,
    updated_at            text not null,
    unique (agent_id, materialization_key),
    check (root_relative_path is not null or state in ('PROVISIONING', 'FAILED', 'REVOKED'))
);

create index if not exists idx_agent_materializations_agent
    on agent_materializations (agent_id, state, updated_at desc);
create index if not exists idx_agent_materializations_key
    on agent_materializations (materialization_key);

create table if not exists context_defaults (
    agent_id                  text primary key check (
        length(agent_id) > 0
        and instr(agent_id, '/') = 0 and instr(agent_id, char(92)) = 0),
    version                   integer not null check (version >= 1),
    selection_kind            text not null check (
        selection_kind in ('MATERIALIZATION', 'PERSONAL')),
    materialization_key       text check (materialization_key is null or (
        length(materialization_key) = 75
        and substr(materialization_key, 1, 11) = 'v1-PROJECT-'
        and substr(materialization_key, 12) not glob '*[^0-9a-f]*')),
    materialization_generation integer check (
        materialization_generation is null or materialization_generation >= 1),
    personal_selection        text check (
        personal_selection is null or personal_selection in ('HOME', 'SCRIPTS', 'APPS')),
    reason                    text check (reason is null or length(reason) <= 4096),
    created_at                text not null,
    updated_at                text not null,
    check ((selection_kind = 'MATERIALIZATION'
            and materialization_key is not null
            and materialization_generation is not null
            and personal_selection is null)
        or (selection_kind = 'PERSONAL'
            and materialization_key is null
            and materialization_generation is null
            and personal_selection is not null))
);

create table if not exists context_bindings (
    id                        integer primary key autoincrement,
    agent_id                  text not null check (
        length(agent_id) > 0
        and instr(agent_id, '/') = 0 and instr(agent_id, char(92)) = 0),
    binding_key               text not null check (length(binding_key) between 1 and 255),
    selection_kind            text not null check (
        selection_kind in ('MATERIALIZATION', 'PERSONAL')),
    materialization_key       text check (materialization_key is null or (
        length(materialization_key) = 75
        and substr(materialization_key, 1, 11) = 'v1-PROJECT-'
        and substr(materialization_key, 12) not glob '*[^0-9a-f]*')),
    materialization_generation integer check (
        materialization_generation is null or materialization_generation >= 1),
    personal_selection        text check (
        personal_selection is null or personal_selection in ('HOME', 'SCRIPTS', 'APPS')),
    captured_at               text not null,
    unique (binding_key),
    check ((selection_kind = 'MATERIALIZATION'
            and materialization_key is not null
            and materialization_generation is not null
            and personal_selection is null)
        or (selection_kind = 'PERSONAL'
            and materialization_key is null
            and materialization_generation is null
            and personal_selection is not null))
);

create index if not exists idx_context_bindings_agent
    on context_bindings (agent_id, captured_at desc);

create table if not exists binding_owner_links (
    owner_type   text not null check (
        length(owner_type) between 1 and 64
        and instr(owner_type, '/') = 0 and instr(owner_type, char(92)) = 0),
    owner_id     text not null check (
        length(owner_id) between 1 and 255
        and instr(owner_id, '/') = 0 and instr(owner_id, char(92)) = 0),
    binding_key  text not null,
    created_at   text not null,
    unique (owner_type, owner_id),
    foreign key (binding_key) references context_bindings(binding_key)
);

create index if not exists idx_binding_owner_links_binding
    on binding_owner_links (binding_key);

create table if not exists materialization_operation_results (
    operation_type       text not null check (
        operation_type in ('REGISTER', 'PROVISION', 'REVOKE', 'FAIL')),
    operation_key        text not null check (length(operation_key) between 1 and 255),
    request_digest       text not null check (
        length(request_digest) = 64 and request_digest not glob '*[^0-9a-f]*'),
    result_state         text not null check (
        result_state in ('COMPLETED', 'CONFLICT')),
    materialization_key  text,
    result_json          text check (result_json is null or length(result_json) <= 4096),
    created_at           text not null,
    primary key (operation_type, operation_key)
);

-- Migration 011 equivalent: durable idempotency for immutable context capture
-- and owner-link requests. Materialization-key columns above use the final
-- strict serialized v1-PROJECT-SHA-256 form.
create table if not exists operation_idempotency (
    operation_type  text not null check (
        operation_type in ('BINDING_CAPTURE', 'OWNER_LINK')),
    operation_key   text not null check (length(operation_key) between 1 and 255),
    request_digest  text not null check (
        length(request_digest) = 64 and request_digest not glob '*[^0-9a-f]*'),
    result_state    text not null check (result_state = 'COMPLETED'),
    binding_key     text not null,
    owner_type      text,
    owner_id        text,
    created_at      text not null,
    primary key (operation_type, operation_key),
    check ((operation_type = 'BINDING_CAPTURE' and owner_type is null and owner_id is null)
        or (operation_type = 'OWNER_LINK' and owner_type is not null and owner_id is not null))
);

-- Migration 012 equivalent: the submission operation is reserved before a
-- binding is captured, so a racing retry cannot leave an orphan binding.
create table if not exists assignment_binding_operations (
    operation_key   text primary key check (length(operation_key) between 1 and 255),
    request_digest  text not null check (
        length(request_digest) = 64 and request_digest not glob '*[^0-9a-f]*'),
    operation_state text not null check (operation_state in ('RESERVED', 'COMPLETED')),
    binding_key     text,
    assignment_id   text,
    task_run_id     text,
    created_at      text not null,
    updated_at      text not null,
    check ((operation_state = 'RESERVED'
            and binding_key is null and assignment_id is null and task_run_id is null)
        or (operation_state = 'COMPLETED'
            and binding_key is not null and assignment_id is not null))
);

create index if not exists idx_assignment_binding_operations_assignment
    on assignment_binding_operations (assignment_id)
    where assignment_id is not null;

-- ════════════════════════════════════════════════════════════════
--  System Chat Action Proposals (migration 013 equivalent)
-- ════════════════════════════════════════════════════════════════

create table if not exists system_chat_action_proposals (
    id                        text primary key check (length(id) between 1 and 128),
    conversation_id           text not null check (length(conversation_id) between 1 and 255),
    agent_id                  text not null check (
        length(agent_id) > 0
        and instr(agent_id, '/') = 0 and instr(agent_id, char(92)) = 0),
    target_kind               text not null check (target_kind in ('ASSIGNMENT')),
    target_id                 text not null check (length(target_id) between 1 and 255),
    action                    text not null check (action in (
        'PAUSE', 'RESUME', 'RETRY', 'REPLAY', 'CANCEL')),
    expected_state_or_revision text,
    canonical_payload_digest  text not null check (
        length(canonical_payload_digest) = 64
        and canonical_payload_digest not glob '*[^0-9a-f]*'),
    operation_key             text not null check (length(operation_key) between 1 and 255),
    proposal_state            text not null check (proposal_state in (
        'PENDING', 'CONFIRMING', 'CONFIRMED', 'DENIED', 'CANCELLED', 'STALE', 'UNAVAILABLE')),
    confirmation_operation_key text,
    linked_operation_id       text,
    confirmed_at              text,
    audit_facts_json          text,
    created_at                text not null,
    updated_at                text not null,
    unique (operation_key),
    unique (target_kind, target_id, action, canonical_payload_digest)
);

create index if not exists idx_system_chat_proposals_conversation
    on system_chat_action_proposals (conversation_id, created_at desc);
create index if not exists idx_system_chat_proposals_agent
    on system_chat_action_proposals (agent_id, proposal_state, created_at desc);
