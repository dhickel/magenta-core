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
    status text not null,
    input_values_json text not null,
    output_values_json text not null,
    plan_snapshot_json text not null,
    workspace_id text,
    output_directory text,
    temp_workspace_path text,
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
    status text not null,
    current_node_index integer not null default 0,
    node_runs_json text not null,
    workspace_path text,
    output_dir text,
    agent_id text,
    job_id text,
    job_assignment_id text,
    job_run_id text,
    project_id text,
    workspace_id text,
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
    workspace_id text,
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
    workspace_id text,
    project_id text,
    effective_workspace_id text,
    effective_workspace_kind text,
    selected_work_area_id text,
    output_route_type text,
    output_work_area_id text,
    output_direct_relative_path text,
    current_item_index integer not null,
    checkpoint_json text,
    input_json text,
    output_json text,
    evidence_json text,
    error_text text,
    lease_owner text,
    lease_expires_at text,
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

create table if not exists workspaces (
    id text primary key,
    owner_type text not null,
    owner_id text not null,
    root_relative_path text not null,
    display_name text not null,
    metadata_json text,
    created_at text not null,
    updated_at text not null
);

create unique index if not exists idx_workspaces_owner
    on workspaces(owner_type, owner_id);

create table if not exists workspace_links (
    id text primary key,
    workspace_id text not null,
    label text not null,
    link_type text not null,
    target text not null,
    readable integer not null,
    writable integer not null,
    created_at text not null,
    updated_at text not null,
    foreign key(workspace_id) references workspaces(id)
);

create table if not exists work_areas (
    id text primary key,
    owner_type text not null,
    owner_id text not null,
    workspace_id text,
    root_relative_path text not null,
    area_relative_path text not null,
    display_name text not null,
    system_flag integer not null default 0,
    home_flag integer not null default 0,
    active_flag integer not null default 1,
    metadata_json text not null default '{}',
    created_at text not null,
    updated_at text not null,
    unique(owner_type, owner_id, area_relative_path),
    foreign key(workspace_id) references workspaces(id)
);

create index if not exists idx_work_areas_owner
    on work_areas(owner_type, owner_id, active_flag);

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

-- Exclusive writable leases on job/project workspaces. Only one active
-- writable lease per workspace at a time. Extension must verify holder
-- ownership.
create table if not exists workspace_leases (
    id text primary key,
    workspace_id text not null,
    holder_type text not null,
    holder_id text not null,
    mode text not null,
    expires_at text,
    release_requested integer not null default 0,
    released_at text,
    created_at text not null,
    updated_at text not null,
    foreign key(workspace_id) references workspaces(id)
);

-- At most one active WRITE lease per workspace. Enforced by the database,
-- not by application-level check-then-insert logic.
create unique index if not exists idx_workspace_leases_active_write
    on workspace_leases(workspace_id)
    where mode = 'WRITE' and released_at is null;

create index if not exists idx_workspace_leases_active_holder
    on workspace_leases(holder_type, holder_id)
    where released_at is null;

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
    workspace_id text,
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

create index if not exists idx_run_output_artifacts_workspace
    on run_output_artifacts(workspace_id);

-- ════════════════════════════════════════════════════════════════
--  Phase 04: Jobs, Projects, Agent Networks
-- ════════════════════════════════════════════════════════════════

-- Job definitions: coordinate multiple plan or workflow work items.
-- items_json is a JSON array of JobWorkItem objects.
create table if not exists job_definitions (
    id text primary key,
    owner_agent_id text,
    project_id text,
    workspace_id text,
    persistent_workspace_enabled integer not null default 0,
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
    workspace_id text,
    status text not null,
    work_item_runs_json text not null,
    workspace_path text,
    output_dir text,
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
