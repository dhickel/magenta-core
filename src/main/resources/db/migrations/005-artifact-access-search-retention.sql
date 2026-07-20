-- Migration 005: authorized artifact access, extraction documents, lineage,
-- retention policies, pins, and bounded active-download registration.

-- Extraction documents for FTS5-backed authorized search.
-- content table holds the raw artifact attribution; the FTS5 virtual table
-- indexes digest-keyed extraction text.
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

-- FTS5 virtual table for full-text search over extracted documents.
-- Content is an external-content table referencing artifact_extraction_documents.
create virtual table if not exists artifact_extraction_fts using fts5 (
    extracted_text,
    language_hint,
    content='artifact_extraction_documents',
    content_rowid='id',
    tokenize='porter unicode61'
);

-- FTS5 synchronization is managed programmatically in ArtifactSearchService.
-- Triggers are omitted to avoid Spring ScriptUtils semicolon splitting issues.

-- Producer-declared lineage edges between artifacts.
-- Only producer-declared edges are recorded; cycles and duplicates are rejected.
create table if not exists artifact_lineage_edges (
    id                      integer primary key autoincrement,
    parent_artifact_id      text not null
        check (length(parent_artifact_id) between 1 and 255
            and parent_artifact_id <> child_artifact_id),
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

-- Retention policies scoped to workspace/task combinations.
-- Default policy is preserve-by-default. A null task_path_key means
-- workspace-default policy.
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

-- Retention pins that prevent deletion of specific artifacts or task runs.
-- A pin targeting a task_run_id blocks all artifacts owned by that run.
-- A pin targeting an artifact_id blocks a single artifact.
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

-- Active download/read sessions. Each session is bounded and tracked
-- so retention cannot evict an actively-downloaded artifact.
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
