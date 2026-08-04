-- Migration 020: immutable project-chat metadata.
-- Columns are installed conditionally by SchemaMigrationRunner before this
-- script because SQLite lacks ADD COLUMN IF NOT EXISTS.

create index if not exists idx_chat_session_project_origin
    on ai_chat_session_metadata (project_id, origin);

create unique index if not exists idx_chat_session_project_tuple
    on ai_chat_session_metadata (project_id, member_agent_id, materialization_key, materialization_generation)
    where origin = 'PROJECT_CHAT';
