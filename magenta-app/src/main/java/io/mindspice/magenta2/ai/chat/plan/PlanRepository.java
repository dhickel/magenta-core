package io.mindspice.magenta2.ai.chat.plan;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Owns persistence for {@link PlanDefinition} and {@link PlanRun} in the
 * {@code plan_definitions} and {@code plan_runs} tables.
 *
 * <p>JSON columns store lists and structured objects. The repository uses
 * Jackson with Java time modules registered for consistent serialization.
 */
@Repository
public class PlanRepository {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final TypeReference<List<PlanFieldDefinition>> FIELD_LIST = new TypeReference<>() { };
    private static final TypeReference<List<PlanStep>> STEP_LIST = new TypeReference<>() { };
    private static final TypeReference<Map<String, Object>> VALUE_MAP = new TypeReference<>() { };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PlanRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper.findAndRegisterModules();
        ensureTables();
    }

    // ── PlanDefinition ──

    public Optional<PlanDefinition> findDefinition(String id) {
        if (!StringUtils.hasText(id)) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
            """
                select id, kind, status, title, summary, goal, notes,
                       deliverables_json, inputs_json, outputs_json, assumptions_json,
                       steps_json, validation_criteria_json,
                       execution_evidence_json, validation_feedback_json,
                       prompt_profile, planning_model, execution_model, settings_override_json,
                       planning_task, pending_questions_json, pending_question_index,
                       plan_start_message_order, final_message, conversation_id,
                       created_at, updated_at
                from plan_definitions
                where id = ?
                """,
            rs -> rs.next() ? Optional.of(definitionFromRow(rs)) : Optional.empty(),
            id
        );
    }

    public Optional<PlanDefinition> findDefinitionByConversationId(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
            """
                select id, kind, status, title, summary, goal, notes,
                       deliverables_json, inputs_json, outputs_json, assumptions_json,
                       steps_json, validation_criteria_json,
                       execution_evidence_json, validation_feedback_json,
                       prompt_profile, planning_model, execution_model, settings_override_json,
                       planning_task, pending_questions_json, pending_question_index,
                       plan_start_message_order, final_message, conversation_id,
                       created_at, updated_at
                from plan_definitions
                where conversation_id = ?
                """,
            rs -> rs.next() ? Optional.of(definitionFromRow(rs)) : Optional.empty(),
            conversationId
        );
    }

    public List<PlanDefinition> findAllDefinitions() {
        return jdbcTemplate.query(
            """
                select id, kind, status, title, summary, goal, notes,
                       deliverables_json, inputs_json, outputs_json, assumptions_json,
                       steps_json, validation_criteria_json,
                       execution_evidence_json, validation_feedback_json,
                       prompt_profile, planning_model, execution_model, settings_override_json,
                       planning_task, pending_questions_json, pending_question_index,
                       plan_start_message_order, final_message, conversation_id,
                       created_at, updated_at
                from plan_definitions
                order by updated_at desc, title asc
                """,
            (rs, rowNum) -> definitionFromRow(rs)
        );
    }

    public List<String> findConversationIds() {
        return jdbcTemplate.queryForList(
            "select id from plan_definitions where kind = 'SESSION_PLAN' order by updated_at desc",
            String.class
        );
    }

    public List<String> findDraftConversationIds() {
        return jdbcTemplate.queryForList(
            "select conversation_id from plan_definitions where kind = 'TASK_TEMPLATE' and conversation_id is not null order by updated_at desc",
            String.class
        );
    }

    @Transactional
    public PlanDefinition saveDefinition(PlanDefinition definition) {
        Instant createdAt = definition.createdAt() == null ? Instant.now() : definition.createdAt();
        Instant updatedAt = Instant.now();
        jdbcTemplate.update(
            """
                insert into plan_definitions (
                    id, kind, status, title, summary, goal, notes,
                    deliverables_json, inputs_json, outputs_json, assumptions_json,
                    steps_json, validation_criteria_json,
                    execution_evidence_json, validation_feedback_json,
                    prompt_profile, planning_model, execution_model, settings_override_json,
                    planning_task, pending_questions_json, pending_question_index,
                    plan_start_message_order, final_message, conversation_id,
                    created_at, updated_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict(id) do update set
                    kind = excluded.kind,
                    status = excluded.status,
                    title = excluded.title,
                    summary = excluded.summary,
                    goal = excluded.goal,
                    notes = excluded.notes,
                    deliverables_json = excluded.deliverables_json,
                    inputs_json = excluded.inputs_json,
                    outputs_json = excluded.outputs_json,
                    assumptions_json = excluded.assumptions_json,
                    steps_json = excluded.steps_json,
                    validation_criteria_json = excluded.validation_criteria_json,
                    execution_evidence_json = excluded.execution_evidence_json,
                    validation_feedback_json = excluded.validation_feedback_json,
                    prompt_profile = excluded.prompt_profile,
                    planning_model = excluded.planning_model,
                    execution_model = excluded.execution_model,
                    settings_override_json = excluded.settings_override_json,
                    planning_task = excluded.planning_task,
                    pending_questions_json = excluded.pending_questions_json,
                    pending_question_index = excluded.pending_question_index,
                    plan_start_message_order = excluded.plan_start_message_order,
                    final_message = excluded.final_message,
                    conversation_id = excluded.conversation_id,
                    updated_at = excluded.updated_at
                """,
            definition.id(),
            definition.kind().name(),
            definition.status().name(),
            definition.title(),
            definition.summary(),
            definition.goal(),
            definition.notes(),
            json(definition.deliverables()),
            json(definition.inputs()),
            json(definition.outputs()),
            json(definition.assumptions()),
            json(definition.steps()),
            json(definition.validationCriteria()),
            json(definition.executionEvidence()),
            json(definition.validationFeedback()),
            definition.promptProfile(),
            definition.planningModel(),
            definition.executionModel(),
            definition.settingsOverrideJson(),
            definition.planningTask(),
            jsonNullIfEmpty(definition.pendingQuestions()),
            definition.pendingQuestionIndex(),
            definition.planStartMessageOrder(),
            definition.finalMessage(),
            definition.conversationId(),
            createdAt.toString(),
            updatedAt.toString()
        );
        return new PlanDefinition(
            definition.id(), definition.kind(), definition.status(),
            definition.title(), definition.summary(), definition.goal(), definition.notes(),
            definition.deliverables(), definition.inputs(), definition.outputs(),
            definition.assumptions(), definition.steps(), definition.validationCriteria(),
            definition.executionEvidence(), definition.validationFeedback(),
            definition.promptProfile(), definition.planningModel(), definition.executionModel(),
            definition.settingsOverrideJson(), definition.planningTask(),
            definition.pendingQuestions(), definition.pendingQuestionIndex(),
            definition.planStartMessageOrder(), definition.finalMessage(),
            definition.conversationId(), createdAt, updatedAt
        );
    }

    @Transactional
    public void deleteDefinition(String id) {
        if (StringUtils.hasText(id)) {
            jdbcTemplate.update("delete from plan_runs where plan_id = ?", id);
            jdbcTemplate.update("delete from plan_definitions where id = ?", id);
        }
    }

    // ── PlanRun ──

    public Optional<PlanRun> findRun(String runId) {
        if (!StringUtils.hasText(runId)) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
            """
                select id, plan_id, run_display_name, status, input_values_json, output_values_json,
                       plan_snapshot_json, workspace_type, workspace_key, workspace_identity,
                       execution_evidence_json, validation_feedback_json,
                       deliverable_evidence_json, final_message, error_text,
                       created_at, updated_at, started_at, completed_at
                from plan_runs
                where id = ?
                """,
            rs -> rs.next() ? Optional.of(runFromRow(rs)) : Optional.empty(),
            runId
        );
    }

    public List<PlanRun> findRunsByPlanId(String planId) {
        return jdbcTemplate.query(
            """
                select id, plan_id, run_display_name, status, input_values_json, output_values_json,
                       plan_snapshot_json, workspace_type, workspace_key, workspace_identity,
                       execution_evidence_json, validation_feedback_json,
                       deliverable_evidence_json, final_message, error_text,
                       created_at, updated_at, started_at, completed_at
                from plan_runs
                where plan_id = ?
                order by created_at desc
                """,
            (rs, rowNum) -> runFromRow(rs),
            planId
        );
    }

    @Transactional
    public PlanRun saveRun(PlanRun run) {
        Instant createdAt = run.createdAt() == null ? Instant.now() : run.createdAt();
        Instant updatedAt = Instant.now();
        jdbcTemplate.update(
            """
                insert into plan_runs (
                    id, plan_id, run_display_name, status, input_values_json, output_values_json,
                    plan_snapshot_json, workspace_type, workspace_key, workspace_identity,
                    execution_evidence_json, validation_feedback_json,
                    deliverable_evidence_json, final_message, error_text,
                    created_at, updated_at, started_at, completed_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict(id) do update set
                    run_display_name = excluded.run_display_name,
                    status = excluded.status,
                    input_values_json = excluded.input_values_json,
                    output_values_json = excluded.output_values_json,
                    plan_snapshot_json = excluded.plan_snapshot_json,
                    workspace_type = excluded.workspace_type,
                    workspace_key = excluded.workspace_key,
                    workspace_identity = excluded.workspace_identity,
                    execution_evidence_json = excluded.execution_evidence_json,
                    validation_feedback_json = excluded.validation_feedback_json,
                    deliverable_evidence_json = excluded.deliverable_evidence_json,
                    final_message = excluded.final_message,
                    error_text = excluded.error_text,
                    updated_at = excluded.updated_at,
                    started_at = excluded.started_at,
                    completed_at = excluded.completed_at
                """,
            run.id(),
            run.planId(),
            run.runDisplayName(),
            run.status().name(),
            json(run.inputValues()),
            json(run.outputValues()),
            json(run.planSnapshot()),
            run.workspaceType(),
            run.workspaceKey(),
            run.workspaceIdentity(),
            json(run.executionEvidence()),
            json(run.validationFeedback()),
            json(run.deliverableEvidence()),
            run.finalMessage(),
            run.errorText(),
            createdAt.toString(),
            updatedAt.toString(),
            instant(run.startedAt()),
            instant(run.completedAt())
        );
        return new PlanRun(
            run.id(), run.planId(), run.runDisplayName(), run.status(), run.inputValues(), run.outputValues(),
            run.planSnapshot(), run.workspaceType(), run.workspaceKey(), run.workspaceIdentity(),
            run.executionEvidence(), run.validationFeedback(), run.deliverableEvidence(),
            run.finalMessage(), run.errorText(), createdAt, updatedAt,
            run.startedAt(), run.completedAt()
        );
    }

    // ── Row mapping ──

    private PlanDefinition definitionFromRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PlanDefinition(
            rs.getString("id"),
            PlanKind.valueOf(rs.getString("kind")),
            PlanStatus.valueOf(rs.getString("status")),
            rs.getString("title"),
            rs.getString("summary"),
            rs.getString("goal"),
            rs.getString("notes"),
            read(rs.getString("deliverables_json"), STRING_LIST, List.of()),
            read(rs.getString("inputs_json"), FIELD_LIST, List.of()),
            read(rs.getString("outputs_json"), FIELD_LIST, List.of()),
            read(rs.getString("assumptions_json"), STRING_LIST, List.of()),
            read(rs.getString("steps_json"), STEP_LIST, List.of()),
            read(rs.getString("validation_criteria_json"), STRING_LIST, List.of()),
            read(rs.getString("execution_evidence_json"), STRING_LIST, List.of()),
            read(rs.getString("validation_feedback_json"), STRING_LIST, List.of()),
            rs.getString("prompt_profile"),
            rs.getString("planning_model"),
            rs.getString("execution_model"),
            rs.getString("settings_override_json"),
            rs.getString("planning_task"),
            read(rs.getString("pending_questions_json"), STRING_LIST, List.of()),
            rs.getInt("pending_question_index"),
            rs.getInt("plan_start_message_order"),
            rs.getString("final_message"),
            rs.getString("conversation_id"),
            Instant.parse(rs.getString("created_at")),
            Instant.parse(rs.getString("updated_at"))
        );
    }

    private PlanRun runFromRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PlanRun(
            rs.getString("id"),
            rs.getString("plan_id"),
            rs.getString("run_display_name"),
            PlanRunStatus.valueOf(rs.getString("status")),
            read(rs.getString("input_values_json"), VALUE_MAP, Map.of()),
            read(rs.getString("output_values_json"), VALUE_MAP, Map.of()),
            readRequired(rs.getString("plan_snapshot_json"), PlanDefinition.class),
            getNullable(rs, "workspace_type"),
            getNullable(rs, "workspace_key"),
            getNullable(rs, "workspace_identity"),
            read(rs.getString("execution_evidence_json"), STRING_LIST, List.of()),
            read(rs.getString("validation_feedback_json"), STRING_LIST, List.of()),
            read(rs.getString("deliverable_evidence_json"), STRING_LIST, List.of()),
            rs.getString("final_message"),
            rs.getString("error_text"),
            Instant.parse(rs.getString("created_at")),
            Instant.parse(rs.getString("updated_at")),
            parseInstant(rs.getString("started_at")),
            parseInstant(rs.getString("completed_at"))
        );
    }

    // ── JSON helpers ──

    private <T> T read(String json, TypeReference<T> type, T defaultValue) {
        if (!StringUtils.hasText(json)) {
            return defaultValue;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse plan JSON", e);
        }
    }

    private <T> T readRequired(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse plan JSON", e);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize plan JSON", e);
        }
    }

    private String jsonNullIfEmpty(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list && list.isEmpty()) {
            return null;
        }
        if (value instanceof Map<?, ?> map && map.isEmpty()) {
            return null;
        }
        return json(value);
    }

    private String instant(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private Instant parseInstant(String value) {
        return StringUtils.hasText(value) ? Instant.parse(value) : null;
    }

    // ── Schema helpers ──

    private void addColumnIfMissing(String table, String column, String ddl) {
        if (!table.matches("[a-zA-Z0-9_]+") || !column.matches("[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("Unsupported table/column identifier");
        }
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from pragma_table_info(?) where name = ?",
            Integer.class,
            table,
            column
        );
        if (count != null && count == 0) {
            jdbcTemplate.execute(ddl);
        }
    }

    private String getNullable(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        try {
            return rs.getString(column);
        } catch (java.sql.SQLException ignored) {
            return null;
        }
    }

    // ── Schema bootstrapping ──

    private void ensureTables() {
        jdbcTemplate.execute("""
            create table if not exists plan_definitions (
                id text primary key,
                kind text not null,
                status text not null,
                title text not null,
                summary text,
                goal text,
                notes text,
                deliverables_json text not null,
                inputs_json text not null,
                outputs_json text not null,
                assumptions_json text not null,
                steps_json text not null,
                validation_criteria_json text not null,
                execution_evidence_json text not null,
                validation_feedback_json text not null,
                prompt_profile text,
                planning_model text,
                execution_model text,
                settings_override_json text,
                planning_task text,
                pending_questions_json text,
                pending_question_index integer not null default 0,
                plan_start_message_order integer not null default 0,
                final_message text,
                conversation_id text,
                created_at text not null,
                updated_at text not null
            )
            """);
        jdbcTemplate.execute("""
            create table if not exists plan_runs (
                id text primary key,
                plan_id text not null,
                run_display_name text,
                status text not null,
                input_values_json text not null,
                output_values_json text not null,
                plan_snapshot_json text not null,
                workspace_type text,
                workspace_key text,
                workspace_identity text,
                execution_evidence_json text not null,
                validation_feedback_json text not null,
                deliverable_evidence_json text not null,
                final_message text,
                error_text text,
                created_at text not null,
                updated_at text not null,
                started_at text,
                completed_at text,
                foreign key (plan_id) references plan_definitions(id) on delete cascade
            )
            """);
        addColumnIfMissing("plan_runs", "run_display_name",
            "alter table plan_runs add column run_display_name text");
        addColumnIfMissing("plan_runs", "workspace_type",
            "alter table plan_runs add column workspace_type text");
        addColumnIfMissing("plan_runs", "workspace_key",
            "alter table plan_runs add column workspace_key text");
        addColumnIfMissing("plan_runs", "workspace_identity",
            "alter table plan_runs add column workspace_identity text");
    }
}
