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
