-- Fresh Magenta Core schema. Historical tables are intentionally not referenced.
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS magenta_core_schema_identity (
    schema_id TEXT PRIMARY KEY,
    schema_version INTEGER NOT NULL CHECK (schema_version > 0),
    schema_hash TEXT NOT NULL,
    initialized_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE TABLE IF NOT EXISTS magenta_core_schema_version (
    version INTEGER PRIMARY KEY CHECK (version > 0),
    applied_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    description TEXT
);

CREATE TABLE IF NOT EXISTS magenta_core_session_metadata (
    session_id TEXT PRIMARY KEY,
    title TEXT,
    model TEXT,
    favorite INTEGER NOT NULL DEFAULT 0 CHECK (favorite IN (0, 1)),
    archived INTEGER NOT NULL DEFAULT 0 CHECK (archived IN (0, 1)),
    origin TEXT NOT NULL DEFAULT 'magenta-core',
    metadata_json TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);
CREATE INDEX IF NOT EXISTS idx_magenta_core_session_origin
    ON magenta_core_session_metadata(origin);
CREATE INDEX IF NOT EXISTS idx_magenta_core_session_favorite
    ON magenta_core_session_metadata(favorite);
CREATE INDEX IF NOT EXISTS idx_magenta_core_session_archived
    ON magenta_core_session_metadata(archived);

CREATE TABLE IF NOT EXISTS magenta_core_conversation_memory (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id TEXT NOT NULL
        REFERENCES magenta_core_session_metadata(session_id) ON DELETE CASCADE,
    role TEXT NOT NULL CHECK (role IN ('system', 'user', 'assistant', 'tool')),
    content TEXT NOT NULL,
    tool_name TEXT,
    tool_call_id TEXT,
    metadata_json TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);
CREATE INDEX IF NOT EXISTS idx_magenta_core_memory_session
    ON magenta_core_conversation_memory(session_id, id);
CREATE INDEX IF NOT EXISTS idx_magenta_core_memory_session_role
    ON magenta_core_conversation_memory(session_id, role);

CREATE TABLE IF NOT EXISTS magenta_core_pending_messages (
    message_id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL
        REFERENCES magenta_core_session_metadata(session_id) ON DELETE CASCADE,
    message_text TEXT NOT NULL,
    metadata_json TEXT,
    claim_token TEXT,
    claimed_at TEXT,
    enqueued_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);
CREATE INDEX IF NOT EXISTS idx_magenta_core_pending_session
    ON magenta_core_pending_messages(session_id, enqueued_at);
CREATE UNIQUE INDEX IF NOT EXISTS idx_magenta_core_pending_claim_token
    ON magenta_core_pending_messages(claim_token)
    WHERE claim_token IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_magenta_core_pending_stale
    ON magenta_core_pending_messages(claimed_at)
    WHERE claim_token IS NOT NULL;

CREATE TABLE IF NOT EXISTS magenta_core_pending_interactions (
    session_id TEXT PRIMARY KEY
        REFERENCES magenta_core_session_metadata(session_id) ON DELETE CASCADE,
    turn_id TEXT NOT NULL,
    model TEXT,
    continuation_json TEXT NOT NULL DEFAULT '{}',
    state TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (state IN ('PENDING', 'ANSWERED', 'CANCELLED')),
    answer_text TEXT,
    cancellation_reason TEXT,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    CHECK (
        (state = 'PENDING' AND answer_text IS NULL AND cancellation_reason IS NULL)
        OR (state = 'ANSWERED' AND answer_text IS NOT NULL AND cancellation_reason IS NULL)
        OR (state = 'CANCELLED' AND answer_text IS NULL AND cancellation_reason IS NOT NULL)
    )
);
CREATE INDEX IF NOT EXISTS idx_magenta_core_pending_interaction_state
    ON magenta_core_pending_interactions(state, updated_at);
CREATE INDEX IF NOT EXISTS idx_magenta_core_pending_interaction_turn
    ON magenta_core_pending_interactions(turn_id);

CREATE TABLE IF NOT EXISTS magenta_core_pending_interaction_questions (
    session_id TEXT NOT NULL
        REFERENCES magenta_core_pending_interactions(session_id) ON DELETE CASCADE,
    question_index INTEGER NOT NULL CHECK (question_index >= 0),
    question_text TEXT NOT NULL CHECK (length(trim(question_text)) > 0),
    PRIMARY KEY (session_id, question_index)
);
CREATE INDEX IF NOT EXISTS idx_magenta_core_pending_question_order
    ON magenta_core_pending_interaction_questions(session_id, question_index);

CREATE TABLE IF NOT EXISTS magenta_core_audit_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id TEXT NOT NULL
        REFERENCES magenta_core_session_metadata(session_id) ON DELETE CASCADE,
    event_type TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);
CREATE INDEX IF NOT EXISTS idx_magenta_core_audit_session
    ON magenta_core_audit_log(session_id, created_at, id);
CREATE INDEX IF NOT EXISTS idx_magenta_core_audit_type
    ON magenta_core_audit_log(event_type, created_at, id);
