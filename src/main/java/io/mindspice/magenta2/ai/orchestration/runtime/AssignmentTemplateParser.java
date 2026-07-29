package io.mindspice.magenta2.ai.orchestration.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.util.StringUtils;

final class AssignmentTemplateParser {
    private AssignmentTemplateParser() {
    }

    static AssignmentRequest scheduleRequest(AgentSchedule schedule) {
        return request(
            schedule == null ? null : schedule.agentId(),
            schedule == null ? null : schedule.jobId(),
            schedule == null ? null : schedule.assignmentTemplate(),
            AssignmentType.JOB_RUN
        );
    }

    static AssignmentRequest reactionRequest(String reactionAgentId, Map<String, Object> template) {
        return request(reactionAgentId, null, template, AssignmentType.REPORT);
    }

    /**
     * Converts an automation template without granting it authority over the
     * server-owned executor, project, workspace, or lifecycle operation key.
     */
    static AssignmentRequest automationRequest(String agentId, String jobId, String projectId,
                                               String operationKey, Map<String, Object> template,
                                               AssignmentType defaultType) {
        Map<String, Object> values = template == null ? Map.of() : template;
        String templateProject = text(values.get("projectId"));
        if (StringUtils.hasText(templateProject) && !templateProject.equals(projectId)) {
            throw new IllegalArgumentException("automation template cannot override projectId");
        }
        if (StringUtils.hasText(text(values.get("agentId")))
                || StringUtils.hasText(text(values.get("workspaceType")))
                || StringUtils.hasText(text(values.get("workspaceKey")))
                || StringUtils.hasText(text(values.get("operationKey")))
                || StringUtils.hasText(text(values.get("contextBindingKey")))) {
            throw new IllegalArgumentException("automation template contains server-owned attribution");
        }
        Map<String, Object> input = input(values);
        String inputProject = text(input.get("projectId"));
        if (StringUtils.hasText(inputProject) && !inputProject.equals(projectId)) {
            throw new IllegalArgumentException("automation template input cannot override projectId");
        }
        AssignmentRequest request = new AssignmentRequest(
            agentId, jobId, null, assignmentType(values.get("assignmentType"), defaultType),
            null, integer(values.get("priority"), 0), text(values.get("modelOverride")),
            projectId, null, null, input, operationKey);
        validate(request);
        return request;
    }

    static void validate(AssignmentRequest request) {
        if (request.assignmentType() == null) {
            throw new IllegalArgumentException("assignmentType is required");
        }
        Map<String, Object> input = request.input() == null ? Map.of() : request.input();
        if (request.assignmentType() == AssignmentType.TASK_RUN && !StringUtils.hasText(text(input.get("taskId")))) {
            throw new IllegalArgumentException("TASK_RUN assignments require input.taskId");
        }
        if (request.assignmentType() == AssignmentType.WORKFLOW_RUN && !StringUtils.hasText(text(input.get("workflowId")))) {
            throw new IllegalArgumentException("WORKFLOW_RUN assignments require input.workflowId");
        }
        if (request.assignmentType() == AssignmentType.JOB_RUN
            && !StringUtils.hasText(request.jobId())
            && !StringUtils.hasText(text(input.get("jobId")))) {
            throw new IllegalArgumentException("JOB_RUN assignments require jobId");
        }
    }

    private static AssignmentRequest request(
        String fallbackAgentId,
        String fallbackJobId,
        Map<String, Object> template,
        AssignmentType defaultType
    ) {
        Map<String, Object> values = template == null ? Map.of() : template;
        Map<String, Object> input = input(values);
        AssignmentRequest request = new AssignmentRequest(
            firstText(text(values.get("agentId")), fallbackAgentId),
            firstText(text(values.get("jobId")), fallbackJobId),
            text(values.get("jobItemId")),
            assignmentType(values.get("assignmentType"), defaultType),
            integer(values.get("priority"), 0),
            text(values.get("modelOverride")),
            text(values.get("projectId")),
            text(values.get("workspaceType")),
            text(values.get("workspaceKey")),
            input
        );
        validate(request);
        return request;
    }

    private static AssignmentType assignmentType(Object value, AssignmentType fallback) {
        String name = text(value);
        if (!StringUtils.hasText(name)) {
            return fallback;
        }
        try {
            return AssignmentType.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid assignmentType");
        }
    }

    private static Map<String, Object> input(Map<String, Object> values) {
        Object input = values.get("input");
        if (input == null) {
            return Map.of();
        }
        if (!(input instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("assignmentTemplate.input must be a JSON object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private static Integer integer(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        return value instanceof Number number ? number.intValue() : Integer.parseInt(value.toString());
    }

    private static String firstText(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
