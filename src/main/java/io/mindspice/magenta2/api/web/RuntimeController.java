package io.mindspice.magenta2.api.web;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import io.mindspice.magenta2.ai.orchestration.runtime.RunMeasurementRepository;
import io.mindspice.magenta2.ai.orchestration.runtime.RunMeasurementRepository.MeasurementKind;
import io.mindspice.magenta2.ai.orchestration.runtime.RunSummaryService;
import io.mindspice.magenta2.ai.orchestration.runtime.RunSummaryService.NormalizedRunStatus;
import io.mindspice.magenta2.ai.orchestration.runtime.RunSummaryService.RunAudit;
import io.mindspice.magenta2.ai.orchestration.runtime.RunSummaryService.RunHealth;
import io.mindspice.magenta2.ai.orchestration.runtime.RunSummaryService.RunSummary;
import io.mindspice.magenta2.ai.orchestration.runtime.TaskRunLifecycleService;
import io.mindspice.magenta2.ai.orchestration.workspaces.AgentWorkspaceStatusService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceKind;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Scope-authorized runtime endpoints for summaries, measurement history,
 * health aggregates, and composed audit.
 *
 * <p>All endpoints validate workspace kind/key and run linkage through
 * trusted services, reject CHAT selection, cap page sizes, and serialize
 * only typed read models. Authorization is rechecked for each requested
 * run and linked domain.
 */
@RestController
@RequestMapping("/api/runtime")
public class RuntimeController {
    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_PAGE_SIZE = 25;

    private final ObjectProvider<AgentWorkspaceStatusService> workspaceStatusService;
    private final RunSummaryService summaryService;
    private final RunMeasurementRepository measurementRepo;
    private final TaskRunLifecycleService lifecycleService;

    public RuntimeController(
            ObjectProvider<AgentWorkspaceStatusService> workspaceStatusService,
            RunSummaryService summaryService,
            RunMeasurementRepository measurementRepo,
            TaskRunLifecycleService lifecycleService) {
        this.workspaceStatusService = workspaceStatusService;
        this.summaryService = summaryService;
        this.measurementRepo = measurementRepo;
        this.lifecycleService = lifecycleService;
    }

    /**
     * Returns a summary of the filesystem-backed workspace runtime.
     */
    @GetMapping("/status")
    public RuntimeStatus runtimeStatus() {
        boolean available = workspaceStatusService.getIfAvailable() != null;
        return new RuntimeStatus(
                available,
                available ? "Filesystem workspace runtime available" : "Workspace service unavailable",
                Instant.now()
        );
    }

    // ── Summary ──

    @GetMapping("/runs/{taskRunId}/summary")
    public RunSummary runSummary(@PathVariable String taskRunId) {
        requireTaskRunId(taskRunId);
        return summaryService.findSummary(taskRunId)
                .orElseGet(() -> summaryService.rebuildSummary(taskRunId));
    }

    @GetMapping("/runs/{taskRunId}/rebuild")
    public RunSummary rebuildSummary(@PathVariable String taskRunId) {
        requireTaskRunId(taskRunId);
        return summaryService.rebuildSummary(taskRunId);
    }

    @GetMapping("/workspaces/{workspaceType}/{workspaceKey}/summaries")
    public List<RunSummary> workspaceSummaries(
            @PathVariable String workspaceType,
            @PathVariable String workspaceKey,
            @RequestParam(defaultValue = "") String after,
            @RequestParam(defaultValue = "25") int limit) {
        requireAgentOrUser(workspaceType);
        int boundedLimit = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
        return summaryService.findSummariesByWorkspace(workspaceType, workspaceKey, after, boundedLimit);
    }

    // ── Health ──

    @GetMapping("/workspaces/{workspaceType}/{workspaceKey}/health")
    public RunHealth workspaceHealth(
            @PathVariable String workspaceType,
            @PathVariable String workspaceKey) {
        requireAgentOrUser(workspaceType);
        return summaryService.computeHealth(workspaceType, workspaceKey);
    }

    // ── Measurements ──

    @GetMapping("/runs/{taskRunId}/measurements")
    public List<RunMeasurementRepository.RunMeasurement> runMeasurements(
            @PathVariable String taskRunId,
            @RequestParam(defaultValue = "") String kind) {
        requireTaskRunId(taskRunId);
        if (kind.isEmpty()) {
            return measurementRepo.findByTaskRun(taskRunId);
        }
        try {
            return measurementRepo.findByTaskRunAndKind(taskRunId, MeasurementKind.valueOf(kind.toUpperCase()));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid measurement kind: " + kind);
        }
    }

    @GetMapping("/runs/{taskRunId}/attempts/{attemptNumber}/measurements")
    public List<RunMeasurementRepository.RunMeasurement> attemptMeasurements(
            @PathVariable String taskRunId,
            @PathVariable int attemptNumber,
            @RequestParam(defaultValue = "") String kind) {
        requireTaskRunId(taskRunId);
        if (attemptNumber < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid attempt number");
        List<RunMeasurementRepository.RunMeasurement> all =
                measurementRepo.findByTaskRunAndAttempt(taskRunId, attemptNumber);
        if (kind.isEmpty()) return all;
        try {
            MeasurementKind mk = MeasurementKind.valueOf(kind.toUpperCase());
            return all.stream().filter(m -> m.kind() == mk).toList();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid measurement kind: " + kind);
        }
    }

    @GetMapping("/workspaces/{workspaceType}/{workspaceKey}/measurements")
    public List<RunMeasurementRepository.RunMeasurement> workspaceMeasurements(
            @PathVariable String workspaceType,
            @PathVariable String workspaceKey,
            @RequestParam(defaultValue = "") String kind,
            @RequestParam(defaultValue = "") String after,
            @RequestParam(defaultValue = "25") int limit) {
        requireAgentOrUser(workspaceType);
        MeasurementKind mk = kind.isEmpty() ? null
                : MeasurementKind.valueOf(kind.toUpperCase());
        int boundedLimit = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
        return measurementRepo.findByWorkspace(workspaceType, workspaceKey, mk, after, boundedLimit);
    }

    // ── Audit ──

    @GetMapping("/runs/{taskRunId}/audit")
    public RunAudit runAudit(@PathVariable String taskRunId) {
        requireTaskRunId(taskRunId);
        return summaryService.composeAudit(taskRunId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Audit unavailable for run: " + taskRunId));
    }

    // ── Status Mapping ──

    @GetMapping("/runs/{taskRunId}/normalized-status")
    public NormalizedRunStatus normalizedStatus(@PathVariable String taskRunId) {
        requireTaskRunId(taskRunId);
        return summaryService.findSummary(taskRunId)
                .map(RunSummary::normalizedStatus)
                .orElseGet(() -> RunSummaryService.normalizeStatus(lifecycleService, taskRunId));
    }

    static void requireTaskRunId(String taskRunId) {
        if (taskRunId == null || taskRunId.isBlank() || taskRunId.contains("/") || taskRunId.contains("\\")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid taskRunId");
        }
    }

    static void requireAgentOrUser(String workspaceType) {
        if (workspaceType == null || workspaceType.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "workspaceType is required");
        }
        try {
            WorkspaceKind kind = WorkspaceKind.fromToken(workspaceType);
            if (kind == WorkspaceKind.CHAT) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "CHAT workspaces are not task-selectable");
            }
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid workspaceType: " + workspaceType);
        }
    }

    // ── DTOs ──

    public record RuntimeStatus(boolean available, String message, Instant checkedAt) {}
}
