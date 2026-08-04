package io.mindspice.magenta2.api.web.selector;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class EntitySelectorController {
    private final EntityLookupService lookupService;
    private final EntitySelectorComponents components;

    public EntitySelectorController(EntityLookupService lookupService, EntitySelectorComponents components) {
        this.lookupService = lookupService;
        this.components = components;
    }

    @GetMapping("/selectors/{kind}/options")
    @ResponseBody
    public String options(@PathVariable String kind, @RequestParam Map<String, String> params) {
        EntityKind entityKind = parseKind(kind);
        String name = first(params.get("name"), "id");
        String q = first(params.get("q"), params.get(name));
        SelectorQuery query = new SelectorQuery(
            q,
            parseInt(params.get("limit"), 20),
            params.get("current"),
            Boolean.parseBoolean(params.getOrDefault("includeUnavailable", "false")),
            context(params)
        );
        List<EntityOption> options = lookupService.search(entityKind, query);
        boolean required = Boolean.parseBoolean(params.getOrDefault("required", "false"));
        return components.options(
            entityKind,
            name,
            required,
            options,
            query.context(),
            params.get("label"),
            params.get("placeholder")
        ).render();
    }

    @GetMapping("/selectors/{kind}/selected")
    @ResponseBody
    public String selected(@PathVariable String kind, @RequestParam Map<String, String> params) {
        EntityKind entityKind = parseKind(kind);
        String name = first(params.get("name"), "id");
        String value = first(params.get("value"), params.get(name));
        boolean required = Boolean.parseBoolean(params.getOrDefault("required", "false"));
        EntityOption current = lookupService.currentOption(entityKind, value);
        EntitySelectorConfig config = new EntitySelectorConfig(
            name,
            entityKind,
            value,
            params.get("label"),
            params.get("placeholder"),
            required,
            context(params)
        );
        return components.selector(config, current).render();
    }

    @GetMapping("/selectors/{kind}/validate")
    @ResponseBody
    public String validate(@PathVariable String kind, @RequestParam Map<String, String> params) {
        EntityKind entityKind = parseKind(kind);
        String name = first(params.get("name"), "id");
        String id = first(params.get("id"), params.get(name));
        boolean required = Boolean.parseBoolean(params.getOrDefault("required", "false"));
        EntityValidation validation = lookupService.validate(entityKind, id, required);
        return components.validation(validation, required).render();
    }

    private EntityKind parseKind(String kind) {
        try {
            return EntityKind.fromWireName(kind);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    private Map<String, String> context(Map<String, String> params) {
        Map<String, String> context = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (entry.getKey().startsWith(EntitySelectorComponents.CONTEXT_PARAM_PREFIX)
                && StringUtils.hasText(entry.getValue())) {
                context.put(
                    entry.getKey().substring(EntitySelectorComponents.CONTEXT_PARAM_PREFIX.length()),
                    entry.getValue()
                );
            }
        }
        return context;
    }

    private String first(String first, String fallback) {
        return StringUtils.hasText(first) ? first.trim() : fallback;
    }

    private int parseInt(String value, int fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}
