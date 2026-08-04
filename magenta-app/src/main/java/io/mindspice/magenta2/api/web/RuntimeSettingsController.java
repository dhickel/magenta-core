package io.mindspice.magenta2.api.web;

import io.mindspice.magenta2.ai.orchestration.settings.RuntimeSettings;
import io.mindspice.magenta2.ai.orchestration.settings.RuntimeSettingsService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/settings/runtime")
public class RuntimeSettingsController {
    private final RuntimeSettingsService service;

    public RuntimeSettingsController(RuntimeSettingsService service) {
        this.service = service;
    }

    @GetMapping
    public RuntimeSettings get() {
        return service.get();
    }

    @PutMapping
    public RuntimeSettings update(@Valid @RequestBody RuntimeSettings settings) {
        try {
            return service.save(settings);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }
}
