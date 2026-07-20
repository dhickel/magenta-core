package io.mindspice.magenta2.api.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.mindspice.magenta2.ai.skills.AgentSkill;
import io.mindspice.magenta2.ai.skills.AgentSkillActivationService;
import io.mindspice.magenta2.ai.skills.AgentSkillAssignment;
import io.mindspice.magenta2.ai.skills.AgentSkillAssignmentRepository;
import io.mindspice.magenta2.ai.skills.AgentSkillCatalogEntry;
import io.mindspice.magenta2.ai.skills.AgentSkillManagementService;
import io.mindspice.magenta2.ai.skills.AgentSkillManagementService.SkillApiException;
import io.mindspice.magenta2.ai.skills.AgentSkillManagementService.SkillCatalog;
import io.mindspice.magenta2.ai.skills.AgentSkillManagementService.SkillFileEntry;
import io.mindspice.magenta2.ai.skills.AgentSkillManagementService.SkillFileTree;
import io.mindspice.magenta2.ai.skills.AgentSkillManagementService.SkillFileView;
import io.mindspice.magenta2.ai.skills.CandidateVersion;
import io.mindspice.magenta2.ai.skills.ResolvedSkill;
import io.mindspice.magenta2.ai.skills.SkillSnapshotRepository;
import io.mindspice.magenta2.ai.skills.SkillTrustService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/skills")
public class SkillController {
    private final AgentSkillManagementService managementService;
    private final SkillTrustService trustService;
    private final SkillSnapshotRepository snapshotRepository;
    private final AgentSkillActivationService activationService;
    private final AgentSkillAssignmentRepository assignmentRepository;

    public SkillController(AgentSkillManagementService managementService,
                           SkillTrustService trustService,
                           SkillSnapshotRepository snapshotRepository,
                           AgentSkillActivationService activationService,
                           AgentSkillAssignmentRepository assignmentRepository) {
        this.managementService = managementService;
        this.trustService = trustService;
        this.snapshotRepository = snapshotRepository;
        this.activationService = activationService;
        this.assignmentRepository = assignmentRepository;
    }

    // ── Existing endpoints (preserved) ──

    @GetMapping
    public SkillCatalog listSkills() {
        return managementService.listSkills();
    }

    @PostMapping("/refresh")
    public SkillCatalog refreshSkills() {
        return managementService.refreshSkills();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AgentSkill createSkill(@RequestBody CreateSkillRequest request) {
        return managementService.createSkill(request.skillName(), request.description());
    }

    @GetMapping("/{skillName}")
    public AgentSkill skillDetail(@PathVariable String skillName) {
        return managementService.getSkill(skillName);
    }

    @GetMapping("/{skillName}/diagnostics")
    public SkillDiagnosticsResponse diagnostics(@PathVariable String skillName) {
        return new SkillDiagnosticsResponse(managementService.diagnostics(skillName));
    }

    @GetMapping("/{skillName}/files")
    public SkillFileTree fileTree(
        @PathVariable String skillName,
        @RequestParam(defaultValue = ".") String path
    ) {
        return managementService.listFiles(skillName, path);
    }

    @GetMapping("/{skillName}/files/view")
    public SkillFileView fileView(
        @PathVariable String skillName,
        @RequestParam String path
    ) {
        return managementService.viewFile(skillName, path);
    }

    @PutMapping("/{skillName}/files/text")
    public SkillFileView saveTextFile(
        @PathVariable String skillName,
        @RequestParam String path,
        @RequestBody TextSaveRequest request
    ) {
        return managementService.saveText(skillName, path, request.content());
    }

    @PostMapping("/{skillName}/files")
    public SkillFileEntry createTextFile(
        @PathVariable String skillName,
        @RequestBody CreateTextFileRequest request
    ) {
        return managementService.createTextFile(
            skillName,
            request.parentPath(),
            request.fileName(),
            request.content()
        );
    }

    @GetMapping("/{skillName}/assignments")
    public SkillAssignmentsResponse listAssignments(@PathVariable String skillName) {
        return new SkillAssignmentsResponse(managementService.listAgentAssignments(skillName));
    }

    @PostMapping("/{skillName}/assignments/agents/{agentId}")
    public AgentSkillAssignment assignAgent(
        @PathVariable String skillName,
        @PathVariable String agentId,
        @RequestParam(defaultValue = "true") boolean enabled
    ) {
        return managementService.assignToAgent(skillName, agentId, enabled);
    }

    @DeleteMapping("/{skillName}/assignments/agents/{agentId}")
    public Map<String, Object> unassignAgent(
        @PathVariable String skillName,
        @PathVariable String agentId
    ) {
        managementService.unassignFromAgent(skillName, agentId);
        return Map.of("removed", true);
    }

    // ── Trust / approval endpoints ──

    @GetMapping("/candidates")
    public List<CandidateInfo> listCandidates() {
        return assignmentRepository.findAllCandidates().stream()
            .map(this::candidateInfo)
            .toList();
    }

    @GetMapping("/candidates/{logicalKey}")
    public List<CandidateInfo> candidatesForKey(@PathVariable String logicalKey) {
        return assignmentRepository.findCandidatesByLogicalKey(logicalKey).stream()
            .map(this::candidateInfo)
            .toList();
    }

    @PostMapping("/trust/approve")
    public Map<String, Object> approveSkill(@RequestBody ApproveRequest request) {
        String operationKey = request.operationKey() != null ? request.operationKey()
            : UUID.randomUUID().toString();
        var result = trustService.approve(operationKey, request.candidateVersionId(),
            request.observedDigest(), request.actor(), request.reason());
        return Map.of("operationKey", operationKey,
            "decision", result.decision(),
            "snapshotId", result.snapshotId(),
            "outcome", result.outcome(),
            "decidedAt", result.decidedAt().toString());
    }

    @PostMapping("/trust/reject")
    public Map<String, Object> rejectSkill(@RequestBody RejectRequest request) {
        String operationKey = request.operationKey() != null ? request.operationKey()
            : UUID.randomUUID().toString();
        var result = trustService.reject(operationKey, request.candidateVersionId(),
            request.actor(), request.reason());
        return Map.of("operationKey", operationKey,
            "decision", result.decision(),
            "decidedAt", result.decidedAt().toString());
    }

    @PostMapping("/trust/revoke")
    public Map<String, Object> revokeSkill(@RequestBody RevokeRequest request) {
        String operationKey = request.operationKey() != null ? request.operationKey()
            : UUID.randomUUID().toString();
        var result = trustService.revoke(operationKey, request.logicalKey(),
            request.actor(), request.reason());
        return Map.of("operationKey", operationKey,
            "decision", result.decision(),
            "decidedAt", result.decidedAt().toString());
    }

    @GetMapping("/trust/decisions/{logicalKey}")
    public List<Map<String, Object>> trustHistory(@PathVariable String logicalKey) {
        List<Map<String, Object>> result = new ArrayList<>();
        List<CandidateVersion> candidates = assignmentRepository.findCandidatesByLogicalKey(logicalKey);
        for (CandidateVersion cv : candidates) {
            var decisions = snapshotRepository.findDecisionsByCandidate(cv.id());
            for (var d : decisions) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", d.id());
                entry.put("candidateVersionId", d.candidateVersionId());
                entry.put("decision", d.decision());
                entry.put("snapshotId", d.snapshotId());
                entry.put("actor", d.actor());
                entry.put("reason", d.reason());
                entry.put("scope", d.scope());
                entry.put("decidedAt", d.decidedAt().toString());
                result.add(entry);
            }
        }
        return result;
    }

    // ── Snapshot endpoints ──

    @GetMapping("/snapshots")
    public List<Map<String, Object>> listSnapshots() {
        return snapshotRepository.findAllSnapshots().stream()
            .map(this::snapshotInfo)
            .toList();
    }

    @GetMapping("/snapshots/{logicalKey}")
    public List<Map<String, Object>> snapshotsForKey(@PathVariable String logicalKey) {
        return snapshotRepository.findByLogicalKey(logicalKey).stream()
            .map(this::snapshotInfo)
            .toList();
    }

    @GetMapping("/snapshots/{snapshotId}/resources")
    public List<Map<String, Object>> snapshotResources(@PathVariable Long snapshotId) {
        List<Map<String, Object>> result = new ArrayList<>();
        var resources = snapshotRepository.findResourcesBySnapshotId(snapshotId);
        for (var r : resources) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", r.id());
            entry.put("relativePath", r.relativePath());
            entry.put("resourceType", r.resourceType());
            entry.put("contentLength", r.contentLength());
            entry.put("contentDigest", r.contentDigest());
            result.add(entry);
        }
        return result;
    }

    // ── Activation endpoints ──

    @PostMapping("/activate")
    public Map<String, Object> activateSkill(@RequestBody ActivateRequest request) {
        var result = activationService.activate(request.skillName(), request.agentId(),
            request.taskRunId(), request.conversationId());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("skillName", result.requestedName());
        response.put("activatedName", result.activatedName());
        response.put("snapshotDigest", result.snapshotDigest());
        response.put("status", result.status());
        response.put("isActivated", result.isActivated());
        if (result.isActivated()) {
            response.put("content", result.content());
            response.put("resourceCount", result.resources().size());
        }
        return response;
    }

    @GetMapping("/activations/{taskRunId}")
    public List<Map<String, Object>> activationsForTaskRun(@PathVariable String taskRunId) {
        return snapshotRepository.findActivationsByTaskRun(taskRunId).stream()
            .map(activation -> {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", activation.id());
                entry.put("snapshotId", activation.snapshotId());
                entry.put("logicalKey", activation.logicalKey());
                entry.put("taskRunId", activation.taskRunId());
                entry.put("conversationId", activation.conversationId());
                entry.put("agentId", activation.agentId());
                entry.put("activationContext", activation.activationContext());
                entry.put("activatedAt", activation.activatedAt().toString());
                return entry;
            })
            .toList();
    }

    // ── Executable authorization endpoints ──

    @GetMapping("/exec-auth/{logicalKey}")
    public Map<String, Object> executableAuthorizationStatus(@PathVariable String logicalKey) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("logicalKey", logicalKey);
        result.put("confinementAvailable", false); // Phase 03 default: disabled
        result.put("message", "Executable resource authorization requires proven process confinement (Phase 03)");
        return result;
    }

    // ── Package endpoints ──

    @PostMapping("/packages/ingest")
    public ResponseEntity<Map<String, Object>> ingestPackage() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
            "status", "UNAVAILABLE",
            "confinementAvailable", false,
            "message", "Package ingestion is disabled until process confinement is proven"));
    }

    // ── Catalog / disclosure ──

    @GetMapping("/catalog/approved")
    public List<AgentSkillCatalogEntry> approvedCatalog() {
        return activationService.approvedCatalog();
    }

    // ── Exact assignment overlay management ──

    @PostMapping("/overlays")
    public Map<String, Object> upsertOverlay(@RequestBody OverlayRequest request) {
        String operationKey = request.operationKey() != null ? request.operationKey()
            : UUID.randomUUID().toString();
        var overlay = assignmentRepository.upsertOverlay(
            request.candidateVersionId(), request.logicalKey(),
            request.targetType(), request.targetKey(), request.enabled(),
            operationKey, request.actor());
        return Map.of("id", overlay.id(),
            "candidateVersionId", overlay.candidateVersionId(),
            "logicalKey", overlay.logicalKey(),
            "targetType", overlay.targetType().name(),
            "targetKey", overlay.targetKey(),
            "enabled", overlay.enabled(),
            "createdAt", overlay.createdAt().toString());
    }

    @GetMapping("/overlays")
    public List<Map<String, Object>> listOverlays() {
        return assignmentRepository.findAllOverlays().stream()
            .map(this::overlayInfo)
            .toList();
    }

    @GetMapping("/overlays/{logicalKey}")
    public List<Map<String, Object>> overlaysForKey(@PathVariable String logicalKey) {
        return assignmentRepository.findOverlaysByLogicalKey(logicalKey).stream()
            .map(this::overlayInfo)
            .toList();
    }

    private CandidateInfo candidateInfo(CandidateVersion candidate) {
        return new CandidateInfo(candidate.id(), candidate.logicalKey(), candidate.scopeType().name(),
            candidate.provenance().name(), candidate.contentDigest(),
            snapshotRepository.snapshotExistsForCandidate(candidate.id()),
            snapshotRepository.isCurrentlyApproved(candidate.logicalKey()), candidate.createdAt());
    }

    private Map<String, Object> snapshotInfo(SkillSnapshotRepository.SkillSnapshot snapshot) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", snapshot.id());
        entry.put("candidateVersionId", snapshot.candidateVersionId());
        entry.put("logicalKey", snapshot.logicalKey());
        entry.put("snapshotDigest", snapshot.snapshotDigest());
        entry.put("skillName", snapshot.skillName());
        entry.put("description", snapshot.description());
        entry.put("createdAt", snapshot.createdAt().toString());
        return entry;
    }

    private Map<String, Object> overlayInfo(ResolvedSkill.ExactAssignmentOverlay overlay) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", overlay.id());
        entry.put("candidateVersionId", overlay.candidateVersionId());
        entry.put("logicalKey", overlay.logicalKey());
        entry.put("targetType", overlay.targetType().name());
        entry.put("targetKey", overlay.targetKey());
        entry.put("enabled", overlay.enabled());
        entry.put("operationKey", overlay.operationKey());
        entry.put("actor", overlay.actor());
        entry.put("createdAt", overlay.createdAt().toString());
        entry.put("updatedAt", overlay.updatedAt().toString());
        return entry;
    }

    // ── Exception handling ──

    @ExceptionHandler(SkillApiException.class)
    public ResponseEntity<Map<String, String>> onSkillApiException(SkillApiException exception) {
        return ResponseEntity.status(exception.status())
            .body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> onIllegalArgument(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> onIllegalState(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(Map.of("error", exception.getMessage()));
    }

    // ── Request/Response types ──

    public record CreateSkillRequest(String skillName, String description) { }
    public record TextSaveRequest(String content) { }
    public record CreateTextFileRequest(String parentPath, String fileName, String content) { }
    public record SkillDiagnosticsResponse(List<io.mindspice.magenta2.ai.skills.AgentSkillDiagnostic> diagnostics) { }
    public record SkillAssignmentsResponse(List<AgentSkillAssignment> assignments) { }

    public record ApproveRequest(Long candidateVersionId, String observedDigest,
                                  String operationKey, String actor, String reason) { }
    public record RejectRequest(Long candidateVersionId, String operationKey,
                                 String actor, String reason) { }
    public record RevokeRequest(String logicalKey, String operationKey,
                                 String actor, String reason) { }
    public record ActivateRequest(String skillName, String agentId, String taskRunId,
                                    String conversationId) { }
    public record OverlayRequest(Long candidateVersionId, String logicalKey,
                                  io.mindspice.magenta2.ai.skills.AgentSkillTargetType targetType,
                                  String targetKey, boolean enabled,
                                  String operationKey, String actor) { }

    public record CandidateInfo(Long id, String logicalKey, String scopeType,
                                 String provenance, String contentDigest,
                                 boolean hasSnapshot, boolean isApproved,
                                 java.time.Instant createdAt) { }
}
