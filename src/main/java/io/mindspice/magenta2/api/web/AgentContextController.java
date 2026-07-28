package io.mindspice.magenta2.api.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.ContextBinding;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.ContextDefault;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.PersonalSelection;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextService;
import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationRepository;
import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationRepository.MaterializationKey;
import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationRepository.MaterializationRecord;
import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationRepository.MaterializationState;
import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDescriptor;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceKind;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Thin agent-scoped REST and HTMX adapter for captured context,
 * materializations, defaults, and immutable bindings. Never resolves
 * filesystem roots, constructs descriptors, infers bindings, or
 * implements authorization.
 */
@Controller
@RequestMapping("/api/agents/{agentId}/context")
class AgentContextController {
    private static final Logger log = LoggerFactory.getLogger(AgentContextController.class);

    private final AgentProfileService agentProfileService;
    private final AgentContextService contextService;
    private final AgentMaterializationService materializationService;
    private final AgentMaterializationRepository materializationRepository;
    private final WorkspaceDirectoryService directoryService;

    AgentContextController(AgentProfileService agentProfileService,
                           AgentContextService contextService,
                           AgentMaterializationService materializationService,
                           AgentMaterializationRepository materializationRepository,
                           WorkspaceDirectoryService directoryService) {
        this.agentProfileService = agentProfileService;
        this.contextService = contextService;
        this.materializationService = materializationService;
        this.materializationRepository = materializationRepository;
        this.directoryService = directoryService;
    }

    // ── JSON summary ──

    @GetMapping(produces = "application/json")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> summary(@PathVariable String agentId) {
        if (!agentExists(agentId)) return notFound();
        var defaultRecord = contextService.getDefault(agentId);
        var materializations = materializationRepository.findByAgent(agentId);
        return ResponseEntity.ok(Map.of(
                "agentId", agentId,
                "default", defaultRecord.outcome().name(),
                "defaultRecord", defaultRecord.value() != null ? Map.of(
                        "version", defaultRecord.value().version(),
                        "selectionKind", defaultRecord.value().selectionKind().name(),
                        "materializationKey", defaultRecord.value().materializationKey() != null
                                ? defaultRecord.value().materializationKey().serialized() : null,
                        "materializationGeneration", defaultRecord.value().materializationGeneration(),
                        "personalSelection", defaultRecord.value().personalSelection() != null
                                ? defaultRecord.value().personalSelection().name() : null
                ) : null,
                "materializationCount", materializations.size()
        ));
    }

    // ── HTMX panel ──

    @GetMapping("/panel")
    @ResponseBody
    public String contextPanel(@PathVariable String agentId, HttpServletResponse response) {
        if (!agentExists(agentId)) {
            response.setStatus(404);
            return AgentContextFragments.errorPanel(encode(agentId), "Not Found", "Agent not found.");
        }
        try {
            var defaultRecord = contextService.getDefault(agentId);
            var materializations = materializationRepository.findByAgentAndState(agentId, MaterializationState.READY);
            // Bindings are looked up individually; provide empty list for summary view
            return AgentContextFragments.contextSummary(encode(agentId),
                    defaultRecord.value(),
                    List.of(),
                    materializations,
                    null);
        } catch (RuntimeException e) {
            log.debug("Context panel unavailable for agent {}", agentId, e);
            response.setStatus(503);
            return AgentContextFragments.errorPanel(encode(agentId), "Unavailable",
                    "Context data is temporarily unavailable.");
        }
    }

    // ── Defaults ──

    @GetMapping(path = "/defaults", produces = "application/json")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getDefault(@PathVariable String agentId) {
        if (!agentExists(agentId)) return notFound();
        var defaultRecord = contextService.getDefault(agentId);
        if (defaultRecord.value() == null) {
            return ResponseEntity.ok(Map.of("agentId", agentId, "available", false));
        }
        ContextDefault d = defaultRecord.value();
        return ResponseEntity.ok(Map.of(
                "agentId", agentId,
                "available", true,
                "version", d.version(),
                "selectionKind", d.selectionKind().name(),
                "materializationKey", d.materializationKey() != null ? d.materializationKey().serialized() : null,
                "materializationGeneration", d.materializationGeneration(),
                "personalSelection", d.personalSelection() != null ? d.personalSelection().name() : null,
                "reason", d.reason()
        ));
    }

    @PutMapping("/defaults")
    @ResponseBody
    public String updateDefault(@PathVariable String agentId,
                                @RequestParam int expectedVersion,
                                @RequestParam String selectionKind,
                                @RequestParam(required = false) String materializationKey,
                                @RequestParam(required = false, defaultValue = "0") int materializationGeneration,
                                @RequestParam(required = false) String personalSelection,
                                @RequestParam String reason,
                                @RequestParam(required = false) String operationKey,
                                HttpServletResponse response) {
        if (!agentExists(agentId)) {
            response.setStatus(404);
            return AgentContextFragments.errorPanel(encode(agentId), "Not Found", "Agent not found.");
        }
        try {
            if ("MATERIALIZATION".equalsIgnoreCase(selectionKind)) {
                if (!StringUtils.hasText(materializationKey)) {
                    response.setStatus(400);
                    return AgentContextFragments.defaultEditor(encode(agentId), null,
                            materializationRepository.findByAgentAndState(agentId, MaterializationState.READY),
                            "Materialization key is required for MATERIALIZATION kind.", true);
                }
                MaterializationKey key = MaterializationKey.fromSerialized(agentId, materializationKey);
                try {
                    contextService.updateDefault(agentId, expectedVersion, key,
                            materializationGeneration, reason);
                } catch (IllegalStateException e) {
                    response.setStatus(409);
                    return AgentContextFragments.defaultEditor(encode(agentId),
                            contextService.getDefault(agentId).value(),
                            materializationRepository.findByAgentAndState(agentId, MaterializationState.READY),
                            "Conflict: " + e.getMessage(), true);
                }
            } else if ("PERSONAL".equalsIgnoreCase(selectionKind)) {
                if (!StringUtils.hasText(personalSelection)) {
                    response.setStatus(400);
                    return AgentContextFragments.defaultEditor(encode(agentId), null,
                            materializationRepository.findByAgentAndState(agentId, MaterializationState.READY),
                            "Personal selection is required for PERSONAL kind.", true);
                }
                PersonalSelection ps;
                try {
                    ps = PersonalSelection.valueOf(personalSelection.toUpperCase());
                } catch (IllegalArgumentException e) {
                    response.setStatus(400);
                    return AgentContextFragments.defaultEditor(encode(agentId), null,
                            materializationRepository.findByAgentAndState(agentId, MaterializationState.READY),
                            "Invalid personal selection: " + personalSelection, true);
                }
                try {
                    contextService.updateDefaultToPersonal(agentId, expectedVersion, ps, reason);
                } catch (IllegalStateException e) {
                    response.setStatus(409);
                    return AgentContextFragments.defaultEditor(encode(agentId),
                            contextService.getDefault(agentId).value(),
                            materializationRepository.findByAgentAndState(agentId, MaterializationState.READY),
                            "Conflict: " + e.getMessage(), true);
                }
            } else {
                response.setStatus(400);
                return AgentContextFragments.defaultEditor(encode(agentId), null,
                        materializationRepository.findByAgentAndState(agentId, MaterializationState.READY),
                        "Invalid selection kind: " + selectionKind, true);
            }

            // Success: re-render the full panel
            var defaultRecord = contextService.getDefault(agentId);
            return AgentContextFragments.contextSummary(encode(agentId),
                    defaultRecord.value(), List.of(),
                    materializationRepository.findByAgentAndState(agentId, MaterializationState.READY),
                    "Default updated.");
        } catch (IllegalArgumentException e) {
            response.setStatus(400);
            return AgentContextFragments.errorPanel(encode(agentId), "Invalid Request", e.getMessage());
        } catch (RuntimeException e) {
            log.debug("Default update failed for agent {}", agentId, e);
            response.setStatus(503);
            return AgentContextFragments.errorPanel(encode(agentId), "Unavailable",
                    "Default update is temporarily unavailable.");
        }
    }

    @GetMapping("/defaults/edit")
    @ResponseBody
    public String editDefault(@PathVariable String agentId, HttpServletResponse response) {
        if (!agentExists(agentId)) {
            response.setStatus(404);
            return AgentContextFragments.errorPanel(encode(agentId), "Not Found", "Agent not found.");
        }
        try {
            var defaultRecord = contextService.getDefault(agentId);
            return AgentContextFragments.defaultEditor(encode(agentId),
                    defaultRecord.value(),
                    materializationRepository.findByAgentAndState(agentId, MaterializationState.READY),
                    null, false);
        } catch (RuntimeException e) {
            log.debug("Default editor unavailable for agent {}", agentId, e);
            response.setStatus(503);
            return AgentContextFragments.errorPanel(encode(agentId), "Unavailable",
                    "Default editor is temporarily unavailable.");
        }
    }

    // ── Materializations ──

    @GetMapping(path = "/materializations", produces = "application/json")
    @ResponseBody
    public ResponseEntity<?> listMaterializations(@PathVariable String agentId) {
        if (!agentExists(agentId)) return notFound();
        var records = materializationRepository.findByAgent(agentId);
        List<Map<String, Object>> list = records.stream().map(r -> Map.<String, Object>of(
                "key", r.materializationKey().serialized(),
                "state", r.state().name(),
                "generation", r.generation(),
                "sourceKind", r.sourceKind(),
                "sourceId", r.sourceId()
        )).toList();
        return ResponseEntity.ok(Map.of("agentId", agentId, "materializations", list));
    }

    @GetMapping("/materializations/panel")
    @ResponseBody
    public String materializationsPanel(@PathVariable String agentId, HttpServletResponse response) {
        if (!agentExists(agentId)) {
            response.setStatus(404);
            return AgentContextFragments.errorPanel(encode(agentId), "Not Found", "Agent not found.");
        }
        try {
            var records = materializationRepository.findByAgent(agentId);
            return AgentContextFragments.materializationList(encode(agentId), records, null);
        } catch (RuntimeException e) {
            log.debug("Materialization list unavailable for agent {}", agentId, e);
            response.setStatus(503);
            return AgentContextFragments.errorPanel(encode(agentId), "Unavailable",
                    "Materialization data is temporarily unavailable.");
        }
    }

    @PostMapping("/materializations")
    @ResponseBody
    public String provisionMaterialization(@PathVariable String agentId,
                                           @RequestParam String projectKey,
                                           @RequestParam(required = false) String operationKey,
                                           HttpServletResponse response) {
        if (!agentExists(agentId)) {
            response.setStatus(404);
            return AgentContextFragments.errorPanel(encode(agentId), "Not Found", "Agent not found.");
        }
        try {
            WorkspaceDescriptor descriptor = directoryService.resolveForRead(WorkspaceKind.AGENT, agentId);
            MaterializationKey key = MaterializationKey.forProject(agentId, projectKey);
            materializationService.provision(descriptor, key);
            var records = materializationRepository.findByAgent(agentId);
            return AgentContextFragments.materializationList(encode(agentId), records,
                    "Provisioned successfully.");
        } catch (IllegalArgumentException e) {
            response.setStatus(400);
            return AgentContextFragments.materializationList(encode(agentId),
                    materializationRepository.findByAgent(agentId),
                    "Invalid: " + e.getMessage());
        } catch (IllegalStateException e) {
            response.setStatus(409);
            return AgentContextFragments.materializationList(encode(agentId),
                    materializationRepository.findByAgent(agentId),
                    "Conflict: " + e.getMessage());
        } catch (RuntimeException e) {
            log.debug("Materialization provision failed for agent {}", agentId, e);
            response.setStatus(503);
            return AgentContextFragments.errorPanel(encode(agentId), "Unavailable",
                    "Provision is temporarily unavailable: " + e.getMessage());
        }
    }

    // ── Bindings (read-only) ──

    @GetMapping(path = "/bindings", produces = "application/json")
    @ResponseBody
    public ResponseEntity<?> listBindings(@PathVariable String agentId) {
        if (!agentExists(agentId)) return notFound();
        var records = contextService.listBindings(agentId);
        List<Map<String, Object>> list = records.stream().map(b -> Map.<String, Object>of(
                "bindingKey", b.bindingKey(),
                "selectionKind", b.selectionKind().name(),
                "materializationKey", b.materializationKey() != null ? b.materializationKey().serialized() : null,
                "materializationGeneration", b.materializationGeneration(),
                "personalSelection", b.personalSelection() != null ? b.personalSelection().name() : null,
                "capturedAt", b.capturedAt().toString()
        )).toList();
        return ResponseEntity.ok(Map.of("agentId", agentId, "bindings", list));
    }

    // ── helpers ──

    private boolean agentExists(String agentId) {
        try {
            return agentProfileService.get(agentId) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private ResponseEntity<Map<String, Object>> notFound() {
        return ResponseEntity.status(404).body(Map.of("reason", "Agent not found"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
