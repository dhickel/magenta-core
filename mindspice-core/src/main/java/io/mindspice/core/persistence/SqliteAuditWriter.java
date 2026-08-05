package io.mindspice.core.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.core.ContextUsage;
import io.mindspice.core.TurnMessage;
import io.mindspice.core.spi.AuditWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SQLite-backed {@link AuditWriter} implementation.
 * <p>
 * Self-migrates the {@code turnloop_audit_log} table on construction.
 * Every method is fire-and-forget: exceptions are caught and logged
 * internally — callers never see audit failures.
 */
public class SqliteAuditWriter implements AuditWriter {

    private static final Logger log = LoggerFactory.getLogger(SqliteAuditWriter.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SqliteAuditWriter(DataSource dataSource) {
        this(dataSource, new ObjectMapper());
    }

    public SqliteAuditWriter(DataSource dataSource, ObjectMapper objectMapper) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.objectMapper = objectMapper;
        migrate();
    }

    // ── self-migration ──

    private void migrate() {
        try {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS turnloop_audit_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id TEXT NOT NULL,
                    event_type TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    created_at TEXT NOT NULL DEFAULT (datetime('now'))
                )
                """);
            jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_audit_session
                ON turnloop_audit_log(session_id, created_at)
                """);
            jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_audit_type
                ON turnloop_audit_log(event_type, created_at)
                """);
        } catch (Exception e) {
            log.error("Audit migration failed — audit logging disabled", e);
        }
    }

    // ── AuditWriter (fire-and-forget) ──

    @Override
    public void recordUserMessage(String sessionId, TurnMessage message) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("role", message.role());
            payload.put("text", message.text());
            payload.put("toolName", message.toolName());
            payload.put("toolCallId", message.toolCallId());
            insertAudit(sessionId, "USER_MESSAGE", payload);
        } catch (Exception e) {
            log.debug("Failed to record user message audit for session {}", sessionId, e);
        }
    }

    @Override
    public void recordAssistantMessage(String sessionId, TurnMessage message) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("role", message.role());
            payload.put("text", message.text());
            payload.put("toolName", message.toolName());
            payload.put("toolCallId", message.toolCallId());
            insertAudit(sessionId, "ASSISTANT_MESSAGE", payload);
        } catch (Exception e) {
            log.debug("Failed to record assistant message audit for session {}", sessionId, e);
        }
    }

    @Override
    public void recordToolExecution(String sessionId, ToolExecutionRecord record) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("toolName", record.toolName());
            payload.put("toolCallId", record.toolCallId());
            payload.put("arguments", record.arguments());
            payload.put("resultSummary", record.resultSummary());
            payload.put("success", record.success());
            payload.put("durationMs", record.durationMs());
            insertAudit(sessionId, "TOOL_EXECUTION", payload);
        } catch (Exception e) {
            log.debug("Failed to record tool execution audit for session {}", sessionId, e);
        }
    }

    @Override
    public void recordContextUsage(String sessionId, ContextUsage usage, String model) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("usedTokens", usage.usedTokens());
            payload.put("triggerTokens", usage.triggerTokens());
            payload.put("maxTokens", usage.maxTokens());
            payload.put("utilizationPercent", usage.utilizationPercent());
            payload.put("model", model);
            insertAudit(sessionId, "CONTEXT_USAGE", payload);
        } catch (Exception e) {
            log.debug("Failed to record context usage audit for session {}", sessionId, e);
        }
    }

    @Override
    public void recordError(String sessionId, TurnError error) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("summary", error.summary());
            payload.put("stackTrace", error.stackTrace());
            payload.put("model", error.model());
            insertAudit(sessionId, "ERROR", payload);
        } catch (Exception e) {
            log.debug("Failed to record error audit for session {}", sessionId, e);
        }
    }

    // ── internal ──

    private void insertAudit(String sessionId, String eventType, Map<String, Object> payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            jdbc.update(
                "INSERT INTO turnloop_audit_log (session_id, event_type, payload_json) VALUES (?, ?, ?)",
                sessionId, eventType, json
            );
        } catch (JsonProcessingException e) {
            log.debug("Failed to serialize audit payload for event {} session {}", eventType, sessionId, e);
        }
    }
}
