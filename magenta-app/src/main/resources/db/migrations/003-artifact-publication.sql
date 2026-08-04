-- Migration 003: Run-isolated artifact publication, integrity, and reconciliation.
-- Preserves existing run_output_artifacts rows as LEGACY with null task attribution.

-- ── Publication table ──
create table output_publications (
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

    -- Task-run linkage (null for CHAT and LEGACY)
    task_run_id          text,
    task_path_key        text,
    native_run_id        text,
    attempt_number       integer check (attempt_number is null or attempt_number > 0),

    -- Root-relative paths (never absolute, never workspace-identity-derived)
    root_relative_path   text not null
        check (length(root_relative_path) > 0
            and substr(root_relative_path, 1, 1) <> '/'
            and instr(root_relative_path, '\\') = 0
            and instr(root_relative_path, char(10)) = 0),
    staging_path         text
        check (staging_path is null or (length(staging_path) > 0
            and substr(staging_path, 1, 1) <> '/'
            and instr(staging_path, '\\') = 0)),

    -- Idempotency
    operation_key        text not null check (length(operation_key) > 0),
    request_digest       text not null check (
        length(request_digest) = 64 and request_digest not glob '*[^0-9a-f]*'),
    content_sha256       text not null check (
        length(content_sha256) = 64 and content_sha256 not glob '*[^0-9a-f]*'),

    -- Publication state
    publication_state    text not null check (publication_state in (
        'RESERVED', 'STAGED', 'PUBLISHED', 'FAILED', 'INTEGRITY_FAILED')),
    reconciliation_state text check (reconciliation_state is null or reconciliation_state in (
        'RECONCILED', 'UNRESOLVED', 'CLEANUP_PENDING', 'DELETING')),
    failure_detail       text,

    -- Integrity facts
    expected_sha256      text not null check (
        length(expected_sha256) = 64 and expected_sha256 not glob '*[^0-9a-f]*'),
    verified_sha256      text check (
        verified_sha256 is null or (length(verified_sha256) = 64
            and verified_sha256 not glob '*[^0-9a-f]*')),
    verified_size        integer check (verified_size is null or verified_size >= 0),
    media_type           text check (media_type is null or length(media_type) > 0),

    -- Writer fence for guarded task mutations
    writer_owner_id      text,
    fence_generation     integer check (fence_generation is null or fence_generation > 0),

    -- Logical metadata
    logical_name         text not null check (length(logical_name) > 0),
    file_extension       text,

    -- Timestamps
    created_at           text not null,
    updated_at           text not null,
    staged_at            text,
    published_at         text,

    unique (publication_origin, operation_key),
    unique (workspace_kind, workspace_key, root_relative_path)
);

-- Indexes for querying by workspace, task-run, and state
create index idx_output_publications_workspace
    on output_publications (workspace_identity, created_at desc);
create index idx_output_publications_task_run
    on output_publications (task_run_id, created_at desc)
    where task_run_id is not null;
create index idx_output_publications_state
    on output_publications (publication_state, workspace_identity);
create index idx_output_publications_reconciliation
    on output_publications (reconciliation_state, updated_at)
    where reconciliation_state is not null;

-- ── Migrate legacy artifact rows ──
-- Preserve existing bytes and rows as LEGACY with honest unavailable facts.
-- compute_sha256 is a function injected via this same migration statement.

insert into output_publications (
    artifact_id, publication_origin, workspace_kind, workspace_key,
    workspace_identity, task_run_id, task_path_key, native_run_id,
    root_relative_path, staging_path,
    operation_key, request_digest, content_sha256,
    publication_state, reconciliation_state, failure_detail,
    expected_sha256, verified_sha256, verified_size, media_type,
    writer_owner_id, fence_generation,
    logical_name, file_extension,
    created_at, updated_at, staged_at, published_at
)
select
    r.id,
    'LEGACY',
    case when r.workspace_type in ('agent', 'chat', 'user') then r.workspace_type else 'agent' end,
    case when length(coalesce(r.workspace_key, '')) > 0
              and r.workspace_key not in ('.', '..')
              and instr(r.workspace_key, '/') = 0
              and instr(r.workspace_key, char(92)) = 0
         then r.workspace_key else 'legacy-' || r.id end,
    (case when r.workspace_type in ('agent', 'chat', 'user') then r.workspace_type else 'agent' end)
        || '/' ||
    (case when length(coalesce(r.workspace_key, '')) > 0
              and r.workspace_key not in ('.', '..')
              and instr(r.workspace_key, '/') = 0
              and instr(r.workspace_key, char(92)) = 0
         then r.workspace_key else 'legacy-' || r.id end),
    null,
    null,
    null,
    case when substr(r.file_path, 1, 1) = '/'
              or r.file_path glob '[A-Za-z]:*'
              or instr(r.file_path, char(92)) > 0
         then 'legacy/unavailable/' || r.id
         else r.file_path end,
    null,
    'legacy-' || r.id,
    '0000000000000000000000000000000000000000000000000000000000000000',
    '0000000000000000000000000000000000000000000000000000000000000000',
    'PUBLISHED',
    null,
    null,
    '0000000000000000000000000000000000000000000000000000000000000000',
    null,
    null,
    null,
    null,
    null,
    coalesce(nullif(r.file_name, ''), 'legacy-' || r.id),
    case when instr(r.file_name, '.') > 0
         then substr(r.file_name, instr(r.file_name, '.'))
         else null end,
    r.created_at,
    r.created_at,
    r.created_at,
    r.created_at
from run_output_artifacts r
where true
on conflict (artifact_id) do nothing;
