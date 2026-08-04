-- Migration 004: normalized scoped skill discovery and exact-version assignment.
-- Immutable candidate versions, observations, exact-version assignment overlays,
-- and legacy conversion diagnostics.
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
