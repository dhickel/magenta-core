package io.mindspice.magenta2.ai.orchestration.workflow;

import io.mindspice.magenta2.ai.chat.plan.PlanDefinition;
import io.mindspice.magenta2.ai.chat.plan.PlanFieldDefinition;
import io.mindspice.magenta2.ai.chat.plan.PlanFieldType;
import io.mindspice.magenta2.ai.chat.plan.PlanService;
import io.mindspice.magenta2.ai.chat.plan.PlanStatus;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Strict workflow v2 compile validation.
 */
public class WorkflowValidator {

    private final PlanService planService;

    public WorkflowValidator(PlanService planService) {
        this.planService = planService;
    }

    public record ValidationResult(List<String> errors, List<String> warnings) {
        public ValidationResult {
            errors = errors == null ? List.of() : List.copyOf(errors);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        public boolean valid() {
            return errors.isEmpty();
        }
    }

    public ValidationResult validate(WorkflowDefinition definition) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        Map<String, WorkflowNode> nodesByKey = validateDraftShape(definition, errors);
        Map<String, PlanDefinition> taskPlans = resolveTaskPlans(definition, errors, warnings);

        validateRoutes(definition, nodesByKey, taskPlans, errors, warnings);
        validateCycles(definition, errors);
        validateExecutableStartPath(definition, nodesByKey, errors);
        validateRequiredTaskInputs(definition, taskPlans, errors);
        validateApprovalGateBranches(definition, nodesByKey, errors);

        return new ValidationResult(errors, warnings);
    }

    public ValidationResult validateDraft(WorkflowDefinition definition) {
        List<String> errors = new ArrayList<>();
        Map<String, WorkflowNode> nodesByKey = validateDraftShape(definition, errors);
        validateDraftRoutes(definition, nodesByKey, errors);
        validateCycles(definition, errors);
        return new ValidationResult(errors, List.of());
    }

    private Map<String, WorkflowNode> validateDraftShape(WorkflowDefinition definition, List<String> errors) {
        if (definition.schemaVersion() != WorkflowDefinition.CURRENT_SCHEMA_VERSION) {
            errors.add("Workflow schemaVersion must be 2 for v2 contract");
        }
        if (definition.maxConcurrency() < 1) {
            errors.add("workflow.maxConcurrency must be >= 1");
        }

        Map<String, WorkflowNode> nodesByKey = new LinkedHashMap<>();
        for (WorkflowNode node : definition.nodes()) {
            if (nodesByKey.putIfAbsent(node.key(), node) != null) {
                errors.add("Duplicate node key: '" + node.key() + "'");
            }
            if (!node.inputBindings().isEmpty()) {
                errors.add("Node '" + node.key() + "' uses legacy inputBindings; v2 requires explicit routes");
            }
        }
        return nodesByKey;
    }

    private void validateDraftRoutes(
        WorkflowDefinition definition,
        Map<String, WorkflowNode> nodesByKey,
        List<String> errors
    ) {
        Set<String> routeIds = new HashSet<>();
        for (WorkflowRoute route : definition.routes()) {
            if (!routeIds.add(route.id())) {
                errors.add("Duplicate route id: '" + route.id() + "'");
            }
            if (StringUtils.hasText(route.fromNodeKey()) && !nodesByKey.containsKey(route.fromNodeKey())) {
                errors.add("Route '" + route.id() + "': source node not found: '" + route.fromNodeKey() + "'");
            }
            if (!nodesByKey.containsKey(route.toNodeKey())) {
                errors.add("Route '" + route.id() + "': destination node not found: '" + route.toNodeKey() + "'");
            }
        }
    }

    private Map<String, PlanDefinition> resolveTaskPlans(
        WorkflowDefinition definition,
        List<String> errors,
        List<String> warnings
    ) {
        Map<String, PlanDefinition> taskPlans = new HashMap<>();
        for (WorkflowNode node : definition.nodes()) {
            if (node.type() != WorkflowNodeType.TASK) {
                continue;
            }
            if (!StringUtils.hasText(node.planId())) {
                errors.add("TASK node '" + node.key() + "' requires planId");
                continue;
            }
            try {
                PlanDefinition plan = planService.getTask(node.planId());
                if (plan.status() != PlanStatus.APPROVED) {
                    errors.add("TASK node '" + node.key() + "' must reference APPROVED task template: " + node.planId());
                }
                taskPlans.put(node.key(), plan);
            } catch (Exception e) {
                errors.add("TASK node '" + node.key() + "' references unknown task template: " + node.planId());
                warnings.add("Task template lookup failed for node '" + node.key() + "': " + e.getMessage());
            }
        }
        return taskPlans;
    }

    private void validateRoutes(
        WorkflowDefinition definition,
        Map<String, WorkflowNode> nodesByKey,
        Map<String, PlanDefinition> taskPlans,
        List<String> errors,
        List<String> warnings
    ) {
        Set<String> routeIds = new HashSet<>();
        Set<String> identities = new HashSet<>();

        for (WorkflowRoute route : definition.routes()) {
            if (!routeIds.add(route.id())) {
                errors.add("Duplicate route id: '" + route.id() + "'");
            }

            if (StringUtils.hasText(route.fromNodeKey()) && !nodesByKey.containsKey(route.fromNodeKey())) {
                errors.add("Route '" + route.id() + "': source node not found: '" + route.fromNodeKey() + "'");
                continue;
            }
            if (!nodesByKey.containsKey(route.toNodeKey())) {
                errors.add("Route '" + route.id() + "': destination node not found: '" + route.toNodeKey() + "'");
                continue;
            }

            String identity = String.join("::",
                String.valueOf(route.fromNodeKey()),
                String.valueOf(route.fromOutputName()),
                String.valueOf(route.toNodeKey()),
                String.valueOf(route.toInputName()),
                route.routeType().name(),
                String.valueOf(route.controlOutcome()));
            if (!identities.add(identity)) {
                errors.add("Duplicate route detected for route '" + route.id() + "'");
            }

            WorkflowNode source = StringUtils.hasText(route.fromNodeKey()) ? nodesByKey.get(route.fromNodeKey()) : null;
            WorkflowNode target = nodesByKey.get(route.toNodeKey());

            if (route.routeType() == WorkflowRouteType.CONTROL) {
                validateControlRoute(route, source, errors);
                continue;
            }

            if (!StringUtils.hasText(route.fromNodeKey())) {
                errors.add("Data route '" + route.id() + "' requires fromNodeKey");
                continue;
            }
            if (!StringUtils.hasText(route.sourcePort())) {
                errors.add("Data route '" + route.id() + "' requires source output port");
                continue;
            }
            if (!StringUtils.hasText(route.targetPort())) {
                errors.add("Data route '" + route.id() + "' requires target input port");
                continue;
            }

            PlanFieldType sourceType = resolveOutputType(source, route.sourcePort(), taskPlans);
            PlanFieldType targetType = resolveInputType(target, route.targetPort(), taskPlans);
            boolean strictSource = source != null && (source.type() == WorkflowNodeType.TASK || !source.outputPorts().isEmpty());
            boolean strictTarget = target != null && (target.type() == WorkflowNodeType.TASK || !target.inputPorts().isEmpty());

            if (sourceType == null && strictSource) {
                errors.add("Route '" + route.id() + "' references unknown source port '"
                    + route.sourcePort() + "' on node '" + route.fromNodeKey() + "'");
            } else if (sourceType == null) {
                warnings.add("Route '" + route.id() + "' source port '" + route.sourcePort()
                    + "' on node '" + route.fromNodeKey() + "' is not explicitly typed");
            }
            if (targetType == null && strictTarget) {
                errors.add("Route '" + route.id() + "' references unknown target port '"
                    + route.targetPort() + "' on node '" + route.toNodeKey() + "'");
            } else if (targetType == null) {
                warnings.add("Route '" + route.id() + "' target port '" + route.targetPort()
                    + "' on node '" + route.toNodeKey() + "' is not explicitly typed");
            }

            if (sourceType != null && targetType != null && sourceType != targetType) {
                errors.add("Route '" + route.id() + "' type mismatch: "
                    + route.fromNodeKey() + "." + route.sourcePort() + " is " + sourceType.wireName()
                    + " but " + route.toNodeKey() + "." + route.targetPort() + " expects " + targetType.wireName());
            }

            if (target != null && target.isGate()) {
                warnings.add("Route '" + route.id() + "' targets a gate node input; only control routes are typically expected for gates");
            }
        }
    }

    private void validateControlRoute(WorkflowRoute route, WorkflowNode source, List<String> errors) {
        if (!StringUtils.hasText(route.fromNodeKey())) {
            errors.add("Control route '" + route.id() + "' requires fromNodeKey");
            return;
        }
        if (source == null || !source.isGate()) {
            errors.add("Control route '" + route.id() + "' must originate from an approval gate node");
        }
        if (StringUtils.hasText(route.sourcePort()) || StringUtils.hasText(route.targetPort())) {
            errors.add("Control route '" + route.id() + "' must not define data ports");
        }
        String outcome = route.controlOutcome();
        if (!WorkflowRoute.OUTCOME_APPROVED.equals(outcome)
            && !WorkflowRoute.OUTCOME_REJECTED.equals(outcome)) {
            errors.add("Control route '" + route.id() + "' must define condition APPROVED or REJECTED");
        }
    }

    private PlanFieldType resolveOutputType(WorkflowNode node, String portName, Map<String, PlanDefinition> taskPlans) {
        if (node == null || !StringUtils.hasText(portName)) {
            return null;
        }
        if (node.type() == WorkflowNodeType.TASK) {
            PlanDefinition plan = taskPlans.get(node.key());
            if (plan == null) return null;
            return plan.outputs().stream()
                .filter(p -> p.name().equals(portName))
                .map(PlanFieldDefinition::type)
                .findFirst()
                .orElse(null);
        }
        return node.outputPorts().stream()
            .filter(p -> p.name().equals(portName))
            .map(WorkflowPort::type)
            .findFirst()
            .orElse(null);
    }

    private PlanFieldType resolveInputType(WorkflowNode node, String portName, Map<String, PlanDefinition> taskPlans) {
        if (node == null || !StringUtils.hasText(portName)) {
            return null;
        }
        if (node.type() == WorkflowNodeType.TASK) {
            PlanDefinition plan = taskPlans.get(node.key());
            if (plan == null) return null;
            return plan.inputs().stream()
                .filter(p -> p.name().equals(portName))
                .map(PlanFieldDefinition::type)
                .findFirst()
                .orElse(null);
        }
        return node.inputPorts().stream()
            .filter(p -> p.name().equals(portName))
            .map(WorkflowPort::type)
            .findFirst()
            .orElse(null);
    }

    private void validateCycles(WorkflowDefinition definition, List<String> errors) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> adjacency = new HashMap<>();
        for (WorkflowNode node : definition.nodes()) {
            indegree.put(node.key(), 0);
            adjacency.put(node.key(), new ArrayList<>());
        }

        for (WorkflowRoute route : definition.routes()) {
            if (!route.createsDependency() || !StringUtils.hasText(route.fromNodeKey())) {
                continue;
            }
            adjacency.computeIfAbsent(route.fromNodeKey(), k -> new ArrayList<>()).add(route.toNodeKey());
            indegree.compute(route.toNodeKey(), (k, v) -> (v == null ? 0 : v) + 1);
        }

        ArrayDeque<String> queue = new ArrayDeque<>();
        indegree.forEach((k, v) -> {
            if (v == 0) queue.add(k);
        });

        int visited = 0;
        while (!queue.isEmpty()) {
            String node = queue.removeFirst();
            visited++;
            for (String next : adjacency.getOrDefault(node, List.of())) {
                int d = indegree.compute(next, (k, v) -> (v == null ? 0 : v - 1));
                if (d == 0) queue.add(next);
            }
        }

        if (visited != definition.nodes().size()) {
            errors.add("Workflow contains a cycle; v2 graph must be a DAG");
        }
    }

    private void validateExecutableStartPath(
        WorkflowDefinition definition,
        Map<String, WorkflowNode> nodesByKey,
        List<String> errors
    ) {
        if (definition.nodes().isEmpty()) {
            errors.add("Workflow must contain at least one executable node before validation, submission, or run");
            return;
        }

        Set<String> dependencyTargets = new HashSet<>();
        Map<String, List<String>> dependencies = new HashMap<>();
        for (WorkflowRoute route : definition.routes()) {
            if (!route.createsDependency()
                || !StringUtils.hasText(route.fromNodeKey())
                || !nodesByKey.containsKey(route.fromNodeKey())
                || !nodesByKey.containsKey(route.toNodeKey())) {
                continue;
            }
            dependencyTargets.add(route.toNodeKey());
            dependencies.computeIfAbsent(route.fromNodeKey(), key -> new ArrayList<>()).add(route.toNodeKey());
        }

        List<String> startNodes = definition.nodes().stream()
            .map(WorkflowNode::key)
            .filter(key -> !dependencyTargets.contains(key))
            .toList();

        if (startNodes.isEmpty()) {
            errors.add("Workflow must have a start node with no incoming dependency routes");
            return;
        }
        if (startNodes.size() > 1) {
            errors.add("Workflow must have exactly one start node; found: " + String.join(", ", startNodes));
            return;
        }

        Set<String> reachable = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(startNodes.get(0));
        while (!queue.isEmpty()) {
            String nodeKey = queue.removeFirst();
            if (!reachable.add(nodeKey)) {
                continue;
            }
            for (String next : dependencies.getOrDefault(nodeKey, List.of())) {
                queue.add(next);
            }
        }

        List<String> unreachable = definition.nodes().stream()
            .map(WorkflowNode::key)
            .filter(key -> !reachable.contains(key))
            .toList();
        if (!unreachable.isEmpty()) {
            errors.add("Workflow contains nodes disconnected from the start path: " + String.join(", ", unreachable));
        }
    }

    private void validateRequiredTaskInputs(
        WorkflowDefinition definition,
        Map<String, PlanDefinition> taskPlans,
        List<String> errors
    ) {
        for (WorkflowNode node : definition.nodes()) {
            if (node.type() != WorkflowNodeType.TASK) {
                continue;
            }
            PlanDefinition plan = taskPlans.get(node.key());
            if (plan == null) {
                continue;
            }

            Set<String> satisfied = new HashSet<>();
            for (WorkflowRoute route : definition.incomingRoutes(node.key())) {
                if ((route.routeType() == WorkflowRouteType.MAP_OUTPUT
                    || route.routeType() == WorkflowRouteType.PASS_THROUGH)
                    && StringUtils.hasText(route.targetPort())) {
                    satisfied.add(route.targetPort());
                }
            }
            satisfied.addAll(node.config().keySet());

            for (PlanFieldDefinition input : plan.inputs()) {
                if (input.required() && !satisfied.contains(input.name())) {
                    errors.add("TASK node '" + node.key() + "': required input '"
                        + input.name() + "' is not satisfied by incoming routes or config");
                }
            }
        }
    }

    private void validateApprovalGateBranches(
        WorkflowDefinition definition,
        Map<String, WorkflowNode> nodesByKey,
        List<String> errors
    ) {
        for (WorkflowNode node : definition.nodes()) {
            if (!node.isGate()) {
                continue;
            }
            boolean approved = false;
            boolean rejected = false;
            for (WorkflowRoute route : definition.outgoingRoutes(node.key())) {
                if (route.routeType() != WorkflowRouteType.CONTROL) continue;
                String outcome = route.controlOutcome();
                if (WorkflowRoute.OUTCOME_APPROVED.equals(outcome)) {
                    approved = true;
                }
                if (WorkflowRoute.OUTCOME_REJECTED.equals(outcome)) {
                    rejected = true;
                }
            }
            if (!approved) {
                errors.add("Gate node '" + node.key() + "' is missing APPROVED control route");
            }
            if (!rejected) {
                errors.add("Gate node '" + node.key() + "' is missing REJECTED control route");
            }
        }
    }
}
