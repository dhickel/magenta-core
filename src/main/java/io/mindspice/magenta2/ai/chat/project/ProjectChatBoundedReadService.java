package io.mindspice.magenta2.ai.chat.project;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationService;
import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationService.FilesAuthorization;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectOperationsAccessService;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectOutputService;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectService;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Read-only, bounded, secret-free facts for a currently validated project
 * chat profile. Every result is scoped to the bound project and member agent.
 */
final class ProjectChatBoundedReadService {
    private static final int MAX_ITEMS = 50;
    private static final int MAX_RESPONSE_BYTES = 8_192;

    private final JdbcTemplate jdbc;
    private final AgentMaterializationService materializationService;
    private final ProjectService projectService;
    private final ProjectOutputService projectOutputService;
    private final ProjectOperationsAccessService projectAccessService;
    private final ObjectMapper objectMapper;

    ProjectChatBoundedReadService(JdbcTemplate jdbc,
                                  AgentMaterializationService materializationService,
                                  ProjectService projectService,
                                  ProjectOutputService projectOutputService,
                                  ProjectOperationsAccessService projectAccessService,
                                  ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.materializationService = Objects.requireNonNull(materializationService);
        this.projectService = Objects.requireNonNull(projectService);
        this.projectOutputService = Objects.requireNonNull(projectOutputService);
        this.projectAccessService = Objects.requireNonNull(projectAccessService);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    ToolCallback projectStatusCallback() {
        return new ReadCallback(ToolDefinition.builder()
                .name("project_project_status")
                .description("Read bounded project metadata: name, description, member count, active assignments count.")
                .inputSchema("{}").build()) {
            @Override String read(ValidatedScope scope, Map<String, Object> ignored) {
                try {
                    var project = projectService.getProject(scope.profile().projectId());
                    int memberCount = projectService.listMembers(scope.profile().projectId()).size();
                    Integer active = jdbc.queryForObject("""
                            select count(*) from work_assignments
                            where project_id = ? and status in ('QUEUED', 'RUNNING')
                            """, Integer.class, scope.profile().projectId());
                    return json(Map.of(
                            "name", project.name(),
                            "description", project.description() == null ? "" : project.description(),
                            "member_count", memberCount,
                            "active_assignments", active == null ? 0 : active));
                } catch (RuntimeException e) {
                    return unavailable();
                }
            }
        };
    }

    ToolCallback memberFactCallback() {
        return new ReadCallback(ToolDefinition.builder()
                .name("project_member_fact")
                .description("Read bounded membership facts: list members or read one member's role.")
                .inputSchema("""
                        {"type":"object","properties":{"agent_id":{"type":"string"}}}
                        """).build()) {
            @Override String read(ValidatedScope scope, Map<String, Object> input) {
                String agentId = string(input, "agent_id");
                try {
                    if (agentId != null) {
                        var members = projectService.listMembers(scope.profile().projectId()).stream()
                                .filter(m -> agentId.equals(m.agentId()))
                                .toList();
                        if (members.isEmpty()) return unavailable();
                        var m = members.getFirst();
                        return json(Map.of("agent_id", m.agentId(), "role", m.role()));
                    }
                    var members = projectService.listMembers(scope.profile().projectId()).stream()
                            .limit(MAX_ITEMS)
                            .map(m -> Map.<String, Object>of(
                                    "agent_id", m.agentId(),
                                    "role", m.role()))
                            .toList();
                    return json(Map.of("members", members, "count", members.size()));
                } catch (RuntimeException e) {
                    return unavailable();
                }
            }
        };
    }

    ToolCallback assignmentFactCallback() {
        return new ReadCallback(ToolDefinition.builder()
                .name("project_assignment_fact")
                .description("Read bounded lifecycle facts for project-scoped assignments.")
                .inputSchema("""
                        {"type":"object","properties":{"assignment_id":{"type":"string"},"limit":{"type":"integer"}}}
                        """).build()) {
            @Override String read(ValidatedScope scope, Map<String, Object> input) {
                String assignmentId = string(input, "assignment_id");
                int limit = Math.clamp(integer(input, "limit", 20), 1, MAX_ITEMS);
                try {
                    String sql = assignmentId == null ? """
                            select id, assignment_type, status, priority, agent_id
                            from work_assignments where project_id = ? order by created_at desc limit ?
                            """ : """
                            select id, assignment_type, status, priority, agent_id
                            from work_assignments where id = ? and project_id = ?
                            """;
                    Object[] args = assignmentId == null
                            ? new Object[]{scope.profile().projectId(), limit}
                            : new Object[]{assignmentId, scope.profile().projectId()};
                    List<Map<String, Object>> rows = jdbc.query(sql, (rs, row) -> Map.<String, Object>of(
                            "id", rs.getString("id"),
                            "type", rs.getString("assignment_type"),
                            "status", rs.getString("status"),
                            "priority", rs.getInt("priority"),
                            "agent_id", rs.getString("agent_id")), args);
                    return assignmentId == null
                            ? json(Map.of("assignments", rows, "count", rows.size()))
                            : rows.isEmpty() ? unavailable() : json(rows.getFirst());
                } catch (RuntimeException e) {
                    return unavailable();
                }
            }
        };
    }

    ToolCallback jobFactCallback() {
        return new ReadCallback(ToolDefinition.builder()
                .name("project_job_fact")
                .description("Read bounded job definition facts for this project.")
                .inputSchema("""
                        {"type":"object","properties":{"job_id":{"type":"string"},"limit":{"type":"integer"}}}
                        """).build()) {
            @Override String read(ValidatedScope scope, Map<String, Object> input) {
                String jobId = string(input, "job_id");
                int limit = Math.clamp(integer(input, "limit", 20), 1, MAX_ITEMS);
                try {
                    String sql = jobId == null ? """
                            select id, title, status
                            from job_definitions where project_id = ? order by updated_at desc limit ?
                            """ : """
                            select id, title, status
                            from job_definitions where id = ? and project_id = ?
                            """;
                    Object[] args = jobId == null
                            ? new Object[]{scope.profile().projectId(), limit}
                            : new Object[]{jobId, scope.profile().projectId()};
                    List<Map<String, Object>> rows = jdbc.query(sql, (rs, row) -> Map.<String, Object>of(
                            "id", rs.getString("id"),
                            "title", rs.getString("title"),
                            "status", rs.getString("status")), args);
                    return jobId == null
                            ? json(Map.of("jobs", rows, "count", rows.size()))
                            : rows.isEmpty() ? unavailable() : json(rows.getFirst());
                } catch (RuntimeException e) {
                    return unavailable();
                }
            }
        };
    }

    ToolCallback scheduleFactCallback() {
        return new ReadCallback(ToolDefinition.builder()
                .name("project_schedule_fact")
                .description("Read bounded schedule facts for this project.")
                .inputSchema("""
                        {"type":"object","properties":{"schedule_id":{"type":"string"},"limit":{"type":"integer"}}}
                        """).build()) {
            @Override String read(ValidatedScope scope, Map<String, Object> input) {
                String schedId = string(input, "schedule_id");
                int limit = Math.clamp(integer(input, "limit", 20), 1, MAX_ITEMS);
                try {
                    String sql = schedId == null ? """
                            select id, mode, enabled, deleted
                            from schedule_definitions
                            where project_id = ? and current_revision = 1
                            order by updated_at desc limit ?
                            """ : """
                            select id, mode, enabled, deleted
                            from schedule_definitions
                            where id = ? and project_id = ? and current_revision = 1
                            """;
                    Object[] args = schedId == null
                            ? new Object[]{scope.profile().projectId(), limit}
                            : new Object[]{schedId, scope.profile().projectId()};
                    List<Map<String, Object>> rows = jdbc.query(sql, (rs, row) -> Map.<String, Object>of(
                            "id", rs.getString("id"),
                            "mode", rs.getString("mode"),
                            "enabled", rs.getInt("enabled") != 0), args);
                    return schedId == null
                            ? json(Map.of("schedules", rows, "count", rows.size()))
                            : rows.isEmpty() ? unavailable() : json(rows.getFirst());
                } catch (RuntimeException e) {
                    return unavailable();
                }
            }
        };
    }

    ToolCallback reactionFactCallback() {
        return new ReadCallback(ToolDefinition.builder()
                .name("project_reaction_fact")
                .description("Read bounded event-reaction facts for this project.")
                .inputSchema("""
                        {"type":"object","properties":{"reaction_id":{"type":"string"},"limit":{"type":"integer"}}}
                        """).build()) {
            @Override String read(ValidatedScope scope, Map<String, Object> input) {
                String rxnId = string(input, "reaction_id");
                int limit = Math.clamp(integer(input, "limit", 20), 1, MAX_ITEMS);
                try {
                    String sql = rxnId == null ? """
                            select id, event_type, enabled, deleted
                            from reaction_definitions
                            where project_id = ? and current_revision = 1
                            order by updated_at desc limit ?
                            """ : """
                            select id, event_type, enabled, deleted
                            from reaction_definitions
                            where id = ? and project_id = ? and current_revision = 1
                            """;
                    Object[] args = rxnId == null
                            ? new Object[]{scope.profile().projectId(), limit}
                            : new Object[]{rxnId, scope.profile().projectId()};
                    List<Map<String, Object>> rows = jdbc.query(sql, (rs, row) -> Map.<String, Object>of(
                            "id", rs.getString("id"),
                            "event_type", rs.getString("event_type"),
                            "enabled", rs.getInt("enabled") != 0), args);
                    return rxnId == null
                            ? json(Map.of("reactions", rows, "count", rows.size()))
                            : rows.isEmpty() ? unavailable() : json(rows.getFirst());
                } catch (RuntimeException e) {
                    return unavailable();
                }
            }
        };
    }

    ToolCallback outputFactCallback() {
        return new ReadCallback(ToolDefinition.builder()
                .name("project_output_fact")
                .description("Read bounded output artifact facts. Returns count and limited metadata only; never returns file bytes or paths.")
                .inputSchema("""
                        {"type":"object","properties":{"limit":{"type":"integer"}}}
                        """).build()) {
            @Override String read(ValidatedScope scope, Map<String, Object> input) {
                int limit = Math.clamp(integer(input, "limit", 20), 1, MAX_ITEMS);
                try {
                    List<Map<String, Object>> rows = projectOutputService.listOutputs(
                                    scope.profile().projectId(), limit).stream()
                            .map(output -> Map.<String, Object>of(
                                    "name", output.logicalName() == null ? "" : output.logicalName(),
                                    "extension", output.fileExtension() == null ? "" : output.fileExtension(),
                                    "origin", output.origin() == null ? "" : output.origin()))
                            .toList();
                    return json(Map.of("outputs", rows, "count", rows.size()));
                } catch (RuntimeException e) {
                    return unavailable();
                }
            }
        };
    }

    ToolCallback transcriptFactCallback() {
        return new ReadCallback(ToolDefinition.builder()
                .name("project_transcript_fact")
                .description("Read bounded transcript/diagnostic facts for a project assignment. Never returns process output bytes or paths.")
                .inputSchema("""
                        {"type":"object","properties":{"assignment_id":{"type":"string"},"limit":{"type":"integer"}}}
                        """).build()) {
            @Override String read(ValidatedScope scope, Map<String, Object> input) {
                String assignmentId = string(input, "assignment_id");
                int limit = Math.clamp(integer(input, "limit", 20), 1, MAX_ITEMS);
                try {
                    if (assignmentId != null) {
                        var row = jdbc.query("""
                                select id, assignment_type, status, error_text, updated_at
                                from work_assignments
                                where id = ? and project_id = ?
                                """, rs -> {
                            if (!rs.next()) return null;
                            return Map.<String, Object>of(
                                    "id", rs.getString("id"),
                                    "type", rs.getString("assignment_type"),
                                    "status", rs.getString("status"),
                                    "has_error", rs.getString("error_text") != null,
                                    "updated_at", rs.getString("updated_at"));
                        }, assignmentId, scope.profile().projectId());
                        return row == null ? unavailable() : json(row);
                    }
                    List<Map<String, Object>> rows = jdbc.query("""
                            select id, assignment_type, status, error_text
                            from work_assignments
                            where project_id = ?
                            order by updated_at desc limit ?
                            """, (rs, row) -> Map.<String, Object>of(
                            "id", rs.getString("id"),
                            "type", rs.getString("assignment_type"),
                            "status", rs.getString("status"),
                            "has_error", rs.getString("error_text") != null),
                            scope.profile().projectId(), limit);
                    return json(Map.of("transcripts", rows, "count", rows.size()));
                } catch (RuntimeException e) {
                    return unavailable();
                }
            }
        };
    }

    private abstract class ReadCallback implements ToolCallback {
        private final ToolDefinition definition;
        ReadCallback(ToolDefinition definition) { this.definition = definition; }
        @Override public ToolDefinition getToolDefinition() { return definition; }
        @Override public final String call(String input) {
            ValidatedScope scope = validatedScope();
            return scope == null ? unavailable() : read(scope, parse(input));
        }
        abstract String read(ValidatedScope scope, Map<String, Object> input);
    }

    private ValidatedScope validatedScope() {
        ProjectChatProfile profile = ProjectChatProfileHolder.current();
        if (profile == null) return null;
        try {
            if (!projectAccessService.isTrustedDeployment()) {
                return null;
            }
            projectAccessService.requireActiveProjectWithMember(profile.projectId(), profile.agentId());
            FilesAuthorization authorization = materializationService.authorizeProjectFiles(
                    profile.agentId(), profile.projectId(), profile.materializationGeneration());
            if (!(authorization instanceof FilesAuthorization.Authorized authorized)
                    || !profile.materializationKey().equals(authorized.generation().projectKey())
                    || profile.materializationGeneration() != authorized.generation().generation()) {
                return null;
            }
            return new ValidatedScope(profile);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Map<String, Object> parse(String input) {
        try { return objectMapper.readValue(input, Map.class); }
        catch (Exception e) { return Map.of(); }
    }
    private String json(Object value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            return json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= MAX_RESPONSE_BYTES
                    ? json : unavailable();
        } catch (Exception e) { return unavailable(); }
    }
    private String unavailable() { return "{\"unavailable\":true}"; }
    private static String string(Map<String, Object> values, String key) {
        return values.get(key) instanceof String value ? value : null;
    }
    private static int integer(Map<String, Object> values, String key, int defaultValue) {
        Object value = values.get(key);
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String text) try { return Integer.parseInt(text); }
        catch (NumberFormatException ignored) { }
        return defaultValue;
    }

    private record ValidatedScope(ProjectChatProfile profile) { }
}
