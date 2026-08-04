package io.mindspice.magenta2.ai.orchestration.workflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Workflow v2 definition with explicit graph wiring and typed node ports.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowDefinition(
    String id,
    int schemaVersion,
    String title,
    String summary,
    int maxConcurrency,
    List<WorkflowNode> nodes,
    List<WorkflowRoute> routes,
    Map<String, Object> uiLayout,
    Instant createdAt,
    Instant updatedAt
) {
    public static final int CURRENT_SCHEMA_VERSION = 2;
    public static final int DEFAULT_MAX_CONCURRENCY = 4;

    public WorkflowDefinition {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Workflow definition title must not be blank");
        }
        schemaVersion = schemaVersion <= 0 ? CURRENT_SCHEMA_VERSION : schemaVersion;
        maxConcurrency = maxConcurrency <= 0 ? DEFAULT_MAX_CONCURRENCY : maxConcurrency;
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        routes = routes == null ? List.of() : List.copyOf(routes);
        uiLayout = uiLayout == null ? Map.of() : Map.copyOf(uiLayout);
    }

    public WorkflowNode nodeByKey(String key) {
        return nodes.stream()
            .filter(n -> n.key().equals(key))
            .findFirst()
            .orElse(null);
    }

    public WorkflowRoute routeById(String routeId) {
        return routes.stream()
            .filter(r -> r.id().equals(routeId))
            .findFirst()
            .orElse(null);
    }

    public List<WorkflowRoute> incomingRoutes(String nodeKey) {
        return routes.stream()
            .filter(r -> r.toNodeKey().equals(nodeKey))
            .toList();
    }

    public List<WorkflowRoute> outgoingRoutes(String nodeKey) {
        return routes.stream()
            .filter(r -> nodeKey.equals(r.fromNodeKey()))
            .toList();
    }
}
