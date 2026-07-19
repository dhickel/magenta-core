package io.mindspice.magenta2.ai.chat.tool.orchestration;

import java.time.Instant;
import java.util.List;

import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentType;
import io.mindspice.magenta2.ai.orchestration.runtime.JobRunStatus;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationStatus;

final class AgentOperationalToolResponses {
    private AgentOperationalToolResponses() {
    }

    record ToolResult(boolean ok, String message, Object data) {
    }

    record PagedListResult(int count, int limit, List<?> items) {
    }

    record WorkspaceStatusItem(
        String agentId,
        String workspaceRelativePath,
        String health,
        boolean exists,
        boolean writable,
        int activeRunCount,
        int activeLeaseCount,
        List<String> linkedProjectIds,
        long outputArtifactCount,
        long outputBytes,
        Instant lastActivityAt,
        String message
    ) {
    }

    record WorkspaceLinkItem(
        String id,
        String workspaceId,
        String label,
        String linkType,
        String target,
        boolean readable,
        boolean writable,
        Instant createdAt,
        Instant updatedAt
    ) {
    }

    record AssignmentItem(
        String id,
        String agentId,
        String jobId,
        String jobItemId,
        AssignmentType assignmentType,
        OrchestrationStatus status,
        int priority,
        String modelOverride,
        String workspaceId,
        String projectId,
        String effectiveWorkspaceId,
        String effectiveWorkspaceKind,
        String updatedAt
    ) {
    }

    record DiagnosticsItem(
        AssignmentItem assignment,
        Instant lastProgressAt,
        Instant lastHeartbeatAt,
        Long progressAgeSeconds,
        Long heartbeatAgeSeconds,
        boolean suspectedStuck,
        List<LinkedRunItem> linkedRuns,
        List<AuditEventItem> auditEvents,
        String conversationId,
        String buildCommit
    ) {
    }

    record LinkedRunItem(String type, String id, String parentId, String status, String errorText) {
    }

    record TranscriptItem(AssignmentItem assignment, List<String> conversationIds, List<AuditEventItem> events) {
    }

    record AuditEventItem(
        int sequence,
        String eventType,
        String messagePreview,
        String toolName,
        String toolStatus,
        String resultPreview,
        String errorType,
        String recordedAt
    ) {
    }

    record InboxItem(
        String id,
        String toAgentId,
        String fromId,
        String messageType,
        boolean read,
        boolean handled,
        Instant createdAt,
        Instant updatedAt,
        String bodyPreview
    ) {
    }

    record ScheduleItem(
        String id,
        String agentId,
        String jobId,
        String cronExpression,
        String timezone,
        boolean enabled,
        Instant nextRunAt,
        Instant updatedAt
    ) {
    }

    record JobItem(
        String id,
        String title,
        String ownerAgentId,
        String projectId,
        String workspaceId,
        String status,
        boolean persistentWorkspaceEnabled,
        int itemCount,
        Instant updatedAt
    ) {
    }

    record JobRunItem(
        String id,
        String jobId,
        String assignmentId,
        String workspaceId,
        JobRunStatus status,
        String workspacePath,
        String outputDir,
        Instant startedAt,
        Instant completedAt,
        Instant updatedAt
    ) {
    }

    record ProjectItem(
        String id,
        String name,
        String ownerAgentId,
        String model,
        Instant updatedAt
    ) {
    }

    record ProjectMemberItem(String id, String projectId, String agentId, String role, Instant joinedAt) {
    }

    record ProjectEventItem(String id, String projectId, String type, String payloadPreview, Instant createdAt) {
    }

    record ProjectWorkspaceItem(
        String workspaceId,
        String ownerAgentId,
        String rootKind,
        String displayPath,
        int linkCount,
        String leaseId,
        String leaseHolderAssignmentId,
        String mountedAgentId,
        boolean releaseRequested
    ) {
    }

    record OutputItem(
        String id,
        String runId,
        String planId,
        String agentId,
        String jobId,
        String jobAssignmentId,
        String jobRunId,
        String projectId,
        String workspaceId,
        String runType,
        String artifactType,
        String fileName,
        Instant createdAt
    ) {
    }

    record OutputContentItem(OutputItem artifact, int characters, String content) {
    }

    record AgentItem(
        String id,
        String name,
        String status,
        String defaultModel,
        boolean directLineEnabled,
        int approvedToolCount,
        WorkspaceStatusItem workspace
    ) {
    }
}
