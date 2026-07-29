package io.mindspice.magenta2.api.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.mindspice.magenta2.ai.orchestration.runtime.EventReactionService.ReactionDefinition;
import io.mindspice.magenta2.ai.orchestration.runtime.EventReactionService.ReactionDispatch;
import io.mindspice.magenta2.ai.orchestration.runtime.EventReactionService.ReactionSimulation;
import io.mindspice.magenta2.ai.orchestration.runtime.ScheduleMode;
import io.mindspice.magenta2.ai.orchestration.runtime.ScheduleService.ScheduleDefinition;
import io.mindspice.magenta2.ai.orchestration.runtime.ScheduleService.ScheduleFiring;
import io.mindspice.magenta2.ai.orchestration.runtime.ScheduleService.ScheduleSimulation;
import io.mindspice.simplypages.components.Div;
import io.mindspice.simplypages.components.Header;
import io.mindspice.simplypages.components.Paragraph;
import io.mindspice.simplypages.components.forms.Button;
import io.mindspice.simplypages.components.forms.Form;
import io.mindspice.simplypages.components.forms.Select;
import io.mindspice.simplypages.components.forms.TextArea;
import io.mindspice.simplypages.components.forms.TextInput;
import io.mindspice.simplypages.core.HtmlTag;

import org.springframework.util.StringUtils;

/**
 * Reusable project automation rendering fragments for Schedules and Events tabs.
 * HTMX-only, no new JavaScript.
 */
@org.springframework.stereotype.Component
public final class ProjectAutomationFragments {

    // ════════════════════════════════════════════════════════════════
    //  Schedules tab
    // ════════════════════════════════════════════════════════════════

    public String schedulesTabShell(String projectId) {
        Div div = new Div();
        div.withClass("project-schedules-tab");
        div.withChild(Header.H2("Schedules"));
        div.withChild(newParagraph("Project-scoped schedule definitions. "
            + "CRON schedules fire on a recurring basis; ONE_TIME schedules fire once at a specific instant."));
        div.withChild(Button.create("New Schedule").withClass("orch-primary small")
            .withAttribute("hx-get", "/projects/_detail/" + e(projectId) + "/schedules/_create")
            .withAttribute("hx-target", "#project-schedules-detail")
            .withAttribute("hx-swap", "innerHTML")
            .withAttribute("data-htmx-error-swap", "true"));
        Div list = new Div();
        list.withId("project-schedules-list");
        list.withAttribute("hx-get", "/projects/_detail/" + e(projectId) + "/schedules/_list");
        list.withAttribute("hx-trigger", "load");
        list.withAttribute("hx-swap", "innerHTML");
        list.withChild(emptyDiv("Loading..."));
        div.withChild(list);
        div.withChild(new Div().withId("project-schedules-detail"));
        return div.render();
    }

    public String scheduleList(String projectId, List<ScheduleDefinition> definitions) {
        Div container = new Div();
        container.withId("project-schedules-list");
        container.withClass("project-operational-list");
        if (definitions.isEmpty()) {
            container.withChild(emptyState("No schedules defined for this project."));
        }
        for (ScheduleDefinition def : definitions) {
            container.withChild(scheduleRow(projectId, def));
        }
        return container.render();
    }

    private Div scheduleRow(String projectId, ScheduleDefinition def) {
        Div row = new Div();
        row.withClass("project-operational-row");
        Div main = new Div();
        main.withClass("project-operational-main");
        main.withChild(strong(dv(def.id())));
        main.withChild(chip(def.mode() != null ? def.mode().name() : "CRON"));
        main.withChild(statusChip(def.enabled()));
        main.withChild(muted(itemContext(def)));
        row.withChild(main);
        Div actions = new Div();
        actions.withClass("project-operational-actions");
        actions.withChild(Button.create("Detail").withClass("orch-secondary small")
            .withAttribute("hx-get", "/projects/_detail/" + e(projectId)
                + "/schedules/_detail/" + e(def.id()) + "?revision=" + def.revision())
            .withAttribute("hx-target", "#project-schedules-detail")
            .withAttribute("hx-swap", "innerHTML")
            .withAttribute("data-htmx-error-swap", "true"));
        row.withChild(actions);
        return row;
    }

    public String scheduleDetail(String projectId, ScheduleDefinition def,
                                  List<ScheduleFiring> firings) {
        Div panel = new Div();
        panel.withId("project-schedules-detail");
        panel.withClass("project-detail-panel");
        panel.withChild(Header.H3("Schedule Detail"));

        Div meta = new Div();
        meta.withClass("orch-meta");
        meta.withChild(span("ID: " + dv(def.id())));
        meta.withChild(span("Agent: " + dv(def.agentId())));
        meta.withChild(span("Revision: " + def.revision()));
        meta.withChild(span("Mode: " + (def.mode() != null ? def.mode().name() : "CRON")));
        meta.withChild(span(def.enabled() ? "Enabled" : "Disabled"));
        panel.withChild(meta);

        if (def.mode() == ScheduleMode.CRON) {
            panel.withChild(span("CRON: " + dv(def.cronExpression()) + " (" + dv(def.timezone()) + ")"));
        } else if (def.fireAt() != null) {
            panel.withChild(span("Fire At: " + def.fireAt().toString()));
        }

        Div actions = new Div();
        actions.withClass("tool-actions");
        actions.withChild(Button.create(def.enabled() ? "Disable" : "Enable")
            .withClass(def.enabled() ? "orch-secondary small" : "orch-primary small")
            .withAttribute("hx-post", "/projects/_detail/" + e(projectId)
                + "/schedules/" + e(def.id()) + "/enable")
            .withAttribute("hx-vals", "{\"enabled\": " + !def.enabled() + "}")
            .withAttribute("hx-target", "#project-schedules-detail")
            .withAttribute("hx-swap", "innerHTML")
            .withAttribute("data-htmx-error-swap", "true"));
        actions.withChild(Button.create("Delete").withClass("orch-danger small")
            .withAttribute("hx-delete", "/projects/_detail/" + e(projectId)
                + "/schedules/" + e(def.id()))
            .withAttribute("hx-target", "#project-schedules-list")
            .withAttribute("hx-swap", "innerHTML")
            .withAttribute("data-htmx-error-swap", "true")
            .withAttribute("hx-confirm", "Delete this schedule?"));
        panel.withChild(actions);

        panel.withChild(Header.H4("Recent Firings"));
        if (firings.isEmpty()) {
            panel.withChild(emptyDiv("No firings recorded."));
        } else {
            for (ScheduleFiring f : firings) {
                Div frow = new Div();
                frow.withClass("project-operational-row");
                frow.withChild(chip(dv(f.firingState())));
                frow.withChild(muted("Slot: " + f.slotInstant().toString()));
                panel.withChild(frow);
            }
        }
        return panel.render();
    }

    public String scheduleCreateForm(String projectId, Map<String, String> values, String error) {
        Div panel = new Div();
        panel.withId("project-schedules-detail");
        panel.withClass("project-detail-panel");
        panel.withChild(Header.H3("New Schedule"));
        if (StringUtils.hasText(error)) {
            panel.withChild(errorDiv(error));
        }

        String selectedMode = values.getOrDefault("mode", "CRON");
        Form form = Form.create();
        form.withHxPost("/projects/_detail/" + e(projectId) + "/schedules");
        form.withHxTarget("#project-schedules-detail");
        form.withHxSwap("innerHTML");
        form.withClass("orch-form-stack");
        form.withAttribute("data-htmx-error-swap", "true");

        form.withChild(formLabel("Mode", Select.create("mode")
            .addOption("CRON", "CRON (Recurring)", "CRON".equals(selectedMode))
            .addOption("ONE_TIME", "ONE_TIME (Single Fire)", "ONE_TIME".equals(selectedMode))));

        if ("CRON".equals(selectedMode)) {
            form.withChild(formLabel("CRON Expression", TextInput.create("cronExpression")
                .withValue(values.getOrDefault("cronExpression", ""))));
            form.withChild(formLabel("Timezone", TextInput.create("timezone")
                .withValue(values.getOrDefault("timezone", "UTC"))));
        } else {
            form.withChild(formLabel("Fire At (ISO instant)", TextInput.create("fireAt")
                .withValue(values.getOrDefault("fireAt", ""))));
        }

        form.withChild(formLabel("Agent ID", TextInput.create("agentId")
            .withValue(values.getOrDefault("agentId", ""))));
        form.withChild(formLabel("Job ID (optional)", TextInput.create("jobId")
            .withValue(values.getOrDefault("jobId", ""))));
        form.withChild(formLabel("Assignment Template (JSON)", TextArea.create("assignmentTemplate")
            .withRows(5).withValue(values.getOrDefault("assignmentTemplate", "{}"))));

        Div toolActions = new Div();
        toolActions.withClass("tool-actions");
        toolActions.withChild(Button.create("Create").withClass("orch-primary small")
            .withAttribute("type", "submit"));
        toolActions.withChild(Button.create("Simulate").withClass("orch-secondary small")
            .withAttribute("type", "button")
            .withAttribute("hx-post", "/projects/_detail/" + e(projectId) + "/schedules/_simulate")
            .withAttribute("hx-target", "#project-schedules-simulation")
            .withAttribute("hx-swap", "innerHTML")
            .withAttribute("hx-include", "closest form"));
        form.withChild(toolActions);

        panel.withChild(form);
        Div simDiv = new Div();
        simDiv.withId("project-schedules-simulation");
        panel.withChild(simDiv);
        return panel.render();
    }

    // ════════════════════════════════════════════════════════════════
    //  Events tab
    // ════════════════════════════════════════════════════════════════

    public String eventsTabShell(String projectId) {
        Div div = new Div();
        div.withClass("project-events-tab");
        div.withChild(Header.H2("Events & Reactions"));
        div.withChild(newParagraph("Project-scoped event types and reaction definitions. "
            + "Reactions only fire for events carrying this project's identity."));
        div.withChild(Button.create("New Reaction").withClass("orch-primary small")
            .withAttribute("hx-get", "/projects/_detail/" + e(projectId) + "/events/_create")
            .withAttribute("hx-target", "#project-events-detail")
            .withAttribute("hx-swap", "innerHTML")
            .withAttribute("data-htmx-error-swap", "true"));
        Div list = new Div();
        list.withId("project-events-list");
        list.withAttribute("hx-get", "/projects/_detail/" + e(projectId) + "/events/_list");
        list.withAttribute("hx-trigger", "load");
        list.withAttribute("hx-swap", "innerHTML");
        list.withChild(emptyDiv("Loading..."));
        div.withChild(list);
        div.withChild(new Div().withId("project-events-detail"));
        return div.render();
    }

    public String reactionList(String projectId, List<ReactionDefinition> definitions) {
        Div container = new Div();
        container.withId("project-events-list");
        container.withClass("project-operational-list");
        if (definitions.isEmpty()) {
            container.withChild(emptyState("No reaction definitions for this project."));
        }
        for (ReactionDefinition def : definitions) {
            Div row = new Div();
            row.withClass("project-operational-row");
            Div main = new Div();
            main.withClass("project-operational-main");
            main.withChild(strong(dv(def.id())));
            main.withChild(chip(def.eventType() != null ? def.eventType().name() : "?"));
            main.withChild(statusChip(def.enabled()));
            main.withChild(muted("v" + def.revision()));
            row.withChild(main);
            Div actions = new Div();
            actions.withClass("project-operational-actions");
            actions.withChild(Button.create("Detail").withClass("orch-secondary small")
                .withAttribute("hx-get", "/projects/_detail/" + e(projectId)
                    + "/events/_detail/" + e(def.id()) + "?revision=" + def.revision())
                .withAttribute("hx-target", "#project-events-detail")
                .withAttribute("hx-swap", "innerHTML")
                .withAttribute("data-htmx-error-swap", "true"));
            row.withChild(actions);
            container.withChild(row);
        }
        return container.render();
    }

    public String reactionDetail(String projectId, ReactionDefinition def,
                                  List<ReactionDispatch> dispatches) {
        Div panel = new Div();
        panel.withId("project-events-detail");
        panel.withClass("project-detail-panel");
        panel.withChild(Header.H3("Reaction Detail"));

        Div meta = new Div();
        meta.withClass("orch-meta");
        meta.withChild(span("ID: " + dv(def.id())));
        meta.withChild(span("Agent: " + dv(def.agentId())));
        meta.withChild(span("Revision: " + def.revision()));
        meta.withChild(span("Event: " + (def.eventType() != null ? def.eventType().name() : "?")));
        meta.withChild(span(def.enabled() ? "Enabled" : "Disabled"));
        panel.withChild(meta);

        Div actions = new Div();
        actions.withClass("tool-actions");
        actions.withChild(Button.create(def.enabled() ? "Disable" : "Enable")
            .withClass(def.enabled() ? "orch-secondary small" : "orch-primary small")
            .withAttribute("hx-post", "/projects/_detail/" + e(projectId)
                + "/events/" + e(def.id()) + "/enable")
            .withAttribute("hx-vals", "{\"enabled\": " + !def.enabled() + "}")
            .withAttribute("hx-target", "#project-events-detail")
            .withAttribute("hx-swap", "innerHTML")
            .withAttribute("data-htmx-error-swap", "true"));
        actions.withChild(Button.create("Delete").withClass("orch-danger small")
            .withAttribute("hx-delete", "/projects/_detail/" + e(projectId)
                + "/events/" + e(def.id()))
            .withAttribute("hx-target", "#project-events-list")
            .withAttribute("hx-swap", "innerHTML")
            .withAttribute("data-htmx-error-swap", "true")
            .withAttribute("hx-confirm", "Delete this reaction?"));
        panel.withChild(actions);

        panel.withChild(Header.H4("Recent Dispatches"));
        if (dispatches.isEmpty()) {
            panel.withChild(emptyDiv("No dispatches recorded."));
        } else {
            for (ReactionDispatch d : dispatches) {
                Div drow = new Div();
                drow.withClass("project-operational-row");
                drow.withChild(chip(dv(d.dispatchState())));
                drow.withChild(muted("Event: " + d.sourceEventId().substring(0, 8) + "\u2026"));
                panel.withChild(drow);
            }
        }
        return panel.render();
    }

    public String reactionCreateForm(String projectId, Map<String, String> values, String error) {
        Div panel = new Div();
        panel.withId("project-events-detail");
        panel.withClass("project-detail-panel");
        panel.withChild(Header.H3("New Reaction"));
        if (StringUtils.hasText(error)) {
            panel.withChild(errorDiv(error));
        }

        Form form = Form.create();
        form.withHxPost("/projects/_detail/" + e(projectId) + "/events");
        form.withHxTarget("#project-events-detail");
        form.withHxSwap("innerHTML");
        form.withClass("orch-form-stack");
        form.withAttribute("data-htmx-error-swap", "true");

        form.withChild(formLabel("Event Type", TextInput.create("eventType")
            .withValue(values.getOrDefault("eventType", "SCHEDULE_DUE"))));
        form.withChild(formLabel("Agent ID", TextInput.create("agentId")
            .withValue(values.getOrDefault("agentId", ""))));
        form.withChild(formLabel("Filter JSON (optional)", TextInput.create("filterJson")
            .withValue(values.getOrDefault("filterJson", ""))));
        form.withChild(formLabel("Assignment Template (JSON)", TextArea.create("assignmentTemplate")
            .withRows(5).withValue(values.getOrDefault("assignmentTemplate", "{}"))));

        Div toolActions = new Div();
        toolActions.withClass("tool-actions");
        toolActions.withChild(Button.create("Create").withClass("orch-primary small")
            .withAttribute("type", "submit"));
        toolActions.withChild(Button.create("Simulate").withClass("orch-secondary small")
            .withAttribute("type", "button")
            .withAttribute("hx-post", "/projects/_detail/" + e(projectId) + "/events/_simulate")
            .withAttribute("hx-target", "#project-events-simulation")
            .withAttribute("hx-swap", "innerHTML")
            .withAttribute("hx-include", "closest form"));
        form.withChild(toolActions);

        panel.withChild(form);
        Div simDiv = new Div();
        simDiv.withId("project-events-simulation");
        panel.withChild(simDiv);
        return panel.render();
    }

    // ── Simulation outputs ──

    public String scheduleSimulationResult(ScheduleSimulation sim) {
        Div panel = new Div();
        panel.withId("project-schedules-simulation");
        panel.withClass("orch-panel simulation-panel");
        panel.withChild(Header.H4("Simulation Result (Non-Executing)"));
        panel.withChild(chip("State: " + dv(sim.state())));
        if (sim.error() != null) {
            panel.withChild(errorDiv(dv(sim.error())));
        }
        if (sim.misfireDetected()) {
            panel.withChild(warningDiv("Misfire detected. Missed slots: " + sim.missedSlots()));
        }
        if (sim.nextSlots() != null && !sim.nextSlots().isEmpty()) {
            Div slots = new Div();
            slots.withClass("project-simulation-slots");
            slots.withChild(strong("Next slots:"));
            for (String slot : sim.nextSlots()) {
                slots.withChild(div(slot));
            }
            panel.withChild(slots);
        }
        return panel.render();
    }

    public String reactionSimulationResult(ReactionSimulation sim) {
        Div panel = new Div();
        panel.withId("project-events-simulation");
        panel.withClass("orch-panel simulation-panel");
        panel.withChild(Header.H4("Simulation Result (Non-Executing)"));
        panel.withChild(chip("State: " + dv(sim.state())));
        if (sim.suppressionReason() != null) {
            panel.withChild(warningDiv("Suppression: " + dv(sim.suppressionReason())));
        }
        if (sim.actionDescription() != null) {
            panel.withChild(newParagraph(dv(sim.actionDescription())));
        }
        panel.withChild(div("Filter matches: " + sim.filterMatches()
            + " | Loop: " + sim.loopDetected()
            + " | Depth: " + sim.depthExceeded()
            + " | Rate: " + sim.rateExceeded()
            + " (" + sim.recentDispatchCount() + " recent)"));
        return panel.render();
    }

    // ── Shared HTML helpers ──

    private static Div emptyState(String message) {
        Div div = new Div();
        div.withClass("dashboard-empty");
        div.withInnerText(message);
        return div;
    }

    private static Div emptyDiv(String message) {
        Div div = new Div();
        div.withClass("dashboard-empty");
        div.withInnerText(message);
        return div;
    }

    private static Div errorDiv(String message) {
        Div div = new Div();
        div.withClass("orch-error");
        div.withInnerText(message);
        return div;
    }

    private static Div warningDiv(String message) {
        Div div = new Div();
        div.withClass("orch-warning");
        div.withInnerText(message);
        return div;
    }

    private static Div div(String text) {
        Div div = new Div();
        div.withInnerText(text);
        return div;
    }

    private static HtmlTag span(String text) {
        HtmlTag span = new HtmlTag("span");
        span.withInnerText(text);
        return span;
    }

    private static HtmlTag strong(String text) {
        HtmlTag s = new HtmlTag("strong");
        s.withInnerText(text);
        return s;
    }

    private static HtmlTag chip(String text) {
        HtmlTag s = new HtmlTag("span");
        s.withClass("orch-chip");
        s.withInnerText(text);
        return s;
    }

    private static HtmlTag statusChip(boolean enabled) {
        HtmlTag s = new HtmlTag("span");
        if (enabled) {
            s.withClass("orch-status-chip active");
            s.withInnerText("Enabled");
        } else {
            s.withClass("orch-status-chip disabled");
            s.withInnerText("Disabled");
        }
        return s;
    }

    private static HtmlTag muted(String text) {
        HtmlTag s = new HtmlTag("span");
        s.withClass("workspace-file-muted");
        s.withInnerText(text);
        return s;
    }

    private static Paragraph newParagraph(String text) {
        Paragraph p = new Paragraph(text);
        p.withClass("orch-muted-note");
        return p;
    }

    private static HtmlTag formLabel(String text, HtmlTag control) {
        HtmlTag label = new HtmlTag("label");
        HtmlTag span = new HtmlTag("span");
        span.withInnerText(text);
        label.withChild(span);
        label.withChild(control);
        return label;
    }

    // ── Utility ──

    private static String e(String value) {
        return URLEncoder.encode(value != null ? value : "", StandardCharsets.UTF_8)
            .replace("+", "%20");
    }

    private static String dv(String value) {
        return StringUtils.hasText(value) ? value : "\u2014";
    }

    private static String itemContext(ScheduleDefinition def) {
        StringBuilder sb = new StringBuilder();
        if (def.mode() == ScheduleMode.CRON) {
            sb.append("CRON: ").append(dv(def.cronExpression()));
        } else if (def.fireAt() != null) {
            sb.append("At: ").append(def.fireAt().toString());
        }
        sb.append(" \u00b7 v").append(def.revision());
        return sb.toString();
    }
}
