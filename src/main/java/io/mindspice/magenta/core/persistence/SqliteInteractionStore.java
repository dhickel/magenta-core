package io.mindspice.magenta.core.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta.core.ContextUsage;
import io.mindspice.magenta.core.ExecutionInfo;
import io.mindspice.magenta.core.ExecutionOrigin;
import io.mindspice.magenta.core.InteractionState;
import io.mindspice.magenta.core.ToolActivityData;
import io.mindspice.magenta.core.ToolActivityStatus;
import io.mindspice.magenta.core.TurnResponse;
import io.mindspice.magenta.core.spi.InteractionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Restart-safe SQLite persistence for exact durable question interactions. */
public final class SqliteInteractionStore implements InteractionStore {

    private static final Logger LOG = LoggerFactory.getLogger(SqliteInteractionStore.class);

    private static final String BASE_COLUMNS = """
        interaction_id, session_id, turn_id, model,
        explicit_tool_names_json, metadata_json, continuation_json,
        origin_type, origin_interaction_id, origin_message_id,
        started_at, completed_at, context_used_tokens,
        context_trigger_tokens, context_max_tokens, state,
        cancellation_reason, created_at, updated_at, cancelled_at
        """;

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
    public void save(Interaction interaction) {
        Objects.requireNonNull(interaction, "interaction");
        try {
            PersistenceSupport.transaction(dataSource, connection -> {
                PersistenceSupport.ensureSession(connection, interaction.sessionId());
                OriginFacts origin = originFacts(interaction.questions().execution().origin());
                try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO magenta_core_interactions
                        (interaction_id, session_id, turn_id, model,
                         explicit_tool_names_json, metadata_json, continuation_json,
                         origin_type, origin_interaction_id, origin_message_id,
                         started_at, completed_at, context_used_tokens,
                         context_trigger_tokens, context_max_tokens, state,
                         cancellation_reason, created_at, updated_at, cancelled_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                    statement.setString(1, interaction.interactionId());
                    statement.setString(2, interaction.sessionId());
                    statement.setString(3, interaction.questions().execution().turnId());
                    statement.setString(4, interaction.model());
                    statement.setString(5, PersistenceSupport.stringListToJson(
                        objectMapper, interaction.explicitToolNames()));
                    statement.setString(6, PersistenceSupport.toJson(
                        objectMapper, interaction.metadata()));
                    statement.setString(7, PersistenceSupport.toJson(
                        objectMapper, interaction.continuationMetadata()));
                    statement.setString(8, origin.type());
                    statement.setString(9, origin.interactionId());
                    statement.setString(10, origin.messageId());
                    statement.setString(11, PersistenceSupport.timestamp(
                        interaction.questions().execution().startedAt()));
                    statement.setString(12, PersistenceSupport.timestamp(
                        interaction.questions().execution().completedAt()));
                    ContextUsage usage = interaction.questions().execution().finalContextUsage();
                    statement.setInt(13, usage.usedTokens());
                    statement.setInt(14, usage.triggerTokens());
                    statement.setInt(15, usage.maxTokens());
                    statement.setString(16, interaction.state().name());
                    statement.setString(17, interaction.cancellationReason());
                    statement.setString(18, PersistenceSupport.timestamp(interaction.createdAt()));
                    statement.setString(19, PersistenceSupport.timestamp(interaction.updatedAt()));
                    statement.setString(20, interaction.cancelledAt() == null
                        ? null : PersistenceSupport.timestamp(interaction.cancelledAt()));
                    statement.executeUpdate();
                }

                insertQuestions(connection, interaction);
                insertActivities(connection, interaction);
                insertAnswers(connection, interaction.interactionId(), interaction.answers());
                return null;
            });
        } catch (RuntimeException e) {
            LOG.error("Failed to save interaction {}", interaction.interactionId(), e);
            throw new RuntimeException(
                "Failed to save interaction " + interaction.interactionId(), e);
        }
    }

    @Override
    public Optional<Interaction> pending(String sessionId) {
        requireText(sessionId, "sessionId");
        try {
            return PersistenceSupport.read(dataSource, connection -> {
                BaseRow row = findBase(connection, """
                    SELECT %s
                    FROM magenta_core_interactions
                    WHERE session_id = ? AND state = 'PENDING'
                    ORDER BY created_at DESC, interaction_id DESC
                    LIMIT 1
                    """.formatted(BASE_COLUMNS), sessionId, null);
                return row == null ? Optional.empty()
                    : Optional.of(toInteraction(connection, row));
            });
        } catch (RuntimeException e) {
            LOG.error("Failed to load pending interaction for session {}", sessionId, e);
            throw new RuntimeException(
                "Failed to load pending interaction for session " + sessionId, e);
        }
    }

    @Override
    public Optional<Interaction> find(String sessionId, String interactionId) {
        requireText(sessionId, "sessionId");
        requireText(interactionId, "interactionId");
        try {
            return PersistenceSupport.read(dataSource, connection -> {
                BaseRow row = findBase(connection, """
                    SELECT %s
                    FROM magenta_core_interactions
                    WHERE session_id = ? AND interaction_id = ?
                    """.formatted(BASE_COLUMNS), sessionId, interactionId);
                return row == null ? Optional.empty()
                    : Optional.of(toInteraction(connection, row));
            });
        } catch (RuntimeException e) {
            LOG.error("Failed to find interaction {} for session {}", interactionId, sessionId, e);
            throw new RuntimeException(
                "Failed to find interaction " + interactionId
                    + " for session " + sessionId, e);
        }
    }

    @Override
    public MutationResult answer(String sessionId,
                                 String interactionId,
                                 List<String> orderedAnswers) {
        requireText(sessionId, "sessionId");
        requireText(interactionId, "interactionId");
        Objects.requireNonNull(orderedAnswers, "orderedAnswers");
        try {
            return PersistenceSupport.transaction(dataSource, connection -> {
                BaseRow row = findBase(connection, """
                    SELECT %s
                    FROM magenta_core_interactions
                    WHERE session_id = ? AND interaction_id = ?
                    """.formatted(BASE_COLUMNS), sessionId, interactionId);
                if (row == null) {
                    return new MutationResult(false, latestState(connection, sessionId));
                }
                if (row.state() != InteractionState.PENDING) {
                    return new MutationResult(false, row.state());
                }

                List<String> questions = readQuestions(connection, interactionId);
                validateAnswers(questions, orderedAnswers);
                Instant updatedAt = Instant.now();
                try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE magenta_core_interactions
                    SET state = 'ANSWERED', cancellation_reason = NULL,
                        cancelled_at = NULL, updated_at = ?
                    WHERE session_id = ? AND interaction_id = ? AND state = 'PENDING'
                    """)) {
                    update.setString(1, PersistenceSupport.timestamp(updatedAt));
                    update.setString(2, sessionId);
                    update.setString(3, interactionId);
                    if (update.executeUpdate() != 1) {
                        return new MutationResult(false,
                            stateForTargetOrLatest(connection, sessionId, interactionId));
                    }
                }
                insertAnswers(connection, interactionId, List.copyOf(orderedAnswers));
                writeLifecycleAudit(connection, sessionId, "INTERACTION_ANSWERED",
                    Map.of("interactionId", interactionId));
                return new MutationResult(true, InteractionState.ANSWERED);
            });
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.error("Failed to answer interaction {} for session {}", interactionId, sessionId, e);
            throw new RuntimeException(
                "Failed to answer interaction " + interactionId
                    + " for session " + sessionId, e);
        }
    }

    @Override
    public MutationResult cancel(String sessionId, String interactionId, String reason) {
        requireText(sessionId, "sessionId");
        requireText(interactionId, "interactionId");
        requireText(reason, "reason");
        try {
            return PersistenceSupport.transaction(dataSource, connection -> {
                BaseRow row = findBase(connection, """
                    SELECT %s
                    FROM magenta_core_interactions
                    WHERE session_id = ? AND interaction_id = ?
                    """.formatted(BASE_COLUMNS), sessionId, interactionId);
                if (row == null) {
                    return new MutationResult(false, latestState(connection, sessionId));
                }
                if (row.state() != InteractionState.PENDING) {
                    return new MutationResult(false, row.state());
                }

                Instant cancelledAt = Instant.now();
                try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE magenta_core_interactions
                    SET state = 'CANCELLED', cancellation_reason = ?,
                        cancelled_at = ?, updated_at = ?
                    WHERE session_id = ? AND interaction_id = ? AND state = 'PENDING'
                    """)) {
                    update.setString(1, reason);
                    update.setString(2, PersistenceSupport.timestamp(cancelledAt));
                    update.setString(3, PersistenceSupport.timestamp(cancelledAt));
                    update.setString(4, sessionId);
                    update.setString(5, interactionId);
                    if (update.executeUpdate() != 1) {
                        return new MutationResult(false,
                            stateForTargetOrLatest(connection, sessionId, interactionId));
                    }
                }
                writeLifecycleAudit(connection, sessionId, "INTERACTION_CANCELLED",
                    Map.of("interactionId", interactionId, "reason", reason));
                return new MutationResult(true, InteractionState.CANCELLED);
            });
        } catch (RuntimeException e) {
            LOG.error("Failed to cancel interaction {} for session {}", interactionId, sessionId, e);
            throw new RuntimeException(
                "Failed to cancel interaction " + interactionId
                    + " for session " + sessionId, e);
        }
    }

    @Override
    public void delete(String sessionId, String interactionId) {
        requireText(sessionId, "sessionId");
        requireText(interactionId, "interactionId");
        try {
            PersistenceSupport.transaction(dataSource, connection -> {
                BaseRow target = findBase(connection, """
                    SELECT %s
                    FROM magenta_core_interactions
                    WHERE session_id = ? AND interaction_id = ?
                    """.formatted(BASE_COLUMNS), sessionId, interactionId);
                if (target == null) {
                    return null;
                }
                deleteChildren(connection, interactionId);
                try (PreparedStatement statement = connection.prepareStatement("""
                    DELETE FROM magenta_core_interactions
                    WHERE session_id = ? AND interaction_id = ?
                    """)) {
                    statement.setString(1, sessionId);
                    statement.setString(2, interactionId);
                    statement.executeUpdate();
                }
                return null;
            });
        } catch (RuntimeException e) {
            LOG.error("Failed to delete interaction {} for session {}", interactionId, sessionId, e);
            throw new RuntimeException(
                "Failed to delete interaction " + interactionId
                    + " for session " + sessionId, e);
        }
    }

    @Override
    public void deleteBySessionId(String sessionId) {
        requireText(sessionId, "sessionId");
        try {
            PersistenceSupport.transaction(dataSource, connection -> {
                for (String table : List.of(
                    "magenta_core_interaction_answers",
                    "magenta_core_interaction_questions",
                    "magenta_core_interaction_tool_activities")) {
                    try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM " + table
                            + " WHERE interaction_id IN ("
                            + "SELECT interaction_id FROM magenta_core_interactions "
                            + "WHERE session_id = ?)")) {
                        statement.setString(1, sessionId);
                        statement.executeUpdate();
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM magenta_core_interactions WHERE session_id = ?")) {
                    statement.setString(1, sessionId);
                    statement.executeUpdate();
                }
                return null;
            });
        } catch (RuntimeException e) {
            LOG.error("Failed to delete interactions for session {}", sessionId, e);
            throw new RuntimeException(
                "Failed to delete interactions for session " + sessionId, e);
        }
    }

    private void insertQuestions(Connection connection, Interaction interaction)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO magenta_core_interaction_questions
                (interaction_id, question_index, question_text)
            VALUES (?, ?, ?)
            """)) {
            List<String> questions = interaction.questions().questions();
            for (int index = 0; index < questions.size(); index++) {
                statement.setString(1, interaction.interactionId());
                statement.setInt(2, index);
                statement.setString(3, questions.get(index));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertActivities(Connection connection, Interaction interaction)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO magenta_core_interaction_tool_activities
                (interaction_id, activity_index, tool_name, tool_call_id, status, summary)
            VALUES (?, ?, ?, ?, ?, ?)
            """)) {
            List<ToolActivityData> activities =
                interaction.questions().execution().toolActivities();
            for (int index = 0; index < activities.size(); index++) {
                ToolActivityData activity = activities.get(index);
                statement.setString(1, interaction.interactionId());
                statement.setInt(2, index);
                statement.setString(3, activity.toolName());
                statement.setString(4, activity.toolCallId());
                statement.setString(5, activity.status().name());
                statement.setString(6, activity.summary());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertAnswers(Connection connection, String interactionId,
                               List<String> answers) throws SQLException {
        if (answers.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO magenta_core_interaction_answers
                (interaction_id, answer_index, answer_text)
            VALUES (?, ?, ?)
            """)) {
            for (int index = 0; index < answers.size(); index++) {
                statement.setString(1, interactionId);
                statement.setInt(2, index);
                statement.setString(3, answers.get(index));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private Interaction toInteraction(Connection connection, BaseRow row)
        throws SQLException {
        ExecutionInfo execution = new ExecutionInfo(
            row.sessionId(), row.turnId(), origin(row), row.startedAt(), row.completedAt(),
            new ContextUsage(row.contextUsedTokens(), row.contextTriggerTokens(),
                row.contextMaxTokens()),
            readActivities(connection, row.interactionId()));
        TurnResponse.Questions questions = new TurnResponse.Questions(
            execution, row.interactionId(), readQuestions(connection, row.interactionId()));
        return new Interaction(
            row.interactionId(), row.sessionId(), questions, row.model(),
            PersistenceSupport.stringListFromJson(
                objectMapper, row.explicitToolNamesJson()),
            PersistenceSupport.metadataFromJson(objectMapper, row.metadataJson()),
            PersistenceSupport.metadataFromJson(objectMapper, row.continuationJson()),
            readAnswers(connection, row.interactionId()), row.state(), row.createdAt(),
            row.updatedAt(), row.cancelledAt(), row.cancellationReason());
    }

    private List<String> readQuestions(Connection connection, String interactionId)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT question_text
            FROM magenta_core_interaction_questions
            WHERE interaction_id = ?
            ORDER BY question_index ASC
            """)) {
            statement.setString(1, interactionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<String> questions = new ArrayList<>();
                while (resultSet.next()) {
                    questions.add(resultSet.getString(1));
                }
                if (questions.isEmpty()) {
                    throw new SQLException(
                        "Persisted interaction has no ordered questions: " + interactionId);
                }
                return List.copyOf(questions);
            }
        }
    }

    private List<String> readAnswers(Connection connection, String interactionId)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT answer_text
            FROM magenta_core_interaction_answers
            WHERE interaction_id = ?
            ORDER BY answer_index ASC
            """)) {
            statement.setString(1, interactionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<String> answers = new ArrayList<>();
                while (resultSet.next()) {
                    answers.add(resultSet.getString(1));
                }
                return List.copyOf(answers);
            }
        }
    }

    private List<ToolActivityData> readActivities(Connection connection, String interactionId)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT tool_name, tool_call_id, status, summary
            FROM magenta_core_interaction_tool_activities
            WHERE interaction_id = ?
            ORDER BY activity_index ASC
            """)) {
            statement.setString(1, interactionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<ToolActivityData> activities = new ArrayList<>();
                while (resultSet.next()) {
                    activities.add(new ToolActivityData(
                        resultSet.getString("tool_name"),
                        resultSet.getString("tool_call_id"),
                        ToolActivityStatus.valueOf(resultSet.getString("status")),
                        resultSet.getString("summary")));
                }
                return List.copyOf(activities);
            }
        }
    }

    private BaseRow findBase(Connection connection, String sql,
                             String firstParameter, String secondParameter)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, firstParameter);
            if (secondParameter != null) {
                statement.setString(2, secondParameter);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readBase(resultSet) : null;
            }
        }
    }

    private BaseRow readBase(ResultSet resultSet) throws SQLException {
        return new BaseRow(
            resultSet.getString("interaction_id"),
            resultSet.getString("session_id"),
            resultSet.getString("turn_id"),
            resultSet.getString("model"),
            resultSet.getString("explicit_tool_names_json"),
            resultSet.getString("metadata_json"),
            resultSet.getString("continuation_json"),
            resultSet.getString("origin_type"),
            resultSet.getString("origin_interaction_id"),
            resultSet.getString("origin_message_id"),
            PersistenceSupport.parseTimestamp(resultSet.getString("started_at")),
            PersistenceSupport.parseTimestamp(resultSet.getString("completed_at")),
            resultSet.getInt("context_used_tokens"),
            resultSet.getInt("context_trigger_tokens"),
            resultSet.getInt("context_max_tokens"),
            InteractionState.valueOf(resultSet.getString("state")),
            resultSet.getString("cancellation_reason"),
            PersistenceSupport.parseTimestamp(resultSet.getString("created_at")),
            PersistenceSupport.parseTimestamp(resultSet.getString("updated_at")),
            PersistenceSupport.parseTimestamp(resultSet.getString("cancelled_at"))
        );
    }

    private InteractionState stateForTargetOrLatest(Connection connection,
                                                     String sessionId,
                                                     String interactionId)
        throws SQLException {
        BaseRow target = findBase(connection, """
            SELECT %s
            FROM magenta_core_interactions
            WHERE session_id = ? AND interaction_id = ?
            """.formatted(BASE_COLUMNS), sessionId, interactionId);
        return target == null ? latestState(connection, sessionId) : target.state();
    }

    private InteractionState latestState(Connection connection, String sessionId)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT state
            FROM magenta_core_interactions
            WHERE session_id = ?
            ORDER BY created_at DESC, interaction_id DESC
            LIMIT 1
            """)) {
            statement.setString(1, sessionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                    ? InteractionState.valueOf(resultSet.getString(1))
                    : InteractionState.MISSING;
            }
        }
    }

    private void deleteChildren(Connection connection, String interactionId)
        throws SQLException {
        for (String table : List.of(
            "magenta_core_interaction_answers",
            "magenta_core_interaction_questions",
            "magenta_core_interaction_tool_activities")) {
            try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + table + " WHERE interaction_id = ?")) {
                statement.setString(1, interactionId);
                statement.executeUpdate();
            }
        }
    }

    private void validateAnswers(List<String> questions, List<String> answers) {
        if (answers.size() != questions.size()) {
            throw new IllegalArgumentException(
                "Exactly one answer is required for each ordered question");
        }
        for (int index = 0; index < answers.size(); index++) {
            String answer = answers.get(index);
            if (answer == null || answer.isBlank()) {
                throw new IllegalArgumentException(
                    "answers[" + index + "] must not be blank");
            }
        }
    }

    private void writeLifecycleAudit(Connection connection, String sessionId,
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

    private OriginFacts originFacts(ExecutionOrigin origin) {
        return switch (origin) {
            case ExecutionOrigin.Direct ignored -> new OriginFacts("DIRECT", null, null);
            case ExecutionOrigin.Answer answer ->
                new OriginFacts("ANSWER", answer.interactionId(), null);
            case ExecutionOrigin.Queued queued ->
                new OriginFacts("QUEUED", null, queued.messageId());
        };
    }

    private ExecutionOrigin origin(BaseRow row) {
        return switch (row.originType()) {
            case "DIRECT" -> new ExecutionOrigin.Direct();
            case "ANSWER" -> new ExecutionOrigin.Answer(
                requirePersisted(row.originInteractionId(), "origin_interaction_id"));
            case "QUEUED" -> new ExecutionOrigin.Queued(
                requirePersisted(row.originMessageId(), "origin_message_id"));
            default -> throw new IllegalStateException(
                "Unknown persisted interaction origin: " + row.originType());
        };
    }

    private static String requirePersisted(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Persisted interaction field is missing: " + field);
        }
        return value;
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private record OriginFacts(String type, String interactionId, String messageId) {
    }

    private record BaseRow(
        String interactionId,
        String sessionId,
        String turnId,
        String model,
        String explicitToolNamesJson,
        String metadataJson,
        String continuationJson,
        String originType,
        String originInteractionId,
        String originMessageId,
        Instant startedAt,
        Instant completedAt,
        int contextUsedTokens,
        int contextTriggerTokens,
        int contextMaxTokens,
        InteractionState state,
        String cancellationReason,
        Instant createdAt,
        Instant updatedAt,
        Instant cancelledAt
    ) {
    }
}
