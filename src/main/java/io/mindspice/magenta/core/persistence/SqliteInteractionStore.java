package io.mindspice.magenta.core.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta.core.ContextUsage;
import io.mindspice.magenta.core.ExecutionInfo;
import io.mindspice.magenta.core.ExecutionOrigin;
import io.mindspice.magenta.core.ToolActivityData;
import io.mindspice.magenta.core.ToolFailureCategory;
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
                String state;
                List<String> answers;
                String cancellationReason = null;
                Instant cancelledAt = null;
                switch (interaction) {
                    case Interaction.Pending ignored -> {
                        state = "PENDING";
                        answers = List.of();
                    }
                    case Interaction.Answered answered -> {
                        state = "ANSWERED";
                        answers = answered.answers();
                    }
                    case Interaction.Cancelled cancelled -> {
                        state = "CANCELLED";
                        answers = List.of();
                        cancellationReason = cancelled.reason();
                        cancelledAt = cancelled.cancelledAt();
                    }
                }
                InteractionStore.InteractionInfo info = interaction.info();
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
                    statement.setString(1, info.interactionId());
                    statement.setString(2, info.sessionId());
                    statement.setString(3, info.questions().execution().turnId());
                    statement.setString(4, info.model());
                    statement.setString(5, PersistenceSupport.stringListToJson(
                        objectMapper, info.explicitToolNames()));
                    statement.setString(6, PersistenceSupport.toJson(
                        objectMapper, info.metadata()));
                    statement.setString(7, PersistenceSupport.toJson(
                        objectMapper, info.continuationMetadata()));
                    statement.setString(8, origin.type());
                    statement.setString(9, origin.interactionId());
                    statement.setString(10, origin.messageId());
                    statement.setString(11, PersistenceSupport.timestamp(
                        info.questions().execution().startedAt()));
                    statement.setString(12, PersistenceSupport.timestamp(
                        info.questions().execution().completedAt()));
                    ContextUsage usage = info.questions().execution().finalContextUsage();
                    statement.setInt(13, usage.usedTokens());
                    statement.setInt(14, usage.triggerTokens());
                    statement.setInt(15, usage.maxTokens());
                    statement.setString(16, state);
                    statement.setString(17, cancellationReason);
                    statement.setString(18, PersistenceSupport.timestamp(info.createdAt()));
                    statement.setString(19, PersistenceSupport.timestamp(info.updatedAt()));
                    statement.setString(20, cancelledAt == null
                        ? null : PersistenceSupport.timestamp(cancelledAt));
                    statement.executeUpdate();
                }

                insertQuestions(connection, interaction);
                insertActivities(connection, interaction);
                insertAnswers(connection, interaction.interactionId(), answers);
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
                    return new MutationResult.Missing();
                }
                Interaction current = toInteraction(connection, row);
                MutationResult terminal = terminalResult(current);
                if (terminal != null) {
                    return terminal;
                }

                Interaction.Pending pending = (Interaction.Pending) current;
                validateAnswers(pending.questions().questions(), orderedAnswers);
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
                        return mutationAfterCas(connection, sessionId, interactionId);
                    }
                }
                List<String> answers = List.copyOf(orderedAnswers);
                insertAnswers(connection, interactionId, answers);
                writeLifecycleAudit(connection, sessionId, "INTERACTION_ANSWERED",
                    Map.of("interactionId", interactionId));
                Interaction.Answered answered = new Interaction.Answered(
                    updated(pending.info(), updatedAt), answers);
                return new MutationResult.Applied(answered);
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
                    return new MutationResult.Missing();
                }
                Interaction current = toInteraction(connection, row);
                MutationResult terminal = terminalResult(current);
                if (terminal != null) {
                    return terminal;
                }

                Interaction.Pending pending = (Interaction.Pending) current;
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
                        return mutationAfterCas(connection, sessionId, interactionId);
                    }
                }
                writeLifecycleAudit(connection, sessionId, "INTERACTION_CANCELLED",
                    Map.of("interactionId", interactionId, "reason", reason));
                Interaction.Cancelled cancelled = new Interaction.Cancelled(
                    updated(pending.info(), cancelledAt), cancelledAt, reason);
                return new MutationResult.Applied(cancelled);
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
                (interaction_id, activity_index, tool_name, tool_call_id, status,
                 failure_category, summary)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """)) {
            List<ToolActivityData> activities =
                interaction.questions().execution().toolActivities();
            for (int index = 0; index < activities.size(); index++) {
                ToolActivityData activity = activities.get(index);
                statement.setString(1, interaction.interactionId());
                statement.setInt(2, index);
                statement.setString(3, activity.toolName());
                statement.setString(4, activity.toolCallId());
                String status;
                String failureCategory = null;
                switch (activity) {
                    case ToolActivityData.Completed ignored -> status = "COMPLETED";
                    case ToolActivityData.Failed failed -> {
                        status = "FAILED";
                        failureCategory = failed.category().name();
                    }
                }
                statement.setString(5, status);
                statement.setString(6, failureCategory);
                statement.setString(7, activity.summary());
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
        InteractionStore.InteractionInfo info = new InteractionStore.InteractionInfo(
            row.interactionId(), row.sessionId(), questions, row.model(),
            PersistenceSupport.stringListFromJson(
                objectMapper, row.explicitToolNamesJson()),
            PersistenceSupport.metadataFromJson(objectMapper, row.metadataJson()),
            PersistenceSupport.metadataFromJson(objectMapper, row.continuationJson()),
            row.createdAt(), row.updatedAt());
        List<String> answers = readAnswers(connection, row.interactionId());
        return switch (row.state()) {
            case "PENDING" -> {
                if (!answers.isEmpty() || row.cancelledAt() != null
                    || row.cancellationReason() != null) {
                    throw new SQLException(
                        "Persisted pending interaction contains terminal facts: "
                            + row.interactionId());
                }
                yield new Interaction.Pending(info);
            }
            case "ANSWERED" -> {
                if (row.cancelledAt() != null || row.cancellationReason() != null) {
                    throw new SQLException(
                        "Persisted answered interaction contains cancellation facts: "
                            + row.interactionId());
                }
                try {
                    yield new Interaction.Answered(info, answers);
                } catch (IllegalArgumentException e) {
                    throw new SQLException(
                        "Persisted answered interaction has invalid ordered answers: "
                            + row.interactionId(), e);
                }
            }
            case "CANCELLED" -> {
                if (!answers.isEmpty()) {
                    throw new SQLException(
                        "Persisted cancelled interaction contains answers: "
                            + row.interactionId());
                }
                try {
                    yield new Interaction.Cancelled(
                        info, row.cancelledAt(), row.cancellationReason());
                } catch (NullPointerException | IllegalArgumentException e) {
                    throw new SQLException(
                        "Persisted cancelled interaction has invalid cancellation facts: "
                            + row.interactionId(), e);
                }
            }
            default -> throw new SQLException(
                "Unknown persisted interaction state: " + row.state());
        };
    }

    private List<String> readQuestions(Connection connection, String interactionId)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT question_index, question_text
            FROM magenta_core_interaction_questions
            WHERE interaction_id = ?
            ORDER BY question_index ASC
            """)) {
            statement.setString(1, interactionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<String> questions = new ArrayList<>();
                while (resultSet.next()) {
                    int index = resultSet.getInt("question_index");
                    if (index != questions.size()) {
                        throw new SQLException(
                            "Persisted interaction question indexes are not contiguous: "
                                + interactionId);
                    }
                    questions.add(resultSet.getString("question_text"));
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
            SELECT answer_index, answer_text
            FROM magenta_core_interaction_answers
            WHERE interaction_id = ?
            ORDER BY answer_index ASC
            """)) {
            statement.setString(1, interactionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<String> answers = new ArrayList<>();
                while (resultSet.next()) {
                    int index = resultSet.getInt("answer_index");
                    if (index != answers.size()) {
                        throw new SQLException(
                            "Persisted interaction answer indexes are not contiguous: "
                                + interactionId);
                    }
                    answers.add(resultSet.getString("answer_text"));
                }
                return List.copyOf(answers);
            }
        }
    }

    private List<ToolActivityData> readActivities(Connection connection, String interactionId)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT activity_index, tool_name, tool_call_id, status, failure_category, summary
            FROM magenta_core_interaction_tool_activities
            WHERE interaction_id = ?
            ORDER BY activity_index ASC
            """)) {
            statement.setString(1, interactionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<ToolActivityData> activities = new ArrayList<>();
                while (resultSet.next()) {
                    int index = resultSet.getInt("activity_index");
                    if (index != activities.size()) {
                        throw new SQLException(
                            "Persisted tool activity indexes are not contiguous: "
                                + interactionId);
                    }
                    String toolName = resultSet.getString("tool_name");
                    String toolCallId = resultSet.getString("tool_call_id");
                    String summary = resultSet.getString("summary");
                    String status = resultSet.getString("status");
                    String failureCategory = resultSet.getString("failure_category");
                    try {
                        ToolActivityData activity = switch (status) {
                            case "COMPLETED" -> {
                                if (failureCategory != null) {
                                    throw new SQLException(
                                        "Completed tool activity has a failure category");
                                }
                                yield new ToolActivityData.Completed(
                                    toolName, toolCallId, summary);
                            }
                            case "FAILED" -> {
                                if (failureCategory == null || failureCategory.isBlank()) {
                                    throw new SQLException(
                                        "Failed tool activity has no failure category");
                                }
                                yield new ToolActivityData.Failed(
                                    toolName, toolCallId, summary,
                                    ToolFailureCategory.valueOf(failureCategory));
                            }
                            default -> throw new SQLException(
                                "Unknown persisted tool activity status: " + status);
                        };
                        activities.add(activity);
                    } catch (IllegalArgumentException e) {
                        throw new SQLException(
                            "Invalid persisted tool activity: " + interactionId, e);
                    }
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
            resultSet.getString("state"),
            resultSet.getString("cancellation_reason"),
            PersistenceSupport.parseTimestamp(resultSet.getString("created_at")),
            PersistenceSupport.parseTimestamp(resultSet.getString("updated_at")),
            PersistenceSupport.parseTimestamp(resultSet.getString("cancelled_at"))
        );
    }

    private MutationResult mutationAfterCas(Connection connection,
                                             String sessionId,
                                             String interactionId)
        throws SQLException {
        BaseRow current = findBase(connection, """
            SELECT %s
            FROM magenta_core_interactions
            WHERE session_id = ? AND interaction_id = ?
            """.formatted(BASE_COLUMNS), sessionId, interactionId);
        if (current == null) {
            return new MutationResult.Missing();
        }
        MutationResult result = terminalResult(toInteraction(connection, current));
        if (result == null) {
            throw new SQLException(
                "Interaction CAS lost without a terminal winner: " + interactionId);
        }
        return result;
    }

    private MutationResult terminalResult(Interaction interaction) {
        return switch (interaction) {
            case Interaction.Pending ignored -> null;
            case Interaction.Answered ignored -> new MutationResult.AlreadyAnswered();
            case Interaction.Cancelled ignored -> new MutationResult.AlreadyCancelled();
        };
    }

    private static InteractionStore.InteractionInfo updated(
        InteractionStore.InteractionInfo info, Instant updatedAt) {
        return new InteractionStore.InteractionInfo(
            info.interactionId(), info.sessionId(), info.questions(), info.model(),
            info.explicitToolNames(), info.metadata(), info.continuationMetadata(),
            info.createdAt(), updatedAt);
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
        String state,
        String cancellationReason,
        Instant createdAt,
        Instant updatedAt,
        Instant cancelledAt
    ) {
    }
}
