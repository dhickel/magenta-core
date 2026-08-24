package io.mindspice.magenta.core.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta.core.ContextUsage;
import io.mindspice.magenta.core.TurnMessage;
import io.mindspice.magenta.core.spi.AuditWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Fire-and-forget durable audit writer.
 *
 * <p>Audit failures are logged and suppressed as required by the public SPI;
 * a failed audit write never changes the outcome of the turn that emitted it.</p>
 */
public class SqliteAuditWriter implements AuditWriter {

    private static final Logger LOG = LoggerFactory.getLogger(SqliteAuditWriter.class);

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public SqliteAuditWriter(DataSource dataSource) {
        this(dataSource, new ObjectMapper());
    }

    public SqliteAuditWriter(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public void recordUserMessage(String sessionId, TurnMessage message) {
        try {
            Map<String, Object> payload = messagePayload(message);
            insert(sessionId, "USER_MESSAGE", payload);
        } catch (Exception e) {
            LOG.debug("Failed to record user-message audit for session {}", sessionId, e);
        }
    }

    @Override
    public void recordAssistantMessage(String sessionId, TurnMessage message) {
        try {
            Map<String, Object> payload = messagePayload(message);
            insert(sessionId, "ASSISTANT_MESSAGE", payload);
        } catch (Exception e) {
            LOG.debug("Failed to record assistant-message audit for session {}", sessionId, e);
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
            insert(sessionId, "TOOL_EXECUTION", payload);
        } catch (Exception e) {
            LOG.debug("Failed to record tool-execution audit for session {}", sessionId, e);
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
            insert(sessionId, "CONTEXT_USAGE", payload);
        } catch (Exception e) {
            LOG.debug("Failed to record context-usage audit for session {}", sessionId, e);
        }
    }

    @Override
    public void recordError(String sessionId, TurnError error) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("summary", error.summary());
            payload.put("stackTrace", error.stackTrace());
            payload.put("model", error.model());
            insert(sessionId, "ERROR", payload);
        } catch (Exception e) {
            LOG.debug("Failed to record error audit for session {}", sessionId, e);
        }
    }

    private void insert(String sessionId, String eventType, Map<String, Object> payload) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(eventType, "eventType");
        String json = PersistenceSupport.toJson(objectMapper, payload);
        PersistenceSupport.transaction(dataSource, connection -> {
            PersistenceSupport.ensureSession(connection, sessionId);
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO magenta_core_audit_log
                    (session_id, event_type, payload_json)
                VALUES (?, ?, ?)
                """)) {
                statement.setString(1, sessionId);
                statement.setString(2, eventType);
                statement.setString(3, json);
                statement.executeUpdate();
            }
            return null;
        });
    }

    private static Map<String, Object> messagePayload(TurnMessage message) {
        Objects.requireNonNull(message, "message");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("role", message.role());
        payload.put("text", message.text());
        payload.put("toolName", message.toolName());
        payload.put("toolCallId", message.toolCallId());
        payload.put("metadata", message.metadata());
        return payload;
    }
}
