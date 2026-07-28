package io.mindspice.magenta2.api.web;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.mindspice.magenta2.ai.chat.system.SystemChatActionProposalService;
import io.mindspice.magenta2.ai.chat.system.SystemChatActionProposalService.ConfirmResult;
import io.mindspice.magenta2.ai.chat.system.SystemChatActionProposalService.CreateResult;
import io.mindspice.magenta2.ai.chat.system.SystemChatService;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Thin agent-scoped REST and HTMX adapter for restricted system chat,
 * SSE stream, and lifecycle proposals. Never writes files, invokes
 * processes, reveals secrets, or enters public chat surface selection.
 */
@Controller
@RequestMapping("/api/agents/{agentId}/system-chat")
class SystemChatController {
    private static final Logger log = LoggerFactory.getLogger(SystemChatController.class);

    private final AgentProfileService agentProfileService;
    private final SystemChatService systemChatService;
    private final SystemChatActionProposalService proposalService;

    SystemChatController(AgentProfileService agentProfileService,
                         SystemChatService systemChatService,
                         SystemChatActionProposalService proposalService) {
        this.agentProfileService = agentProfileService;
        this.systemChatService = systemChatService;
        this.proposalService = proposalService;
    }

    // ── Panel ──

    @GetMapping("/panel")
    @ResponseBody
    public String panel(@PathVariable String agentId, HttpServletResponse response) {
        if (!agentExists(agentId)) {
            response.setStatus(404);
            return SystemChatFragments.panel(encode(agentId), null, false);
        }
        try {
            var result = systemChatService.resolveProfile(agentId);
            return SystemChatFragments.panel(encode(agentId),
                    result.conversationId(), result.available());
        } catch (RuntimeException e) {
            log.debug("System chat panel unavailable for agent {}", agentId, e);
            response.setStatus(503);
            return SystemChatFragments.panel(encode(agentId), null, false);
        }
    }

    @GetMapping("/panel/history")
    @ResponseBody
    public String history(@PathVariable String agentId, HttpServletResponse response) {
        if (!agentExists(agentId)) {
            response.setStatus(404);
            return SystemChatFragments.history(encode(agentId), List.of());
        }
        try {
            var result = systemChatService.resolveProfile(agentId);
            if (!result.available()) {
                return SystemChatFragments.history(encode(agentId), List.of());
            }
            // History loaded from the system conversation; return empty for now
            // as the SSE stream appends messages client-side.
            return SystemChatFragments.history(encode(agentId), List.of());
        } catch (RuntimeException e) {
            log.debug("System chat history unavailable for agent {}", agentId, e);
            response.setStatus(503);
            return SystemChatFragments.history(encode(agentId), List.of());
        }
    }

    // ── SSE stream ──

    @PostMapping("/stream")
    @ResponseBody
    public SseEmitter stream(@PathVariable String agentId, @RequestParam String message) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5-minute timeout

        if (!agentExists(agentId)) {
            return completeWithError(emitter, "Agent not found");
        }
        if (!StringUtils.hasText(message) || message.length() > 16_000) {
            return completeWithError(emitter, "Message is empty or too large");
        }

        var result = systemChatService.resolveProfile(agentId);
        if (!result.available()) {
            return completeWithError(emitter, "System chat is unavailable");
        }

        // Emit initial metadata
        try {
            emitter.send(SseEmitter.event()
                    .name("start")
                    .data(Map.of("agentId", agentId, "conversationId", result.conversationId())));
        } catch (IOException e) {
            return completeWithError(emitter, null);
        }

        try {
            var invocationResult = systemChatService.invoke(agentId, message);
            if (!invocationResult.available()) {
                emitter.send(SseEmitter.event()
                        .name("error")
                        .data(Map.of("reason", "invocation_unavailable")));
                emitter.complete();
                return emitter;
            }

            String response = invocationResult.response() != null ? invocationResult.response() : "";

            // Send response as a single text event (not chunked per current service design)
            emitter.send(SseEmitter.event()
                    .name("message")
                    .data(Map.of("role", "assistant", "text", response)));

            // Load proposals after the turn completes
            var proposals = loadProposals(agentId, result.conversationId());
            emitter.send(SseEmitter.event()
                    .name("proposals")
                    .data(Map.of("proposals", proposals)));

            emitter.send(SseEmitter.event()
                    .name("done")
                    .data(Map.of("conversationId", result.conversationId())));
            emitter.complete();
        } catch (IOException e) {
            log.debug("System chat SSE stream interrupted for agent {}", agentId);
            emitter.completeWithError(e);
        } catch (RuntimeException e) {
            log.debug("System chat invocation failed for agent {}", agentId, e);
            try {
                emitter.send(SseEmitter.event()
                        .name("error")
                        .data(Map.of("reason", "invocation_failed")));
                emitter.complete();
            } catch (IOException ignored) {
                emitter.completeWithError(e);
            }
        }

        return emitter;
    }

    // ── Proposals ──

    @GetMapping("/proposals")
    @ResponseBody
    public String proposalsPanel(@PathVariable String agentId, HttpServletResponse response) {
        if (!agentExists(agentId)) {
            response.setStatus(404);
            return SystemChatFragments.proposals(encode(agentId), List.of());
        }
        try {
            var result = systemChatService.resolveProfile(agentId);
            if (!result.available()) {
                return SystemChatFragments.proposals(encode(agentId), List.of());
            }
            var proposals = loadProposals(agentId, result.conversationId());
            return SystemChatFragments.proposals(encode(agentId), proposals);
        } catch (RuntimeException e) {
            log.debug("Proposals panel unavailable for agent {}", agentId, e);
            response.setStatus(503);
            return SystemChatFragments.proposals(encode(agentId), List.of());
        }
    }

    @GetMapping(path = "/proposals", produces = "application/json")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> proposalsJson(@PathVariable String agentId) {
        if (!agentExists(agentId)) {
            return ResponseEntity.status(404).body(Map.of("reason", "Agent not found"));
        }
        try {
            var result = systemChatService.resolveProfile(agentId);
            if (!result.available()) {
                return ResponseEntity.ok(Map.of("agentId", agentId, "available", false, "proposals", List.of()));
            }
            var proposals = loadProposals(agentId, result.conversationId());
            return ResponseEntity.ok(Map.of("agentId", agentId, "available", true,
                    "proposals", proposals.stream().map(p -> Map.of(
                            "proposalId", p.proposalId(),
                            "action", p.action(),
                            "targetId", p.targetId(),
                            "state", p.state()
                    )).toList()));
        } catch (RuntimeException e) {
            return ResponseEntity.status(503).body(Map.of("reason", "Proposals unavailable"));
        }
    }

    @PostMapping("/proposals/{proposalId}/confirm")
    @ResponseBody
    public String confirmProposal(@PathVariable String agentId,
                                  @PathVariable String proposalId,
                                  @RequestParam("digest") String canonicalDigest,
                                  HttpServletResponse response) {
        if (!agentExists(agentId)) {
            response.setStatus(404);
            return SystemChatFragments.proposals(encode(agentId), List.of());
        }
        try {
            var result = systemChatService.resolveProfile(agentId);
            if (!result.available()) {
                response.setStatus(503);
                return SystemChatFragments.proposals(encode(agentId), List.of());
            }

            ConfirmResult confirmResult = proposalService.confirmProposal(
                    proposalId, agentId, canonicalDigest, true);
            if ("UNAVAILABLE".equals(confirmResult.state())) {
                response.setStatus(409);
            }

            var proposals = loadProposals(agentId, result.conversationId());
            return SystemChatFragments.proposals(encode(agentId), proposals);
        } catch (RuntimeException e) {
            log.debug("Proposal confirmation failed for agent {}", agentId, e);
            response.setStatus(409);
            try {
                var result = systemChatService.resolveProfile(agentId);
                var proposals = result.available() ? loadProposals(agentId, result.conversationId()) : List.<SystemChatFragments.ProposalRecord>of();
                return SystemChatFragments.proposals(encode(agentId), proposals);
            } catch (RuntimeException ignored) {
                return SystemChatFragments.proposals(encode(agentId), List.of());
            }
        }
    }

    // ── helpers ──

    private boolean agentExists(String agentId) {
        try {
            return agentProfileService.get(agentId) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private List<SystemChatFragments.ProposalRecord> loadProposals(String agentId, String conversationId) {
        // Proposals for the system chat conversation; the proposal service
        // uses conversation-scoped queries. We list by agent/proposal state.
        try {
            return proposalService.listProposals(agentId, conversationId).stream()
                    .map(p -> new SystemChatFragments.ProposalRecord(
                            p.proposalId(), p.action(), p.targetId(), p.canonicalDigest(), p.state(), p.createdAt()))
                    .toList();
        } catch (RuntimeException e) {
            log.debug("Failed to load proposals for agent {}", agentId, e);
            return List.of();
        }
    }

    private SseEmitter completeWithError(SseEmitter emitter, String reason) {
        try {
            if (reason != null) {
                emitter.send(SseEmitter.event()
                        .name("error")
                        .data(Map.of("reason", reason)));
            }
            emitter.complete();
        } catch (IOException ignored) {
            // complete anyway
        }
        return emitter;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
