package io.mindspice.magenta2.api.web.selector;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import io.mindspice.simplypages.components.TextNode;
import io.mindspice.simplypages.components.forms.TextInput;
import io.mindspice.simplypages.core.Component;
import io.mindspice.simplypages.core.HtmlTag;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class EntitySelectorComponents {
    static final String CONTEXT_PARAM_PREFIX = "selectorContext.";
    private static final String WORKSPACE_SELECTION_CONTEXT = "workspaceSelectionContext";

    public Component selector(EntitySelectorConfig config, EntityOption current) {
        String rootId = rootId(config.kind(), config.name());
        String resultsId = rootId + "-results";
        String statusId = rootId + "-status";
        String value = current == null ? normalize(config.currentValue()) : current.id();
        String label = StringUtils.hasText(config.label()) ? config.label() : title(config.kind().wireName());

        HtmlTag root = new HtmlTag("div")
            .withId(rootId)
            .withClass("entity-selector" + (current != null && !current.available() ? " entity-selector-invalid" : ""))
            .withAttribute("data-selector-kind", config.kind().wireName())
            .withAttribute("data-selector-name", config.name());
        for (Map.Entry<String, String> entry : config.contextParams().entrySet()) {
            if (StringUtils.hasText(entry.getValue())) {
                root.withChild(new HtmlTag("input", true)
                    .withAttribute("type", "hidden")
                    .withAttribute("name", contextParamName(entry.getKey()))
                    .withAttribute("value", entry.getValue()));
            }
        }

        TextInput input = TextInput.create(config.name())
            .withValue(value)
            .withPlaceholder(StringUtils.hasText(config.placeholder()) ? config.placeholder() : "Search " + label);
        input.withAttribute("autocomplete", "off")
            .withAttribute("hx-get", optionsUrl(config))
            .withAttribute("hx-trigger", "keyup changed delay:300ms, focus")
            .withAttribute("hx-target", "#" + resultsId)
            .withAttribute("hx-include", "closest .entity-selector")
            .withAttribute("hx-swap", "innerHTML")
            .withAttribute("hx-validate", "true");
        if (config.required()) {
            input.withAttribute("required", "required");
        }
        input.withAttribute("hx-on::after-request", "htmx.ajax('GET', '" + validateUrl(config)
            + "', {target: '#" + statusId + "', source: this})");

        root.withChild(new HtmlTag("label")
            .withClass("entity-selector-label")
            .withChild(new TextNode(label))
            .withChild(input));
        root.withChild(status(config, current));
        root.withChild(new HtmlTag("div")
            .withId(resultsId)
            .withClass("entity-selector-results")
            .withAttribute("role", "listbox"));
        return root;
    }

    public Component options(EntityKind kind, String name, boolean required, List<EntityOption> options) {
        return options(kind, name, required, options, Map.of(), null, null);
    }

    public Component options(
        EntityKind kind,
        String name,
        boolean required,
        List<EntityOption> options,
        Map<String, String> contextParams,
        String label,
        String placeholder
    ) {
        HtmlTag list = new HtmlTag("div").withClass("entity-selector-options");
        if (options.isEmpty()) {
            String message = "No matches";
            return list.withChild(new HtmlTag("div")
                .withClass("entity-selector-empty")
                .withInnerText(message));
        }
        for (EntityOption option : options) {
            String url = "/selectors/" + kind.wireName() + "/selected?name=" + enc(name)
                + "&value=" + enc(option.id()) + "&required=" + required;
            if (StringUtils.hasText(label)) {
                url += "&label=" + enc(label);
            }
            if (StringUtils.hasText(placeholder)) {
                url += "&placeholder=" + enc(placeholder);
            }
            for (Map.Entry<String, String> entry : contextParams.entrySet()) {
                if (StringUtils.hasText(entry.getValue())) {
                    url += "&" + enc(contextParamName(entry.getKey())) + "=" + enc(entry.getValue());
                }
            }
            String workspaceContext = contextParams.get(WORKSPACE_SELECTION_CONTEXT);
            if (kind == EntityKind.AGENT && StringUtils.hasText(workspaceContext)) {
                url = url.replace("/selectors/agent/selected", "/selectors/workspace/agent-selected");
            }
            HtmlTag row = new HtmlTag("button")
                .withAttribute("type", "button")
                .withClass("entity-selector-option" + (option.available() ? "" : " entity-selector-invalid"))
                .withAttribute("hx-get", url)
                .withAttribute("hx-target", "#" + rootId(kind, name))
                .withAttribute("hx-swap", "outerHTML")
                .withAttribute("role", "option");
            row.withChild(new HtmlTag("span").withClass("entity-selector-option-label").withInnerText(option.label()));
            row.withChild(new HtmlTag("code").withInnerText(option.id()));
            if (StringUtils.hasText(option.detail())) {
                row.withChild(new HtmlTag("span").withClass("entity-selector-option-detail").withInnerText(option.detail()));
            }
            if (StringUtils.hasText(option.status())) {
                row.withChild(new HtmlTag("span").withClass("entity-selector-option-status").withInnerText(option.status()));
            }
            list.withChild(row);
        }
        return list;
    }

    public Component validation(EntityValidation validation, boolean required) {
        String css = validation.exists() ? "entity-selector-status entity-selector-selected"
            : required || StringUtils.hasText(validation.id()) ? "entity-selector-status entity-selector-invalid"
            : "entity-selector-status";
        String message = StringUtils.hasText(validation.label())
            ? validation.message() + ": " + validation.label()
            : validation.message();
        return new HtmlTag("div").withClass(css).withInnerText(message == null ? "" : message);
    }

    private Component status(EntitySelectorConfig config, EntityOption current) {
        if (current == null || !StringUtils.hasText(current.id())) {
            return new HtmlTag("div").withId(rootId(config.kind(), config.name()) + "-status")
                .withClass("entity-selector-status")
                .withInnerText(config.required() ? "Required" : "");
        }
        EntityValidation validation = new EntityValidation(
            config.kind().wireName(), current.id(), current.available(), current.label(),
            current.available() ? "Selected" : "Not found");
        HtmlTag status = (HtmlTag) validation(validation, config.required());
        status.withId(rootId(config.kind(), config.name()) + "-status");
        return status;
    }

    private String optionsUrl(EntitySelectorConfig config) {
        return selectorUrl(config, "options");
    }

    private String validateUrl(EntitySelectorConfig config) {
        return selectorUrl(config, "validate");
    }

    private String selectorUrl(EntitySelectorConfig config, String action) {
        String url = "/selectors/" + config.kind().wireName() + "/" + action + "?name=" + enc(config.name())
            + "&required=" + config.required();
        if (StringUtils.hasText(config.label())) {
            url += "&label=" + enc(config.label());
        }
        if (StringUtils.hasText(config.placeholder())) {
            url += "&placeholder=" + enc(config.placeholder());
        }
        for (Map.Entry<String, String> entry : config.contextParams().entrySet()) {
            if (StringUtils.hasText(entry.getValue())) {
                url += "&" + enc(contextParamName(entry.getKey())) + "=" + enc(entry.getValue());
            }
        }
        return url;
    }

    private String contextParamName(String key) {
        return CONTEXT_PARAM_PREFIX + (key == null ? "" : key);
    }

    private String rootId(EntityKind kind, String name) {
        String safeName = name == null ? "value" : name.replaceAll("[^A-Za-z0-9_-]", "-");
        return "entity-selector-" + kind.wireName() + "-" + safeName;
    }

    private String title(String value) {
        if (!StringUtils.hasText(value)) {
            return "Entity";
        }
        return value.substring(0, 1).toUpperCase() + value.substring(1);
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }

    private String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
