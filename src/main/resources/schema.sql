-- Fresh Magenta Core response-protocol schema.
-- Historical magenta_core_* databases are incompatible and are rejected by
-- MigrationRunner before this script can create anything. Historical
-- turnloop_* tables are intentionally not referenced.
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

-- Queued caller intent and its internal claim lease live in one row. The
-- explicit model/tool columns deliberately preserve null versus an empty
-- explicit selection until drain-time execution.
CREATE TABLE IF NOT EXISTS magenta_core_queued_requests (
    message_id TEXT PRIMARY KEY CHECK (length(trim(message_id)) > 0),
    session_id TEXT NOT NULL
        REFERENCES magenta_core_session_metadata(session_id) ON DELETE CASCADE,
    message TEXT NOT NULL CHECK (length(trim(message)) > 0),
    explicit_model TEXT CHECK (
        explicit_model IS NULL OR length(trim(explicit_model)) > 0
    ),
    explicit_tool_names_json TEXT,
    metadata_json TEXT NOT NULL,
    queue_reason TEXT NOT NULL CHECK (
        queue_reason IN ('EXPLICIT', 'SESSION_ACTIVE', 'AWAITING_QUESTIONS')
    ),
    enqueued_at TEXT NOT NULL,
    claim_token TEXT,
    claimed_at TEXT,
    lease_expires_at TEXT,
    CHECK (
        (claim_token IS NULL AND claimed_at IS NULL AND lease_expires_at IS NULL)
        OR (claim_token IS NOT NULL AND claimed_at IS NOT NULL AND lease_expires_at IS NOT NULL)
    )
);
CREATE INDEX IF NOT EXISTS idx_magenta_core_queue_session_order
    ON magenta_core_queued_requests(session_id, enqueued_at, message_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_magenta_core_queue_claim_token
    ON magenta_core_queued_requests(claim_token)
    WHERE claim_token IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_magenta_core_queue_expired_claims
    ON magenta_core_queued_requests(lease_expires_at)
    WHERE claim_token IS NOT NULL;

-- An interaction is a durable Questions outcome, not a delivery receipt.
-- Terminal rows remain until exact interaction cleanup so restart recovery can
-- consume the stored ordered answer list without replaying an outcome.
CREATE TABLE IF NOT EXISTS magenta_core_interactions (
    interaction_id TEXT PRIMARY KEY CHECK (length(trim(interaction_id)) > 0),
    session_id TEXT NOT NULL
        REFERENCES magenta_core_session_metadata(session_id) ON DELETE CASCADE,
    turn_id TEXT NOT NULL CHECK (length(trim(turn_id)) > 0),
    model TEXT NOT NULL CHECK (length(trim(model)) > 0),
    explicit_tool_names_json TEXT,
    metadata_json TEXT NOT NULL,
    continuation_json TEXT NOT NULL,
    origin_type TEXT NOT NULL CHECK (
        origin_type IN ('DIRECT', 'ANSWER', 'QUEUED')
    ),
    origin_interaction_id TEXT,
    origin_message_id TEXT,
    started_at TEXT NOT NULL,
    completed_at TEXT NOT NULL,
    context_used_tokens INTEGER NOT NULL,
    context_trigger_tokens INTEGER NOT NULL,
    context_max_tokens INTEGER NOT NULL,
    state TEXT NOT NULL CHECK (
        state IN ('PENDING', 'ANSWERED', 'CANCELLED')
    ),
    cancellation_reason TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    cancelled_at TEXT,
    CHECK (updated_at >= created_at),
    CHECK (
        (state = 'CANCELLED'
            AND cancellation_reason IS NOT NULL
            AND length(trim(cancellation_reason)) > 0
            AND cancelled_at IS NOT NULL)
        OR (state <> 'CANCELLED'
            AND cancellation_reason IS NULL
            AND cancelled_at IS NULL)
    ),
    CHECK (
        (origin_type = 'DIRECT'
            AND origin_interaction_id IS NULL AND origin_message_id IS NULL)
        OR (origin_type = 'ANSWER'
            AND origin_interaction_id IS NOT NULL AND origin_message_id IS NULL)
        OR (origin_type = 'QUEUED'
            AND origin_interaction_id IS NULL AND origin_message_id IS NOT NULL)
    )
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_magenta_core_interactions_one_pending
    ON magenta_core_interactions(session_id)
    WHERE state = 'PENDING';
CREATE INDEX IF NOT EXISTS idx_magenta_core_interactions_session_state
    ON magenta_core_interactions(session_id, state, created_at);
CREATE INDEX IF NOT EXISTS idx_magenta_core_interactions_turn
    ON magenta_core_interactions(turn_id);

CREATE TABLE IF NOT EXISTS magenta_core_interaction_questions (
    interaction_id TEXT NOT NULL
        REFERENCES magenta_core_interactions(interaction_id) ON DELETE CASCADE,
    question_index INTEGER NOT NULL CHECK (question_index >= 0),
    question_text TEXT NOT NULL CHECK (length(trim(question_text)) > 0),
    PRIMARY KEY (interaction_id, question_index)
);
CREATE INDEX IF NOT EXISTS idx_magenta_core_interaction_questions_order
    ON magenta_core_interaction_questions(interaction_id, question_index);

CREATE TABLE IF NOT EXISTS magenta_core_interaction_answers (
    interaction_id TEXT NOT NULL
        REFERENCES magenta_core_interactions(interaction_id) ON DELETE CASCADE,
    answer_index INTEGER NOT NULL CHECK (answer_index >= 0),
    answer_text TEXT NOT NULL CHECK (length(trim(answer_text)) > 0),
    PRIMARY KEY (interaction_id, answer_index)
);
CREATE INDEX IF NOT EXISTS idx_magenta_core_interaction_answers_order
    ON magenta_core_interaction_answers(interaction_id, answer_index);

CREATE TABLE IF NOT EXISTS magenta_core_interaction_tool_activities (
    interaction_id TEXT NOT NULL
        REFERENCES magenta_core_interactions(interaction_id) ON DELETE CASCADE,
    activity_index INTEGER NOT NULL CHECK (activity_index >= 0),
    tool_name TEXT NOT NULL CHECK (length(trim(tool_name)) > 0),
    tool_call_id TEXT NOT NULL CHECK (length(trim(tool_call_id)) > 0),
    status TEXT NOT NULL CHECK (status IN ('COMPLETED', 'FAILED')),
    summary TEXT NOT NULL,
    PRIMARY KEY (interaction_id, activity_index)
);
CREATE INDEX IF NOT EXISTS idx_magenta_core_interaction_activities_order
    ON magenta_core_interaction_tool_activities(interaction_id, activity_index);

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
