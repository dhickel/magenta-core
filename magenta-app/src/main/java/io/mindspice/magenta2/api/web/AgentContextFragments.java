package io.mindspice.magenta2.api.web;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.ContextBinding;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.ContextDefault;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.PersonalSelection;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.SelectionKind;
import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationRepository.MaterializationRecord;
import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationRepository.MaterializationState;
import io.mindspice.simplypages.components.Div;
import io.mindspice.simplypages.components.Paragraph;
import io.mindspice.simplypages.components.forms.Button;
import io.mindspice.simplypages.components.forms.Form;
import io.mindspice.simplypages.core.Component;
import io.mindspice.simplypages.core.HtmlTag;

/** Stateless SimplyPages renderer for secret-free agent-context records. */
final class AgentContextFragments {
    static final String CONTEXT_PANEL_ID = "agent-context-panel";
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(ZoneId.systemDefault());

    private AgentContextFragments() {}

    static String contextSummary(String encodedAgentId, ContextDefault defaultRecord,
                                 List<ContextBinding> bindings, List<MaterializationRecord> materializations,
                                 String message) {
        Div panel = panel("Agent Context", encodedAgentId, "/context/panel", "Refresh");
        if (message != null && !message.isBlank()) panel.withChild(status(message, false));
        panel.withChild(defaultSection(defaultRecord));
        panel.withChild(materializationSection(materializations));
        panel.withChild(bindingSection(bindings));
        return panel.render();
    }

    static String defaultEditor(String encodedAgentId, ContextDefault currentDefault,
                                List<MaterializationRecord> readyMaterializations,
                                String message, boolean error) {
        Div panel = panel("Context Default", null, null, null);
        if (message != null && !message.isBlank()) panel.withChild(status(message, error));
        Form form = Form.create();
        form.withClass("orch-form-stack").withAttribute("data-htmx-error-swap", "true")
                .hxPut(route(encodedAgentId, "/defaults")).hxTarget("#" + CONTEXT_PANEL_ID).hxSwap("outerHTML");
        form.withChild(hidden("expectedVersion", String.valueOf(currentDefault == null ? 0 : currentDefault.version())));
        form.withChild(label("Kind", select("selectionKind", List.of(
                option("MATERIALIZATION", "Materialization (Project)", selected(currentDefault, SelectionKind.MATERIALIZATION)),
                option("PERSONAL", "Personal", selected(currentDefault, SelectionKind.PERSONAL))))));
        form.withChild(label("Project Key", input("text", "materializationKey", "agent/project-key",
                currentDefault != null && currentDefault.materializationKey() != null
                        ? currentDefault.materializationKey().serialized() : null)));
        form.withChild(label("Generation", input("number", "materializationGeneration", null,
                currentDefault != null && currentDefault.materializationGeneration() != null
                        ? String.valueOf(currentDefault.materializationGeneration()) : null)
                .withAttribute("min", "1")));
        HtmlTag personal = new HtmlTag("select").withAttribute("name", "personalSelection").withClass("orch-select");
        for (PersonalSelection selection : PersonalSelection.values()) {
            personal.withChild(option(selection.name(), selection.name(), currentDefault != null
                    && selection == currentDefault.personalSelection()));
        }
        form.withChild(label("Personal Area", personal));
        form.withChild(label("Reason", input("text", "reason", "Why this default?", null)
                .withAttribute("required", "required")));
        form.withChild(label("Operation Key", input("text", "operationKey", "optional idempotency key", null)));
        form.withChild(new Div().withClass("orch-actions")
                .withChild(Button.create("Update Default").withClass("orch-primary").withAttribute("type", "submit"))
                .withChild(Button.create("Cancel").withClass("button-secondary").withAttribute("type", "button")
                        .hxGet(route(encodedAgentId, "/panel")).hxTarget("#" + CONTEXT_PANEL_ID).hxSwap("outerHTML")));
        panel.withChild(form);
        if (readyMaterializations != null && !readyMaterializations.isEmpty()) {
            HtmlTag table = table("Key", "Gen");
            HtmlTag body = new HtmlTag("tbody");
            for (MaterializationRecord record : readyMaterializations) {
                body.withChild(row(code(record.materializationKey().serialized()), text(String.valueOf(record.generation()))));
            }
            panel.withChild(section("Available Projects", table.withChild(body)));
        }
        return panel.render();
    }

    static String materializationList(String encodedAgentId, List<MaterializationRecord> records, String message) {
        Div panel = panel("Materializations", encodedAgentId, "/context/panel", "Back");
        if (message != null && !message.isBlank()) panel.withChild(status(message, false));
        if (records == null || records.isEmpty()) {
            panel.withChild(new Paragraph("No materializations exist for this agent.").withClass("orch-muted"));
        } else {
            HtmlTag table = table("Key", "State", "Gen", "Created", "Updated");
            HtmlTag body = new HtmlTag("tbody");
            for (MaterializationRecord record : records) {
                body.withChild(row(code(record.materializationKey().serialized()), stateChip(record.state()),
                        text(String.valueOf(record.generation())), text(time(record.createdAt())), text(time(record.updatedAt()))));
            }
            panel.withChild(table.withChild(body));
        }
        Form form = Form.create();
        form.withClass("orch-form-stack").withAttribute("data-htmx-error-swap", "true")
                .hxPost(route(encodedAgentId, "/materializations")).hxTarget("#" + CONTEXT_PANEL_ID).hxSwap("outerHTML");
        form.withChild(label("Project Key", input("text", "projectKey", "e.g. my-project", null)
                .withAttribute("required", "required")));
        form.withChild(label("Operation Key", input("text", "operationKey", "optional", null)));
        form.withChild(new Div().withClass("orch-actions")
                .withChild(Button.create("Provision").withClass("orch-primary").withAttribute("type", "submit")));
        panel.withChild(section("Provision Project", form));
        return panel.render();
    }

    static String errorPanel(String encodedAgentId, String title, String message) {
        return panel(title, encodedAgentId, "/context/panel", "Retry")
                .withChild(status(message, true)).render();
    }

    private static Div defaultSection(ContextDefault defaultRecord) {
        if (defaultRecord == null) return section("Default", new Paragraph("No context default configured.").withClass("orch-muted"));
        Div facts = new Div().withClass("context-facts")
                .withChild(fact("Kind", defaultRecord.selectionKind().name()));
        if (defaultRecord.selectionKind() == SelectionKind.MATERIALIZATION && defaultRecord.materializationKey() != null) {
            facts.withChild(fact("Project", code(defaultRecord.materializationKey().serialized())))
                    .withChild(fact("Generation", String.valueOf(defaultRecord.materializationGeneration())));
        } else if (defaultRecord.personalSelection() != null) {
            facts.withChild(fact("Area", defaultRecord.personalSelection().name()));
        }
        facts.withChild(fact("Version", String.valueOf(defaultRecord.version())))
                .withChild(fact("Reason", defaultRecord.reason()));
        return section("Default", facts);
    }

    private static Div materializationSection(List<MaterializationRecord> records) {
        if (records == null || records.isEmpty()) return section("Materializations", new Paragraph("No materializations.").withClass("orch-muted"));
        HtmlTag table = table("Key", "State", "Generation", "Updated");
        HtmlTag body = new HtmlTag("tbody");
        for (MaterializationRecord record : records) {
            body.withChild(row(code(record.materializationKey().serialized()), stateChip(record.state()),
                    text(String.valueOf(record.generation())), text(time(record.updatedAt()))));
        }
        return section("Materializations", table.withChild(body));
    }

    private static Div bindingSection(List<ContextBinding> bindings) {
        if (bindings == null || bindings.isEmpty()) return section("Bindings", new Paragraph("No bindings captured.").withClass("orch-muted"));
        HtmlTag table = table("Binding Key", "Kind", "Captured");
        HtmlTag body = new HtmlTag("tbody");
        for (ContextBinding binding : bindings) {
            body.withChild(row(code(truncateKey(binding.bindingKey())), text(binding.selectionKind().name()), text(time(binding.capturedAt()))));
        }
        return section("Bindings", table.withChild(body));
    }

    private static Div panel(String title, String encodedAgentId, String refreshPath, String refreshLabel) {
        Div panel = new Div().withId(CONTEXT_PANEL_ID).withClass("orch-panel agent-context-panel");
        Div heading = new Div().withClass("orch-panel-heading").withChild(new HtmlTag("h3").withInnerText(title));
        if (encodedAgentId != null && refreshPath != null) {
            heading.withChild(Button.create(refreshLabel).withClass("button-secondary small")
                    .hxGet(route(encodedAgentId, refreshPath)).hxTarget("#" + CONTEXT_PANEL_ID).hxSwap("outerHTML"));
        }
        return panel.withChild(heading);
    }

    private static Div section(String title, Component content) {
        return new Div().withClass("context-section").withChild(new HtmlTag("h4").withInnerText(title)).withChild(content);
    }

    private static Component status(String value, boolean error) {
        return new Div().withClass(error ? "orch-status-error" : "orch-status").withInnerText(value == null ? "" : value);
    }

    private static HtmlTag table(String... headings) {
        HtmlTag row = new HtmlTag("tr");
        for (String heading : headings) row.withChild(new HtmlTag("th").withInnerText(heading));
        return new HtmlTag("table").withClass("orch-table compact").withChild(new HtmlTag("thead").withChild(row));
    }

    private static HtmlTag row(Component... cells) {
        HtmlTag row = new HtmlTag("tr");
        for (Component cell : cells) row.withChild(new HtmlTag("td").withChild(cell));
        return row;
    }

    private static Div fact(String label, String value) {
        return fact(label, text(value));
    }

    private static Div fact(String label, Component value) {
        return new Div().withClass("context-fact")
                .withChild(new HtmlTag("span").withClass("context-fact-label").withInnerText(label))
                .withChild(new HtmlTag("span").withClass("context-fact-value").withChild(value));
    }

    private static HtmlTag label(String value, Component input) {
        return new HtmlTag("label").withInnerText(value).withChild(input);
    }

    private static HtmlTag input(String type, String name, String placeholder, String value) {
        HtmlTag input = new HtmlTag("input").withAttribute("type", type).withAttribute("name", name).withClass("orch-input");
        if (placeholder != null) input.withAttribute("placeholder", placeholder);
        if (value != null) input.withAttribute("value", value);
        return input;
    }

    private static HtmlTag hidden(String name, String value) {
        return new HtmlTag("input").withAttribute("type", "hidden").withAttribute("name", name).withAttribute("value", value);
    }

    private static HtmlTag select(String name, List<HtmlTag> options) {
        HtmlTag select = new HtmlTag("select").withAttribute("name", name).withClass("orch-select");
        options.forEach(select::withChild);
        return select;
    }

    private static HtmlTag option(String value, String label, boolean selected) {
        HtmlTag option = new HtmlTag("option").withAttribute("value", value).withInnerText(label);
        return selected ? option.withAttribute("selected", "selected") : option;
    }

    private static boolean selected(ContextDefault current, SelectionKind kind) {
        return current != null && current.selectionKind() == kind;
    }

    private static Component stateChip(MaterializationState state) {
        String css = switch (state) {
            case READY -> "orch-status-chip active";
            case FAILED, REVOKED -> "orch-status-chip disabled";
            case PROVISIONING -> "orch-chip";
        };
        return new HtmlTag("span").withClass(css).withInnerText(state.name());
    }

    private static Component code(String value) { return new HtmlTag("code").withInnerText(value == null ? "" : value); }
    private static Component text(String value) { return new HtmlTag("span").withInnerText(value == null ? "—" : value); }
    private static String route(String agentId, String suffix) { return "/api/agents/" + agentId + "/context" + suffix; }
    private static String time(Instant instant) { return instant == null ? "—" : TIME_FORMAT.format(instant); }
    private static String truncateKey(String key) { return key != null && key.length() > 48 ? key.substring(0, 45) + "…" : key; }
}
