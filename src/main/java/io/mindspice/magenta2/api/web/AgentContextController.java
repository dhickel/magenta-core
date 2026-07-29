package io.mindspice.magenta2.api.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.mindspice.magenta2.ai.orchestration.agents.AgentProfile;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.ContextBinding;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.ContextDefault;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.PersonalSelection;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.SelectionKind;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextService;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextService.ContextSnapshot;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextService.DefaultUpdateRequest;
import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationRepository.MaterializationKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
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

/** Thin agent-scoped adapter; context services own persistence, descriptors, and mutations. */
@Controller
@RequestMapping("/api/agents/{agentId}/context")
class AgentContextController {
    private static final Logger log = LoggerFactory.getLogger(AgentContextController.class);

    private final AgentProfileService agentProfileService;
    private final AgentContextService contextService;

    AgentContextController(AgentProfileService agentProfileService, AgentContextService contextService) {
        this.agentProfileService = agentProfileService;
        this.contextService = contextService;
    }

    @GetMapping(produces = "application/json")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> summary(@PathVariable String agentId) {
        Optional<AgentProfile> profile = agentProfileService.find(agentId);
        if (profile.isEmpty()) return notFound();
        ContextSnapshot snapshot = contextService.snapshot(agentId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("agentId", agentId);
        response.put("default", snapshot.defaultRecord() == null ? "UNAVAILABLE" : "AVAILABLE");
        response.put("defaultRecord", defaultPayload(snapshot.defaultRecord()));
        response.put("materializationCount", snapshot.materializations().size());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/panel")
    @ResponseBody
    public String contextPanel(@PathVariable String agentId, HttpServletResponse response) {
        try {
            if (agentProfileService.find(agentId).isEmpty()) {
                return error(response, 404, agentId, "Not Found", "Agent not found.");
            }
            return summaryFragment(agentId, contextService.snapshot(agentId), null);
        } catch (DataAccessException exception) {
            log.debug("Context panel infrastructure failure");
            return error(response, 503, agentId, "Unavailable", "Context data is temporarily unavailable.");
        } catch (IllegalArgumentException exception) {
            return error(response, 400, agentId, "Invalid", "Invalid agent context request.");
        } catch (RuntimeException exception) {
            log.warn("Context panel failure");
            return error(response, 500, agentId, "Error", "Context data could not be loaded.");
        }
    }

    @GetMapping(path = "/defaults", produces = "application/json")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getDefault(@PathVariable String agentId) {
        Optional<AgentProfile> profile = agentProfileService.find(agentId);
        if (profile.isEmpty()) return notFound();
        ContextDefault defaultRecord = contextService.getDefault(agentId).value();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("agentId", agentId);
        response.put("available", defaultRecord != null);
        if (defaultRecord != null) response.putAll(defaultPayload(defaultRecord));
        return ResponseEntity.ok(response);
    }

    @PutMapping("/defaults")
    @ResponseBody
    public String updateDefault(@PathVariable String agentId,
                                @RequestParam int expectedVersion,
                                @RequestParam String selectionKind,
                                @RequestParam(required = false) String materializationKey,
                                @RequestParam(required = false, defaultValue = "0") int materializationGeneration,
                                @RequestParam(required = false) String personalSelection,
                                @RequestParam(required = false) String reason,
                                @RequestParam(required = false) String operationKey,
                                HttpServletResponse response) {
        try {
            if (agentProfileService.find(agentId).isEmpty()) {
                return error(response, 404, agentId, "Not Found", "Agent not found.");
            }
            SelectionKind kind = SelectionKind.valueOf(selectionKind.toUpperCase());
            MaterializationKey key = kind == SelectionKind.MATERIALIZATION
                    ? MaterializationKey.fromSerialized(agentId, required(materializationKey, "Materialization key")) : null;
            PersonalSelection personal = kind == SelectionKind.PERSONAL
                    ? PersonalSelection.valueOf(required(personalSelection, "Personal selection").toUpperCase()) : null;
            contextService.updateDefault(new DefaultUpdateRequest(agentId, expectedVersion, kind, key,
                    kind == SelectionKind.MATERIALIZATION ? materializationGeneration : null,
                    personal, reason, operationKey));
            return summaryFragment(agentId, contextService.snapshot(agentId), "Default updated.");
        } catch (AgentContextService.OperationConflictException exception) {
            response.setStatus(409);
            return AgentContextFragments.defaultEditor(encode(agentId), contextService.getDefault(agentId).value(),
                    contextService.readyMaterializations(agentId), "Conflict with current state.", true);
        } catch (IllegalArgumentException exception) {
            response.setStatus(400);
            return AgentContextFragments.defaultEditor(encode(agentId), contextService.getDefault(agentId).value(),
                    contextService.readyMaterializations(agentId), "Invalid request.", true);
        } catch (DataAccessException exception) {
            log.debug("Default update infrastructure failure");
            return error(response, 503, agentId, "Unavailable", "Default update is temporarily unavailable.");
        } catch (RuntimeException exception) {
            log.warn("Default update failure");
            return error(response, 500, agentId, "Error", "Default update could not be completed.");
        }
    }

    @GetMapping("/defaults/edit")
    @ResponseBody
    public String editDefault(@PathVariable String agentId, HttpServletResponse response) {
        try {
            if (agentProfileService.find(agentId).isEmpty()) {
                return error(response, 404, agentId, "Not Found", "Agent not found.");
            }
            return AgentContextFragments.defaultEditor(encode(agentId), contextService.getDefault(agentId).value(),
                    contextService.readyMaterializations(agentId), null, false);
        } catch (DataAccessException exception) {
            log.debug("Default editor infrastructure failure");
            return error(response, 503, agentId, "Unavailable", "Default editor is temporarily unavailable.");
        } catch (IllegalArgumentException exception) {
            return error(response, 400, agentId, "Invalid", "Invalid agent context request.");
        } catch (RuntimeException exception) {
            log.warn("Default editor failure");
            return error(response, 500, agentId, "Error", "Default editor could not be loaded.");
        }
    }

    @GetMapping(path = "/materializations", produces = "application/json")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> listMaterializations(@PathVariable String agentId) {
        Optional<AgentProfile> profile = agentProfileService.find(agentId);
        if (profile.isEmpty()) return notFound();
        List<Map<String, Object>> records = contextService.materializations(agentId).stream().map(record -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("key", record.materializationKey().serialized());
            payload.put("state", record.state().name());
            payload.put("generation", record.generation());
            payload.put("sourceKind", record.sourceKind());
            payload.put("sourceId", record.sourceId());
            return payload;
        }).toList();
        return ResponseEntity.ok(Map.of("agentId", agentId, "materializations", records));
    }

    @GetMapping("/materializations/panel")
    @ResponseBody
    public String materializationsPanel(@PathVariable String agentId, HttpServletResponse response) {
        try {
            if (agentProfileService.find(agentId).isEmpty()) {
                return error(response, 404, agentId, "Not Found", "Agent not found.");
            }
            return AgentContextFragments.materializationList(encode(agentId), contextService.materializations(agentId), null);
        } catch (DataAccessException exception) {
            log.debug("Materialization infrastructure failure");
            return error(response, 503, agentId, "Unavailable", "Materialization data is temporarily unavailable.");
        } catch (IllegalArgumentException exception) {
            return error(response, 400, agentId, "Invalid", "Invalid agent context request.");
        } catch (RuntimeException exception) {
            log.warn("Materialization list failure");
            return error(response, 500, agentId, "Error", "Materialization data could not be loaded.");
        }
    }

    @PostMapping("/materializations")
    @ResponseBody
    public String provisionMaterialization(@PathVariable String agentId,
                                           @RequestParam String projectKey,
                                           @RequestParam(required = false) String operationKey,
                                           HttpServletResponse response) {
        try {
            if (agentProfileService.find(agentId).isEmpty()) {
                return error(response, 404, agentId, "Not Found", "Agent not found.");
            }
            contextService.provisionProject(agentId, projectKey, operationKey);
            return AgentContextFragments.materializationList(encode(agentId), contextService.materializations(agentId),
                    "Provisioned successfully.");
        } catch (AgentContextService.OperationConflictException exception) {
            response.setStatus(409);
            return AgentContextFragments.materializationList(encode(agentId), contextService.materializations(agentId),
                    "Conflict with current state.");
        } catch (IllegalArgumentException exception) {
            response.setStatus(400);
            return AgentContextFragments.materializationList(encode(agentId), contextService.materializations(agentId),
                    "Invalid request.");
        } catch (DataAccessException exception) {
            log.debug("Materialization provision infrastructure failure");
            return error(response, 503, agentId, "Unavailable", "Provision is temporarily unavailable.");
        } catch (RuntimeException exception) {
            log.warn("Materialization provision failure");
            return error(response, 500, agentId, "Error", "Provision could not be completed.");
        }
    }

    @GetMapping(path = "/bindings", produces = "application/json")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> listBindings(@PathVariable String agentId) {
        Optional<AgentProfile> profile = agentProfileService.find(agentId);
        if (profile.isEmpty()) return notFound();
        List<Map<String, Object>> bindings = contextService.listBindings(agentId).stream()
                .map(AgentContextController::bindingPayload).toList();
        return ResponseEntity.ok(Map.of("agentId", agentId, "bindings", bindings));
    }

    private String summaryFragment(String agentId, ContextSnapshot snapshot, String message) {
        return AgentContextFragments.contextSummary(encode(agentId), snapshot.defaultRecord(), snapshot.bindings(),
                snapshot.materializations(), message);
    }

    private static Map<String, Object> defaultPayload(ContextDefault record) {
        if (record == null) return null;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", record.version());
        payload.put("selectionKind", record.selectionKind().name());
        payload.put("materializationKey", record.materializationKey() == null ? null : record.materializationKey().serialized());
        payload.put("materializationGeneration", record.materializationGeneration());
        payload.put("personalSelection", record.personalSelection() == null ? null : record.personalSelection().name());
        payload.put("reason", record.reason());
        return payload;
    }

    private static Map<String, Object> bindingPayload(ContextBinding binding) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bindingKey", binding.bindingKey());
        payload.put("selectionKind", binding.selectionKind().name());
        payload.put("materializationKey", binding.materializationKey() == null ? null : binding.materializationKey().serialized());
        payload.put("materializationGeneration", binding.materializationGeneration());
        payload.put("personalSelection", binding.personalSelection() == null ? null : binding.personalSelection().name());
        payload.put("capturedAt", binding.capturedAt().toString());
        return payload;
    }

    private static String required(String value, String label) {
        if (!StringUtils.hasText(value)) throw new IllegalArgumentException(label + " is required");
        return value;
    }

    private static ResponseEntity<Map<String, Object>> notFound() {
        return ResponseEntity.status(404).body(Map.of("reason", "Agent not found"));
    }

    private static String error(HttpServletResponse response, int status, String agentId, String title, String message) {
        response.setStatus(status);
        return AgentContextFragments.errorPanel(encode(agentId), title, message);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
