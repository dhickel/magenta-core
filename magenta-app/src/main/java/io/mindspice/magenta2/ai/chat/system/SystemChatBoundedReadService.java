package io.mindspice.magenta2.ai.chat.system;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta2.ai.chat.service.ChatInvocationProfile;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextService;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextService.ContextOutcome;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDescriptor;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceExplorerService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceExplorerService.RelativePath;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceKind;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.jdbc.core.JdbcTemplate;

/** Read-only, bounded, path-free facts for a currently validated system profile. */
final class SystemChatBoundedReadService {
    private static final int MAX_ITEMS = 50;
    private static final int MAX_DEPTH = 6;
    private static final int MAX_RESPONSE_BYTES = 8_192;

    private final JdbcTemplate jdbc;
    private final WorkspaceExplorerService explorer;
    private final WorkspaceDirectoryService directoryService;
    private final AgentContextService contextService;
    private final ObjectMapper objectMapper;

    SystemChatBoundedReadService(JdbcTemplate jdbc, WorkspaceExplorerService explorer,
                                 WorkspaceDirectoryService directoryService,
                                 AgentContextService contextService, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.explorer = Objects.requireNonNull(explorer);
        this.directoryService = Objects.requireNonNull(directoryService);
        this.contextService = Objects.requireNonNull(contextService);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    ToolCallback agentStatusCallback() {
        return new ReadCallback(ToolDefinition.builder().name("system_agent_status")
                .description("Read bounded, secret-free status facts for the bound agent.")
                .inputSchema("{}").build()) {
            @Override String read(ValidatedScope scope, Map<String, Object> ignored) {
                try {
                    Integer active = jdbc.queryForObject("""
                            select count(*) from work_assignments
                            where agent_id = ? and status in ('QUEUED', 'RUNNING')
                            """, Integer.class, scope.profile().agentId());
                    Long outputs = jdbc.queryForObject("""
                            select count(*) from output_publications
                            where workspace_kind = 'agent' and workspace_key = ?
                            """, Long.class, scope.profile().agentId());
                    return json(Map.of("active_runs", active == null ? 0 : active,
                            "output_artifacts", outputs == null ? 0 : outputs));
                } catch (RuntimeException exception) {
                    return unavailable();
                }
            }
        };
    }

    ToolCallback workspaceFileFactCallback() {
        return new ReadCallback(ToolDefinition.builder().name("system_workspace_file_fact")
                .description("Read bounded metadata facts for the bound agent workspace; file bytes and paths are never returned.")
                .inputSchema("""
                        {"type":"object","properties":{"path":{"type":"string"},"operation":{"enum":["list","inspect"]}},
                         "required":["path","operation"]}
                        """).build()) {
            @Override String read(ValidatedScope scope, Map<String, Object> input) {
                String requestedPath = string(input, "path");
                String operation = string(input, "operation");
                if (requestedPath == null || operation == null) {
                    return unavailable();
                }
                try {
                    RelativePath path = explorer.normalizeRelativePath(requestedPath);
                    if (depth(path) > MAX_DEPTH) {
                        return unavailable();
                    }
                    if ("list".equals(operation)) {
                        var entries = explorer.list(scope.descriptor(), path).entries().stream()
                                .limit(MAX_ITEMS)
                                .map(entry -> Map.of("type", entry.directory() ? "directory" : "file",
                                        "size", Math.max(0, entry.size())))
                                .toList();
                        return json(Map.of("items", entries, "count", entries.size()));
                    }
                    if ("inspect".equals(operation)) {
                        var entry = explorer.inspect(scope.descriptor(), path);
                        return json(Map.of("type", entry.directory() ? "directory" : "file",
                                "size", Math.max(0, entry.size()), "text", entry.regularFile()));
                    }
                } catch (RuntimeException exception) {
                    // Missing, escaped, linked, malformed, and unavailable all fail closed alike.
                }
                return unavailable();
            }
        };
    }

    ToolCallback assignmentFactCallback() {
        return new ReadCallback(ToolDefinition.builder().name("system_assignment_fact")
                .description("Read bounded lifecycle facts for assignments of the bound agent.")
                .inputSchema("""
                        {"type":"object","properties":{"assignment_id":{"type":"string"},"limit":{"type":"integer"}}}
                        """).build()) {
            @Override String read(ValidatedScope scope, Map<String, Object> input) {
                String assignmentId = string(input, "assignment_id");
                int limit = Math.clamp(integer(input, "limit", 20), 1, MAX_ITEMS);
                try {
                    String sql = assignmentId == null ? """
                            select id, assignment_type, status, priority
                            from work_assignments where agent_id = ? order by created_at desc limit ?
                            """ : """
                            select id, assignment_type, status, priority
                            from work_assignments where id = ? and agent_id = ?
                            """;
                    Object[] args = assignmentId == null
                            ? new Object[]{scope.profile().agentId(), limit}
                            : new Object[]{assignmentId, scope.profile().agentId()};
                    List<Map<String, Object>> rows = jdbc.query(sql, (rs, row) -> Map.<String, Object>of(
                            "id", rs.getString("id"), "type", rs.getString("assignment_type"),
                            "status", rs.getString("status"), "priority", rs.getInt("priority")), args);
                    return assignmentId == null ? json(Map.of("assignments", rows, "count", rows.size()))
                            : rows.isEmpty() ? unavailable() : json(rows.getFirst());
                } catch (RuntimeException exception) {
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
        ChatInvocationProfile.SystemChatProfile profile = SystemChatProfileHolder.current();
        if (profile == null) return null;
        try {
            WorkspaceDescriptor descriptor = directoryService.resolveForRead(WorkspaceKind.AGENT, profile.agentId());
            if (!descriptor.identity().equals(profile.agentDescriptor().identity())
                    || contextService.resolveBinding(descriptor, profile.bindingKey()).outcome() != ContextOutcome.AVAILABLE) {
                return null;
            }
            return new ValidatedScope(profile, descriptor);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private Map<String, Object> parse(String input) {
        try { return objectMapper.readValue(input, Map.class); }
        catch (Exception exception) { return Map.of(); }
    }
    private String json(Object value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            return json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= MAX_RESPONSE_BYTES ? json : unavailable();
        } catch (Exception exception) { return unavailable(); }
    }
    private String unavailable() { return "{\"unavailable\":true}"; }
    private static String string(Map<String, Object> values, String key) {
        return values.get(key) instanceof String value ? value : null;
    }
    private static int integer(Map<String, Object> values, String key, int defaultValue) {
        Object value = values.get(key);
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String text) try { return Integer.parseInt(text); } catch (NumberFormatException ignored) { }
        return defaultValue;
    }
    private static int depth(RelativePath path) { return ".".equals(path.value()) ? 0 : path.value().split("/").length; }

    private record ValidatedScope(ChatInvocationProfile.SystemChatProfile profile, WorkspaceDescriptor descriptor) { }
}
