package io.mindspice.magenta2.api.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import io.mindspice.magenta2.ai.orchestration.tasks.WorkTaskAttempt;
import io.mindspice.magenta2.ai.orchestration.tasks.WorkTaskDefinition;
import io.mindspice.simplypages.components.Div;
import io.mindspice.simplypages.components.Header;
import io.mindspice.simplypages.components.Paragraph;
import io.mindspice.simplypages.components.display.Table;
import io.mindspice.simplypages.components.forms.Button;
import io.mindspice.simplypages.components.forms.Form;
import io.mindspice.simplypages.components.forms.Select;
import io.mindspice.simplypages.components.forms.TextArea;
import io.mindspice.simplypages.components.forms.TextInput;
import io.mindspice.simplypages.core.Component;
import io.mindspice.simplypages.core.HtmlTag;

import org.springframework.util.StringUtils;

/**
 * Stateless SimplyPages renderers for work task console fragments.
 *
 * <p>Renders project-scoped task lists, editors, detail views, promotion
 * controls, submission forms, and attempt history. Owns no scope resolution,
 * persistence, or authorization logic. All values are pre-validated and
 * authorized by the controller.
 */
public final class WorkTaskFragments {

    // ── Stable host IDs ──
    static final String TASKS_LIST_ID = "project-tasks-list";
    static final String TASKS_EDITOR_ID = "project-tasks-editor";
    static final String TASKS_DETAIL_ID = "project-tasks-detail";
    static final String TASKS_SUBMIT_ID = "project-tasks-submit";
    static final String TASKS_STATUS_ID = "project-tasks-status";

    private WorkTaskFragments() {}

    // ════════════════════════════════════════════════════════════════
    //  Task list bound to a project
    // ════════════════════════════════════════════════════════════════

    /**
     * Render the task list panel with header, create button, and list.
     */
    static Component taskListPanel(String projectId, List<WorkTaskRow> rows,
                                    List<AgentOption> agentOptions) {
        Div panel = new Div().withId(TASKS_LIST_ID).withClass("project-tasks-panel");
        panel.withChild(taskHeader(projectId, rows.size()));
        panel.withChild(newTaskButton(projectId));
        panel.withChild(statusHost(""));
        if (rows.isEmpty()) {
            panel.withChild(emptyState());
        } else {
            panel.withChild(taskTable(projectId, rows));
        }
        return panel;
    }

    /**
     * Render just the task list (for OOB refresh).
     */
    static Component taskList(String projectId, List<WorkTaskRow> rows) {
        Div host = new Div().withId(TASKS_LIST_ID).withClass("project-tasks-panel")
            .withAttribute("hx-swap-oob", "outerHTML");
        host.withChild(taskHeader(projectId, rows.size()));
        host.withChild(newTaskButton(projectId));
        host.withChild(statusHost(""));
        if (rows.isEmpty()) {
            host.withChild(emptyState());
        } else {
            host.withChild(taskTable(projectId, rows));
        }
        return host;
    }

    private static Component taskHeader(String projectId, int count) {
        return new Div().withClass("project-tasks-header")
            .withChild(Header.H2("Tasks"))
            .withChild(new Paragraph(count + " task" + (count != 1 ? "s" : "")
                + " tracked for this project.").withClass("orch-muted-note"));
    }

    private static Component newTaskButton(String projectId) {
        return Button.create("New Task").withClass("orch-primary")
            .withAttribute("hx-get", taskRoute(projectId) + "/_editor/_new")
            .withAttribute("hx-target", "#" + TASKS_EDITOR_ID)
            .withAttribute("hx-swap", "outerHTML")
            .withAttribute("data-htmx-error-swap", "true");
    }

    private static Component emptyState() {
        return new Div().withClass("dashboard-empty")
            .withInnerText("No work tasks yet. Create one to start tracking executable work.");
    }

    private static Component taskTable(String projectId, List<WorkTaskRow> rows) {
        Table table = Table.create().withClass("dashboard-table project-tasks-table")
            .withHeaders("Title", "Priority", "Agent", "Status", "Version", "Updated", "");
        for (WorkTaskRow row : rows) {
            table.addRow(
                taskTitleCell(projectId, row),
                new HtmlTag("span").withInnerText(String.valueOf(row.defaultPriority)),
                new HtmlTag("span").withInnerText(dv(row.preferredAgentId)),
                statusChip(row.derivedStatus, row.promotedPlanId == null),
                new HtmlTag("span").withInnerText("v" + row.definitionVersion),
                new HtmlTag("span").withInnerText(since(row.updatedAt)),
                rowActions(projectId, row));
        }
        return table;
    }

    private static Component taskTitleCell(String projectId, WorkTaskRow row) {
        Div cell = new Div().withClass("project-task-title");
        if (row.pin) {
            cell.withChild(new HtmlTag("span").withClass("orch-chip").withInnerText("PINNED"));
        }
        Button titleBtn = Button.create(dv(row.title)).withClass("button-link");
        titleBtn.withAttribute("hx-get", taskRoute(projectId) + "/" + e(row.id))
            .withAttribute("hx-target", "#" + TASKS_DETAIL_ID)
            .withAttribute("hx-swap", "outerHTML")
            .withAttribute("data-htmx-error-swap", "true");
        return cell.withChild(titleBtn);
    }

    private static Component rowActions(String projectId, WorkTaskRow row) {
        Div actions = new Div().withClass("orch-actions");
        actions.withChild(Button.create("View").withClass("button-secondary small")
            .withAttribute("hx-get", taskRoute(projectId) + "/" + e(row.id))
            .withAttribute("hx-target", "#" + TASKS_DETAIL_ID)
            .withAttribute("hx-swap", "outerHTML")
            .withAttribute("data-htmx-error-swap", "true"));
        if (!row.archived) {
            actions.withChild(Button.create("Edit").withClass("button-secondary small")
                .withAttribute("hx-get", taskRoute(projectId) + "/" + e(row.id) + "/edit")
                .withAttribute("hx-target", "#" + TASKS_EDITOR_ID)
                .withAttribute("hx-swap", "outerHTML")
                .withAttribute("data-htmx-error-swap", "true"));
        }
        return actions;
    }

    // ════════════════════════════════════════════════════════════════
    //  Editor host (empty by default)
    // ════════════════════════════════════════════════════════════════

    static Component editorHost() {
        return new Div().withId(TASKS_EDITOR_ID).withClass("project-tasks-editor");
    }

    static Component submitHost() {
        return new Div().withId(TASKS_SUBMIT_ID).withClass("project-tasks-submit");
    }

    static Component detailHost() {
        return new Div().withId(TASKS_DETAIL_ID).withClass("project-tasks-detail");
    }

    // ════════════════════════════════════════════════════════════════
    //  Create task form
    // ════════════════════════════════════════════════════════════════

    static Component createForm(String projectId, java.util.Map<String, String> values,
                                 String error, List<AgentOption> agentOptions) {
        Div editor = editorPanel("New Task");
        if (StringUtils.hasText(error)) {
            editor.withChild(new Div().withClass("orch-error").withInnerText(error));
        }
        Form form = Form.create()
            .withHxPost(taskRoute(projectId) + "/_editor")
            .withHxTarget("#" + TASKS_EDITOR_ID)
            .withHxSwap("outerHTML")
            .withClass("orch-form-stack");
        form.withAttribute("data-htmx-error-swap", "true");
        form.withChild(hidden("operationKey", values.getOrDefault("operationKey", java.util.UUID.randomUUID().toString())));
        form.withChild(label("Title",
            TextInput.create("title").withValue(values.getOrDefault("title", ""))
                .withAttribute("required", "required")));
        form.withChild(label("Description",
            TextArea.create("description").withRows(3)
                .withValue(values.getOrDefault("description", ""))));
        form.withChild(label("Default Priority",
            TextInput.create("defaultPriority").withValue(values.getOrDefault("defaultPriority", "0"))
                .withAttribute("type", "number").withAttribute("min", "0")));
        form.withChild(label("Pin",
            checkbox("pin", "true".equals(values.get("pin")))));
        form.withChild(label("Preferred Agent",
            agentSelect("preferredAgentId", values.get("preferredAgentId"), agentOptions, true)));
        form.withChild(new Div().withClass("orch-actions")
            .withChild(Button.create("Create Task").withClass("orch-primary")
                .withAttribute("type", "submit"))
            .withChild(Button.create("Cancel").withClass("button-secondary")
                .withAttribute("type", "button")
                .withAttribute("hx-get", taskRoute(projectId) + "/_editor/_cancel")
                .withAttribute("hx-target", "#" + TASKS_EDITOR_ID)
                .withAttribute("hx-swap", "outerHTML")));
        return editor.withChild(form);
    }

    // ════════════════════════════════════════════════════════════════
    //  Edit task form (CAS-aware)
    // ════════════════════════════════════════════════════════════════

    static Component editForm(String projectId, WorkTaskDefinition task,
                               java.util.Map<String, String> values, String error,
                               List<AgentOption> agentOptions) {
        Div editor = editorPanel("Edit Task");
        if (StringUtils.hasText(error)) {
            editor.withChild(new Div().withClass("orch-error").withInnerText(error));
        }
        Form form = Form.create()
            .withHxPut(taskRoute(projectId) + "/" + e(task.id()))
            .withHxTarget("#" + TASKS_EDITOR_ID)
            .withHxSwap("outerHTML")
            .withClass("orch-form-stack");
        form.withAttribute("data-htmx-error-swap", "true");
        form.withChild(hidden("expectedVersion", String.valueOf(task.definitionVersion())));
        form.withChild(hidden("operationKey", values.getOrDefault("operationKey", java.util.UUID.randomUUID().toString())));
        String titleVal = values.containsKey("title") ? values.get("title") : task.title();
        form.withChild(label("Title",
            TextInput.create("title").withValue(titleVal)
                .withAttribute("required", "required")));
        String descVal = values.containsKey("description") ? values.get("description") : nn(task.description());
        form.withChild(label("Description",
            TextArea.create("description").withRows(3).withValue(descVal)));
        String prioVal = values.containsKey("defaultPriority")
            ? values.get("defaultPriority") : String.valueOf(task.defaultPriority());
        form.withChild(label("Default Priority",
            TextInput.create("defaultPriority").withValue(prioVal)
                .withAttribute("type", "number").withAttribute("min", "0")));
        boolean pinVal = values.containsKey("pin") ? "true".equals(values.get("pin")) : task.pin();
        form.withChild(label("Pin", checkbox("pin", pinVal)));
        String agentVal = values.containsKey("preferredAgentId")
            ? values.get("preferredAgentId") : nn(task.preferredAgentId());
        form.withChild(label("Preferred Agent",
            agentSelect("preferredAgentId", agentVal, agentOptions, true)));
        form.withChild(new Div().withClass("orch-actions")
            .withChild(Button.create("Save").withClass("orch-primary")
                .withAttribute("type", "submit"))
            .withChild(Button.create("Cancel").withClass("button-secondary")
                .withAttribute("type", "button")
                .withAttribute("hx-get", taskRoute(projectId) + "/_editor/_cancel")
                .withAttribute("hx-target", "#" + TASKS_EDITOR_ID)
                .withAttribute("hx-swap", "outerHTML")));
        return editor.withChild(form);
    }

    // ════════════════════════════════════════════════════════════════
    //  Task detail with attempts
    // ════════════════════════════════════════════════════════════════

    static Div taskDetail(String projectId, WorkTaskDefinition task,
                                 List<WorkTaskAttempt> attempts,
                                 List<AttemptDisplay> attemptDisplays,
                                 boolean projectArchived, boolean planAvailable) {
        Div detail = new Div().withId(TASKS_DETAIL_ID).withClass("project-tasks-detail");
        detail.withChild(Header.H3(dv(task.title())));

        // Task facts
        Div facts = new Div().withClass("orch-form-grid");
        facts.withChild(factItem("ID", task.id()));
        facts.withChild(factItem("Title", dv(task.title())));
        facts.withChild(factItem("Description", dv(task.description())));
        facts.withChild(factItem("Priority", String.valueOf(task.defaultPriority())));
        facts.withChild(factItem("Pin", task.pin() ? "Yes" : "No"));
        facts.withChild(factItem("Preferred Agent", dv(task.preferredAgentId())));
        facts.withChild(factItem("Version", "v" + task.definitionVersion()));
        facts.withChild(factItem("Project", dv(task.projectId())));
        facts.withChild(factItem("Created", task.createdAt() == null ? "—" : task.createdAt().toString()));
        facts.withChild(factItem("Updated", task.updatedAt() == null ? "—" : task.updatedAt().toString()));
        detail.withChild(facts);

        // Promotion status
        Div promoSection = new Div().withClass("project-task-promo-section");
        if (task.promotedPlanId() != null) {
            promoSection.withChild(new HtmlTag("span").withClass("orch-status-chip active")
                .withInnerText("Promoted"));
            promoSection.withChild(new HtmlTag("code").withInnerText(task.promotedPlanId()));
            if (planAvailable) {
                promoSection.withChild(new HtmlTag("a")
                    .withClass("button button-secondary small")
                    .withAttribute("href", "/plans/" + e(task.promotedPlanId()))
                    .withInnerText("View Plan"));
            } else {
                promoSection.withChild(new HtmlTag("span").withClass("orch-chip")
                    .withInnerText("Plan unavailable"));
            }
        } else {
            promoSection.withChild(new HtmlTag("span").withClass("orch-chip")
                .withInnerText("Not promoted"));
            if (!projectArchived) {
                promoSection.withChild(promoteButton(projectId, task));
            }
        }
        detail.withChild(promoSection);

        // Control buttons
        if (!projectArchived) {
            Div controls = new Div().withClass("orch-actions");
            if (task.promotedPlanId() != null && planAvailable) {
                controls.withChild(submitButton(projectId, task));
                if (!attempts.isEmpty() && !hasActiveAttempt(attemptDisplays)) {
                    controls.withChild(retryButton(projectId, task));
                }
            }
            controls.withChild(Button.create("Edit").withClass("button-secondary")
                .withAttribute("hx-get", taskRoute(projectId) + "/" + e(task.id()) + "/edit")
                .withAttribute("hx-target", "#" + TASKS_EDITOR_ID)
                .withAttribute("hx-swap", "outerHTML")
                .withAttribute("data-htmx-error-swap", "true"));
            if (hasActiveAttempt(attemptDisplays)) {
                controls.withChild(new HtmlTag("span").withClass("orch-chip")
                    .withInnerText("Active attempt in progress"));
            }
            detail.withChild(controls);
        } else if (projectArchived) {
            detail.withChild(new Div().withClass("orch-error")
                .withInnerText("Project is archived — task controls are disabled."));
        }

        // Attempt history
        detail.withChild(attemptHistory(projectId, task, attempts, attemptDisplays, planAvailable));

        // Back to list
        detail.withChild(Button.create("Back to list").withClass("button-secondary")
            .withAttribute("hx-get", taskRoute(projectId))
            .withAttribute("hx-target", "#" + TASKS_LIST_ID)
            .withAttribute("hx-swap", "outerHTML")
            .withAttribute("data-htmx-error-swap", "true"));

        return detail;
    }

    // ════════════════════════════════════════════════════════════════
    //  Submit form (explicit executor, workspace choice)
    // ════════════════════════════════════════════════════════════════

    static Component submitForm(String projectId, WorkTaskDefinition task,
                                 List<AgentOption> agentOptions,
                                 java.util.Map<String, String> values, String error) {
        Div editor = submitPanel("Submit Task for Execution");
        if (StringUtils.hasText(error)) {
            editor.withChild(new Div().withClass("orch-error").withInnerText(error));
        }
        Form form = Form.create()
            .withHxPost(taskRoute(projectId) + "/" + e(task.id()) + "/submit")
            .withHxTarget("#" + TASKS_SUBMIT_ID)
            .withHxSwap("outerHTML")
            .withClass("orch-form-stack");
        form.withAttribute("data-htmx-error-swap", "true");
        form.withChild(hidden("operationKey", values.getOrDefault("operationKey", java.util.UUID.randomUUID().toString())));
        form.withChild(label("Agent",
            agentSelect("agentId", values.getOrDefault("agentId", nn(task.preferredAgentId())),
                agentOptions, false)));
        form.withChild(label("Run Display Name",
            TextInput.create("runDisplayName")
                .withValue(values.getOrDefault("runDisplayName", task.title()))
                .withPlaceholder(task.title())));
        form.withChild(label("Workspace Type",
            TextInput.create("workspaceType")
                .withValue(values.getOrDefault("workspaceType", ""))
                .withPlaceholder("Optional: agent or user")));
        form.withChild(label("Workspace Key",
            TextInput.create("workspaceKey")
                .withValue(values.getOrDefault("workspaceKey", ""))
                .withPlaceholder("Agent ID or user name")));
        form.withChild(new Div().withClass("orch-actions")
            .withChild(Button.create("Submit").withClass("orch-primary")
                .withAttribute("type", "submit"))
            .withChild(Button.create("Cancel").withClass("button-secondary")
                .withAttribute("type", "button")
                .withAttribute("hx-get", taskRoute(projectId) + "/_editor/_cancel?host=submit")
                .withAttribute("hx-target", "#" + TASKS_SUBMIT_ID)
                .withAttribute("hx-swap", "outerHTML")));
        return editor.withChild(form);
    }

    // ════════════════════════════════════════════════════════════════
    //  Controlled states
    // ════════════════════════════════════════════════════════════════

    static Component errorNotFound(String message) {
        return new Div().withClass("orch-error")
            .withInnerText(message != null ? message : "Task not found.");
    }

    static Component errorUnavailable(String message) {
        return new Div().withClass("orch-error")
            .withInnerText(message != null ? message : "Task data is temporarily unavailable.");
    }

    static Component errorBadRequest(String message) {
        return new Div().withClass("orch-error").withInnerText(message);
    }

    static Component errorConflict(String message) {
        return new Div().withClass("orch-error").withInnerText(message);
    }

    static Component statusMessage(String message, boolean error) {
        Div host = new Div().withId(TASKS_STATUS_ID).withClass("project-tasks-status")
            .withAttribute("hx-swap-oob", "outerHTML");
        host.withChild(new Div()
            .withClass(error ? "orch-error" : "orch-status")
            .withInnerText(message));
        return host;
    }

    // ════════════════════════════════════════════════════════════════
    //  Private helpers
    // ════════════════════════════════════════════════════════════════

    private static Div editorPanel(String title) {
        return new Div().withId(TASKS_EDITOR_ID).withClass("project-tasks-editor orch-panel")
            .withChild(Header.H3(title));
    }

    private static Div submitPanel(String title) {
        return new Div().withId(TASKS_SUBMIT_ID).withClass("project-tasks-submit orch-panel")
            .withChild(Header.H3(title));
    }

    private static Component statusHost(String message) {
        return new Div().withId(TASKS_STATUS_ID).withClass("project-tasks-status")
            .withChild(StringUtils.hasText(message)
                ? new Div().withClass("orch-status").withInnerText(message)
                : new Div());
    }

    private static Component promoteButton(String projectId, WorkTaskDefinition task) {
        return Button.create("Promote to Plan").withClass("orch-primary")
            .withAttribute("hx-post", taskRoute(projectId) + "/" + e(task.id()) + "/promote")
            .withAttribute("hx-vals", "{\"expectedVersion\":" + task.definitionVersion()
                + ",\"operationKey\":\"" + java.util.UUID.randomUUID() + "\"}")
            .withAttribute("hx-target", "#" + TASKS_DETAIL_ID)
            .withAttribute("hx-swap", "outerHTML")
            .withAttribute("data-htmx-error-swap", "true");
    }

    private static Component submitButton(String projectId, WorkTaskDefinition task) {
        return Button.create("Submit for Execution").withClass("orch-primary")
            .withAttribute("hx-get", taskRoute(projectId) + "/" + e(task.id()) + "/submit")
            .withAttribute("hx-target", "#" + TASKS_SUBMIT_ID)
            .withAttribute("hx-swap", "outerHTML")
            .withAttribute("data-htmx-error-swap", "true");
    }

    private static Component retryButton(String projectId, WorkTaskDefinition task) {
        return Button.create("Retry").withClass("button-secondary")
            .withAttribute("hx-post", taskRoute(projectId) + "/" + e(task.id()) + "/retry")
            .withAttribute("hx-vals", "{\"operationKey\":\"" + java.util.UUID.randomUUID() + "\"}")
            .withAttribute("hx-target", "#" + TASKS_DETAIL_ID)
            .withAttribute("hx-swap", "outerHTML")
            .withAttribute("hx-confirm", "Retry this task? A new execution attempt will be created.")
            .withAttribute("data-htmx-error-swap", "true");
    }

    private static Component attemptHistory(String projectId, WorkTaskDefinition task,
                                             List<WorkTaskAttempt> attempts,
                                             List<AttemptDisplay> displays,
                                             boolean planAvailable) {
        Div section = new Div().withClass("project-task-attempts");
        section.withChild(Header.H4("Execution History"));
        if (displays.isEmpty()) {
            section.withChild(new Div().withClass("dashboard-empty")
                .withInnerText("No execution attempts yet."));
            return section;
        }
        Table table = Table.create().withClass("dashboard-table project-tasks-attempt-table")
            .withHeaders("#", "Assignment", "Status", "Created");
        for (int i = 0; i < displays.size(); i++) {
            AttemptDisplay d = displays.get(i);
            WorkTaskAttempt a = i < attempts.size() ? attempts.get(i) : null;
            table.addRow(
                new HtmlTag("span").withInnerText(String.valueOf(d.attemptOrder)),
                assignmentLink(projectId, a, d),
                statusChip(d.assignmentStatus, false),
                new HtmlTag("span").withInnerText(
                    a != null && a.createdAt() != null ? a.createdAt().toString() : "—"));
        }
        return section.withChild(table);
    }

    private static Component assignmentLink(String projectId, WorkTaskAttempt a, AttemptDisplay d) {
        if (a == null || a.assignmentId() == null) {
            return new HtmlTag("code").withInnerText(dv(d.assignmentId));
        }
        return new HtmlTag("a").withClass("button-link")
            .withAttribute("href", "/projects/_detail/" + e(projectId) + "/queue/" + e(a.assignmentId()))
            .withInnerText(dv(a.assignmentId()));
    }

    private static Component statusChip(String status, boolean unpromoted) {
        if (!StringUtils.hasText(status)) {
            if (unpromoted) {
                return new HtmlTag("span").withClass("orch-chip").withInnerText("Unpromoted");
            }
            return new HtmlTag("span").withClass("orch-chip").withInnerText("—");
        }
        String s = status.toLowerCase();
        boolean terminal = s.equals("completed") || s.equals("cancelled") || s.equals("failed")
            || s.equals("needs_review");
        String css = terminal ? "orch-status-chip disabled" : "orch-status-chip active";
        return new HtmlTag("span").withClass(css).withInnerText(status);
    }

    private static Component factItem(String label, String value) {
        return new Div().withClass("agent-meta-item")
            .withChild(new HtmlTag("span").withClass("agent-meta-label").withInnerText(label))
            .withChild(new HtmlTag("span").withClass("agent-meta-value").withInnerText(value));
    }

    private static Component label(String text, Component control) {
        return new HtmlTag("label")
            .withChild(new HtmlTag("span").withInnerText(text))
            .withChild(control);
    }

    private static Component checkbox(String name, boolean checked) {
        HtmlTag input = new HtmlTag("input")
            .withAttribute("type", "checkbox")
            .withAttribute("name", name)
            .withAttribute("value", "true");
        if (checked) {
            input.withAttribute("checked", "checked");
        }
        return input;
    }

    private static HtmlTag hidden(String name, String value) {
        return new HtmlTag("input").withAttribute("type", "hidden")
            .withAttribute("name", name).withAttribute("value", value);
    }

    private static Select agentSelect(String name, String selected,
                                       List<AgentOption> agentOptions, boolean optional) {
        Select select = Select.create(name);
        if (optional) {
            select.addOption("", "-- none --", !StringUtils.hasText(selected));
        }
        for (AgentOption opt : agentOptions) {
            select.addOption(opt.id, dv(opt.label), opt.id.equals(selected));
        }
        if (StringUtils.hasText(selected) && agentOptions.stream().noneMatch(o -> o.id.equals(selected))) {
            select.addOption(selected, dv(selected), true);
        }
        return select;
    }

    private static String taskRoute(String projectId) {
        return "/projects/_detail/" + e(projectId) + "/tasks";
    }

    private static boolean hasActiveAttempt(List<AttemptDisplay> displays) {
        return displays.stream().anyMatch(d -> {
            String s = d.assignmentStatus;
            return s != null && !s.isEmpty()
                && !(s.equalsIgnoreCase("CANCELLED") || s.equalsIgnoreCase("FAILED")
                    || s.equalsIgnoreCase("COMPLETED") || s.equalsIgnoreCase("NEEDS_REVIEW"));
        });
    }

    private static String since(Instant value) {
        if (value == null) return "—";
        long diff = Instant.now().toEpochMilli() - value.toEpochMilli();
        long seconds = diff / 1000;
        if (seconds < 60) return seconds + "s ago";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m ago";
        long hours = minutes / 60;
        return hours < 24 ? hours + "h ago" : (hours / 24) + "d ago";
    }

    private static String dv(String value) {
        return ProjectConsoleFragments.displayValue(value);
    }

    private static String e(String value) {
        return ProjectConsoleFragments.e(value);
    }

    private static String nn(String value) {
        return ProjectConsoleFragments.nn(value);
    }

    // ════════════════════════════════════════════════════════════════
    //  DTOs
    // ════════════════════════════════════════════════════════════════

    /** Row for the task list table. */
    public record WorkTaskRow(
        String id,
        String title,
        String description,
        String projectId,
        boolean pin,
        int defaultPriority,
        String preferredAgentId,
        String promotedPlanId,
        int definitionVersion,
        String derivedStatus,
        boolean archived,
        Instant updatedAt
    ) {}

    /** Agent selection option. */
    public record AgentOption(String id, String label) {}

    /** Attempt display row. */
    public record AttemptDisplay(
        String assignmentId,
        int attemptOrder,
        String assignmentStatus
    ) {}
}
