-- Migration 018: project materialization templates and immutable generation rows.
--
-- project_materialization_templates: append-only template revisions per project.
-- Each edit creates a new revision with a snapshot of normalized relative
-- directory paths. Revisions are monotonic and immutable.
--
-- agent_materialization_generations: immutable generation rows that are the
-- authoritative source for each provisioned materialization instance.
-- Identity is (agent_id, project_key, generation). Logical heads are
-- selected by these three fields. Lifecycle CAS touches only lifecycle_state,
-- lifecycle_version, root_location, and failure_detail; all other columns
-- are immutable after insert. GENERATION_V2 roots live outside legacy
-- projects/ subtrees.

create table if not exists project_materialization_templates (
    id                    text primary key check (
        length(id) between 1 and 255
        and instr(id, '/') = 0 and instr(id, char(92)) = 0),
    project_id            text not null check (
        length(project_id) between 1 and 255
        and instr(project_id, '/') = 0 and instr(project_id, char(92)) = 0),
    revision              integer not null check (revision >= 1),
    directories_snapshot  text not null check (length(directories_snapshot) > 0),
    created_at            text not null,
    unique (project_id, revision),
    foreign key (project_id) references projects(id) on delete cascade
);

create index if not exists idx_project_materialization_templates_project
    on project_materialization_templates (project_id, revision desc);

create table if not exists agent_materialization_generations (
    agent_id                     text not null check (
        length(agent_id) > 0
        and instr(agent_id, '/') = 0 and instr(agent_id, char(92)) = 0),
    project_key                  text not null check (
        length(project_key) = 75
        and substr(project_key, 1, 11) = 'v1-PROJECT-'
        and substr(project_key, 12) not glob '*[^0-9a-f]*'),
    generation                   integer not null check (generation >= 1),
    root_kind                    text not null check (
        root_kind in ('GENERATION_V2', 'LEGACY_V1')),
    root_location                text check (root_location is null or (
        length(root_location) > 0
        and substr(root_location, 1, 1) <> '/'
        and instr(root_location, char(92)) = 0
        and instr(root_location, '..') = 0
        and instr(root_location, char(10)) = 0)),
    lifecycle_state              text not null check (
        lifecycle_state in ('PROVISIONING', 'READY', 'FAILED', 'REVOKED')),
    lifecycle_version            integer not null check (lifecycle_version >= 1),
    template_revision            integer check (
        template_revision is null or template_revision >= 1),
    template_directories_snapshot text not null check (
        length(template_directories_snapshot) > 0),
    failure_detail               text check (
        failure_detail is null or length(failure_detail) <= 4096),
    created_at                   text not null,
    updated_at                   text not null,
    primary key (agent_id, project_key, generation),
    check (root_location is not null or lifecycle_state in ('PROVISIONING', 'FAILED', 'REVOKED')),
    check ((root_kind = 'LEGACY_V1' and root_location is not null)
        or root_kind = 'GENERATION_V2')
);

create index if not exists idx_agent_materialization_generations_agent
    on agent_materialization_generations (agent_id, updated_at desc);

create index if not exists idx_agent_materialization_generations_head
    on agent_materialization_generations (agent_id, project_key, generation desc);
