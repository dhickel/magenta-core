package io.mindspice.magenta.core.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta.core.QuestionPendingContext;
import io.mindspice.magenta.core.spi.InteractionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Restart-safe pending interaction repository.
 *
 * <p>One active interaction is retained per session. Questions are normalized
 * into an ordered child table so a multi-question prompt cannot be collapsed
 * into a single string or reordered during a restart. Terminal answer and
 * cancellation snapshots remain in the database for audit/recovery, while
 * {@link #load(String)} exposes only an active {@code PENDING} interaction to
 * the turn state machine.</p>
 */
public final class SqliteInteractionStore implements InteractionStore {

    private static final Logger LOG = LoggerFactory.getLogger(SqliteInteractionStore.class);
    private static final TypeReference<Map<String, String>> CONTINUATION_MAP = new TypeReference<>() {};

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public SqliteInteractionStore(DataSource dataSource) {
        this(dataSource, new ObjectMapper());
    }

    public SqliteInteractionStore(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public void save(String sessionId, QuestionPendingContext context) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(context, "context");
        if (!sessionId.equals(context.sessionId())) {
            throw new IllegalArgumentException(
                "Interaction session does not match the persistence key");
        }
        try {
            PersistenceSupport.transaction(dataSource, connection -> {
                PersistenceSupport.ensureSession(connection, sessionId);
                String now = Instant.now().toString();
                try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO magenta_core_pending_interactions
                        (session_id, turn_id, model, continuation_json, state,
                         answer_text, cancellation_reason, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(session_id) DO UPDATE SET
                        turn_id = excluded.turn_id,
                        model = excluded.model,
                        continuation_json = excluded.continuation_json,
                        state = excluded.state,
                        answer_text = excluded.answer_text,
                        cancellation_reason = excluded.cancellation_reason,
                        updated_at = excluded.updated_at
                    """)) {
                    statement.setString(1, sessionId);
                    statement.setString(2, context.turnId());
                    statement.setString(3, context.model());
                    statement.setString(4,
                        PersistenceSupport.toJson(objectMapper, context.continuationContext()));
                    statement.setString(5, context.state().name());
                    statement.setString(6, context.answer());
                    statement.setString(7, context.cancellationReason());
                    statement.setString(8, now);
                    statement.setString(9, now);
                    statement.executeUpdate();
                }

                try (PreparedStatement deleteQuestions = connection.prepareStatement("""
                    DELETE FROM magenta_core_pending_interaction_questions
                    WHERE session_id = ?
                    """)) {
                    deleteQuestions.setString(1, sessionId);
                    deleteQuestions.executeUpdate();
                }

                try (PreparedStatement insertQuestion = connection.prepareStatement("""
                    INSERT INTO magenta_core_pending_interaction_questions
                        (session_id, question_index, question_text)
                    VALUES (?, ?, ?)
                    """)) {
                    for (int index = 0; index < context.questions().size(); index++) {
                        insertQuestion.setString(1, sessionId);
                        insertQuestion.setInt(2, index);
                        insertQuestion.setString(3, context.questions().get(index));
                        insertQuestion.addBatch();
                    }
                    insertQuestion.executeBatch();
                }
                return null;
            });
        } catch (RuntimeException e) {
            LOG.error("Failed to save pending interaction for session {}", sessionId, e);
            throw new RuntimeException(
                "Failed to save pending interaction for session " + sessionId, e);
        }
    }

    @Override
    public QuestionPendingContext load(String sessionId) {
        return find(sessionId, true);
    }

    /**
     * Returns the persisted interaction in any lifecycle state. This is useful
     * for recovery/audit code; the SPI-facing {@link #load(String)} method only
     * returns a currently answerable interaction.
     */
    QuestionPendingContext findPersisted(String sessionId) {
        return find(sessionId, false);
    }

    /**
     * Atomically records one combined answer. A false result means the row was
     * already answered, cancelled, deleted, or never existed.
     */
    @Override
    public boolean answer(String sessionId, String answer) {
        Objects.requireNonNull(sessionId, "sessionId");
        if (answer == null || answer.isBlank()) {
            throw new IllegalArgumentException("answer must not be blank");
        }
        try {
            return PersistenceSupport.transaction(dataSource, connection -> {
                try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE magenta_core_pending_interactions
                    SET state = 'ANSWERED', answer_text = ?,
                        cancellation_reason = NULL, updated_at = ?
                    WHERE session_id = ? AND state = 'PENDING'
                    """)) {
                    statement.setString(1, answer);
                    statement.setString(2, Instant.now().toString());
                    statement.setString(3, sessionId);
                    boolean updated = statement.executeUpdate() == 1;
                    if (updated) {
                        writeLifecycleAudit(connection, sessionId, "INTERACTION_ANSWERED",
                            Map.of("answer", answer));
                    }
                    return updated;
                }
            });
        } catch (RuntimeException e) {
            LOG.error("Failed to record answer for session {}", sessionId, e);
            throw new RuntimeException("Failed to record answer for session " + sessionId, e);
        }
    }

    /** Atomically records cancellation and makes the interaction non-pending. */
    @Override
    public boolean cancel(String sessionId, String reason) {
        Objects.requireNonNull(sessionId, "sessionId");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("cancellation reason must not be blank");
        }
        try {
            return PersistenceSupport.transaction(dataSource, connection -> {
                try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE magenta_core_pending_interactions
                    SET state = 'CANCELLED', answer_text = NULL,
                        cancellation_reason = ?, updated_at = ?
                    WHERE session_id = ? AND state = 'PENDING'
                    """)) {
                    statement.setString(1, reason);
                    statement.setString(2, Instant.now().toString());
                    statement.setString(3, sessionId);
                    boolean updated = statement.executeUpdate() == 1;
                    if (updated) {
                        writeLifecycleAudit(connection, sessionId, "INTERACTION_CANCELLED",
                            Map.of("reason", reason));
                    }
                    return updated;
                }
            });
        } catch (RuntimeException e) {
            LOG.error("Failed to cancel interaction for session {}", sessionId, e);
            throw new RuntimeException(
                "Failed to cancel interaction for session " + sessionId, e);
        }
    }

    @Override
    public void delete(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        try {
            PersistenceSupport.transaction(dataSource, connection -> {
                // Delete children explicitly as well as relying on the FK so
                // behavior remains correct for a compatible driver with FK
                // enforcement disabled by the host.
                try (PreparedStatement questions = connection.prepareStatement("""
                    DELETE FROM magenta_core_pending_interaction_questions
                    WHERE session_id = ?
                    """)) {
                    questions.setString(1, sessionId);
                    questions.executeUpdate();
                }
                try (PreparedStatement interaction = connection.prepareStatement("""
                    DELETE FROM magenta_core_pending_interactions
                    WHERE session_id = ?
                    """)) {
                    interaction.setString(1, sessionId);
                    interaction.executeUpdate();
                }
                return null;
            });
        } catch (RuntimeException e) {
            LOG.error("Failed to delete pending interaction for session {}", sessionId, e);
            throw new RuntimeException(
                "Failed to delete pending interaction for session " + sessionId, e);
        }
    }

    private void writeLifecycleAudit(java.sql.Connection connection, String sessionId,
                                     String eventType, Map<String, String> payload)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO magenta_core_audit_log
                (session_id, event_type, payload_json)
            VALUES (?, ?, ?)
            """)) {
            statement.setString(1, sessionId);
            statement.setString(2, eventType);
            statement.setString(3, PersistenceSupport.toJson(objectMapper, payload));
            statement.executeUpdate();
        }
    }

    private QuestionPendingContext find(String sessionId, boolean pendingOnly) {
        Objects.requireNonNull(sessionId, "sessionId");
        try {
            return PersistenceSupport.read(dataSource, connection -> {
                String sql = """
                    SELECT turn_id, model, continuation_json, state,
                           answer_text, cancellation_reason, created_at, updated_at
                    FROM magenta_core_pending_interactions
                    WHERE session_id = ?
                    """ + (pendingOnly ? " AND state = 'PENDING'" : "");
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, sessionId);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        if (!resultSet.next()) {
                            return null;
                        }
                        List<String> questions = readQuestions(connection, sessionId);
                        if (questions.isEmpty()) {
                            throw new IllegalStateException(
                                "Persisted pending interaction has no questions");
                        }
                        return new QuestionPendingContext(
                            sessionId,
                            resultSet.getString("turn_id"),
                            questions,
                            resultSet.getString("model"),
                            continuationFromJson(resultSet.getString("continuation_json")),
                            QuestionPendingContext.State.valueOf(resultSet.getString("state")),
                            resultSet.getString("answer_text"),
                            resultSet.getString("cancellation_reason")
                        );
                    }
                }
            });
        } catch (RuntimeException e) {
            LOG.error("Failed to load pending interaction for session {}", sessionId, e);
            throw new RuntimeException(
                "Failed to load pending interaction for session " + sessionId, e);
        }
    }

    private List<String> readQuestions(java.sql.Connection connection, String sessionId)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT question_text
            FROM magenta_core_pending_interaction_questions
            WHERE session_id = ?
            ORDER BY question_index ASC
            """)) {
            statement.setString(1, sessionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<String> questions = new ArrayList<>();
                while (resultSet.next()) {
                    questions.add(resultSet.getString(1));
                }
                return List.copyOf(questions);
            }
        }
    }

    private Map<String, String> continuationFromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return Map.copyOf(objectMapper.readValue(json, CONTINUATION_MAP));
        } catch (Exception e) {
            throw new IllegalStateException(
                "Unable to deserialize pending interaction continuation context", e);
        }
    }
}
