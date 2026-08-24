-- Framework version tracking
CREATE TABLE IF NOT EXISTS turnloop_schema_version (
    version INTEGER NOT NULL,
    applied_at TEXT NOT NULL DEFAULT (datetime('now')),
    description TEXT
);

-- Conversation memory: stores all chat messages
CREATE TABLE IF NOT EXISTS turnloop_conversation_memory (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id TEXT NOT NULL,
    role TEXT NOT NULL,                -- 'system', 'user', 'assistant', 'tool'
    content TEXT NOT NULL,
    tool_name TEXT,
    tool_call_id TEXT,
    metadata_json TEXT,                -- JSON object for extension data
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_memory_session ON turnloop_conversation_memory(session_id, id);
CREATE INDEX IF NOT EXISTS idx_memory_session_role ON turnloop_conversation_memory(session_id, role);

-- Session metadata
CREATE TABLE IF NOT EXISTS turnloop_session_metadata (
    session_id TEXT PRIMARY KEY,
    title TEXT,
    model TEXT,
    favorite INTEGER NOT NULL DEFAULT 0,
    archived INTEGER NOT NULL DEFAULT 0,
    origin TEXT NOT NULL DEFAULT 'turnloop',
    metadata_json TEXT,                -- JSON object for extension data
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_session_origin ON turnloop_session_metadata(origin);
CREATE INDEX IF NOT EXISTS idx_session_favorite ON turnloop_session_metadata(favorite);
CREATE INDEX IF NOT EXISTS idx_session_archived ON turnloop_session_metadata(archived);

-- Pending message queue
CREATE TABLE IF NOT EXISTS turnloop_pending_messages (
    message_id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL,
    message_text TEXT NOT NULL,
    metadata_json TEXT,
    claim_token TEXT,
    claimed_at TEXT,
    enqueued_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_pending_session ON turnloop_pending_messages(session_id);
CREATE INDEX IF NOT EXISTS idx_pending_claim ON turnloop_pending_messages(claim_token);
CREATE INDEX IF NOT EXISTS idx_pending_stale ON turnloop_pending_messages(claimed_at);

-- Audit log
CREATE TABLE IF NOT EXISTS turnloop_audit_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id TEXT NOT NULL,
    event_type TEXT NOT NULL,          -- 'USER_MESSAGE', 'ASSISTANT_MESSAGE', 'TOOL_EXECUTION', 'CONTEXT_USAGE', 'ERROR'
    payload_json TEXT NOT NULL,        -- JSON object with event-specific fields
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_audit_session ON turnloop_audit_log(session_id, created_at);
CREATE INDEX IF NOT EXISTS idx_audit_type ON turnloop_audit_log(event_type, created_at);
