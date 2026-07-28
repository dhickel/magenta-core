package io.mindspice.magenta2.api.web;

import java.util.List;

import io.mindspice.magenta2.ai.orchestration.agents.AgentProfile;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/agents")
public class AgentProfileController {
    private final AgentProfileService agentProfileService;

    public AgentProfileController(AgentProfileService agentProfileService) {
        this.agentProfileService = agentProfileService;
    }

    @GetMapping
    public List<AgentProfile> list() {
        return agentProfileService.list();
    }

    @PostMapping
    public AgentProfile create(@Valid @RequestBody AgentProfile profile) {
        try {
            return agentProfileService.create(profile);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @GetMapping("/{agentId}")
    public AgentProfile get(@PathVariable String agentId) {
        try {
            return agentProfileService.get(agentId);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }

    @PutMapping("/{agentId}")
    public AgentProfile update(@PathVariable String agentId, @Valid @RequestBody AgentProfile profile) {
        try {
            return agentProfileService.update(agentId, profile);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }

    @DeleteMapping("/{agentId}")
    public void delete(@PathVariable String agentId) {
        try {
            agentProfileService.deleteOrDisable(agentId);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }
}
