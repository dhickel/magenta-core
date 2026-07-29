package io.mindspice.magenta2.api.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationStatus;
import io.mindspice.magenta2.ai.orchestration.runtime.Project;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectAgentMembership;
import io.mindspice.magenta2.ai.orchestration.runtime.WorkAssignment;
import io.mindspice.simplypages.builders.ShellBuilder;
import io.mindspice.simplypages.builders.ShellTemplate;
import io.mindspice.simplypages.components.Div;
import io.mindspice.simplypages.components.Header;
import io.mindspice.simplypages.components.Paragraph;
import io.mindspice.simplypages.components.forms.Button;
import io.mindspice.simplypages.core.Component;
import io.mindspice.simplypages.core.HtmlTag;

import org.springframework.util.StringUtils;

/**
 * Reusable project console rendering fragments.
 *
 * <p>Renders project chooser, grouped tab navigation, tab shell, and
 * controlled unavailable/empty/error states. Owns no scope resolution,
 * persistence, or authorization logic.</p>
 */
@org.springframework.stereotype.Component
public final class ProjectConsoleFragments {

    // ── Tab registry ──
    public record ProjectTab(String group, String label, String key, boolean available) {
        public String route(String projectId) {
            return "/projects/_detail/" + e(projectId) + "/" + key;
        }

        public String hostId() {
            return "project-tab-panel";
        }
    }

    static final List<ProjectTab> TAB_REGISTRY = List.of(
        new ProjectTab("overview",   "Dashboard",  "dashboard",  true),
        new ProjectTab("work",       "Queue",      "queue",      true),
        new ProjectTab("work",       "Tasks",      "tasks",      true),
        new ProjectTab("work",       "Jobs",       "jobs",       false),
        new ProjectTab("work",       "Outputs",    "outputs",    false),
        new ProjectTab("context",    "Files",      "files",      false),
        new ProjectTab("context",    "Chat",       "chat",       false),
        new ProjectTab("automation", "Schedules",  "schedules",  false),
        new ProjectTab("automation", "Events",     "events",     false),
        new ProjectTab("admin",      "Members",    "members",    true),
        new ProjectTab("admin",      "Settings",   "settings",   true)
    );

    /** Ordered group keys for rendering. */
    static final List<String> GROUP_ORDER = List.of("overview", "work", "context", "automation", "admin");

    static final Map<String, String> GROUP_LABELS = Map.of(
        "overview",   "Overview",
        "work",       "Work",
        "context",    "Context",
        "automation", "Automation",
        "admin",      "Administration"
    );

    /** Return the ordered list of project-console tabs for the registry. */
    public List<ProjectTab> projectTabs() {
        return TAB_REGISTRY;
    }

    public Optional<ProjectTab> findTab(String key) {
        return TAB_REGISTRY.stream().filter(tab -> tab.key().equals(key)).findFirst();
    }

    public ProjectTab defaultTab() {
        return findTab("queue").orElseThrow();
    }

    // ── Chooser empty state ──
    public String projectChooserEmptyState() {
        return new Div().withClass("orch-panel")
            .withChild(new Div().withClass("dashboard-empty")
                .withInnerText("Select a project from the list or create a new one."))
            .render();
    }

    // ── Loading placeholder ──
    public String loadingPlaceholder() {
        return new Div().withClass("dashboard-empty").withInnerText("Loading...").render();
    }

    // ── Project detail shell ──
    public Component projectDetailShell(Project project) {
        return projectDetailShell(project, defaultTab());
    }

    public Component projectDetailShell(Project project, ProjectTab selectedTab) {
        return new Div()
            .withClass("entity-detail-layout entity-detail-layout-full project-detail-shell")
            .withAttribute("data-project-id", project.id())
            .withChild(new Div().withClass("entity-detail-main")
                .withChild(new Div().withClass("project-detail-heading")
                    .withChild(new Div().withClass("entity-title")
                        .withChild(Header.H2(displayValue(project.name())))
                        .withChild(new Paragraph(StringUtils.hasText(project.description())
                            ? project.description() : "No description")))
                    .withChild(new HtmlTag("span").withClass("orch-status-chip tracked").withInnerText("Tracked")))
                .withChild(groupedTabNav(project.id(), selectedTab.key()))
                .withChild(new Div().withId("project-tab-panel").withClass("orch-panel")
                    .withAttribute("hx-get", selectedTab.route(project.id()))
                    .withAttribute("hx-trigger", "load")
                    .withAttribute("hx-swap", "innerHTML")
                    .withChild(new Div().withClass("dashboard-empty").withInnerText("Loading..."))));
    }

    // ── Grouped tab navigation ──
    public Component groupedTabNav(String projectId, String selectedTabKey) {
        HtmlTag nav = new HtmlTag("nav")
            .withClass("orch-tabs project-tabs project-tabs-grouped")
            .withAttribute("aria-label", "Project views");

        Map<String, List<ProjectTab>> groups = new LinkedHashMap<>();
        for (String group : GROUP_ORDER) {
            List<ProjectTab> tabs = TAB_REGISTRY.stream()
                .filter(t -> t.group().equals(group))
                .toList();
            if (!tabs.isEmpty()) groups.put(group, tabs);
        }

        for (Map.Entry<String, List<ProjectTab>> entry : groups.entrySet()) {
            String groupKey = entry.getKey();
            String groupLabel = GROUP_LABELS.getOrDefault(groupKey, groupKey);
            HtmlTag groupWrapper = new HtmlTag("div").withClass("project-tab-group")
                .withChild(new HtmlTag("span").withClass("project-tab-group-label").withInnerText(groupLabel));
            for (ProjectTab tab : entry.getValue()) {
                Button button = Button.create(tab.label());
                if (tab.key().equals(selectedTabKey)) button.withClass("active");
                if (!tab.available()) button.withClass("tab-unavailable");
                button.withAttribute("data-project-tab", tab.key())
                    .withAttribute("data-tab-button", "true")
                    .withAttribute("data-project-tab-group", tab.group())
                    .withAttribute("data-tab-available", String.valueOf(tab.available()))
                    .withAttribute("aria-selected", String.valueOf(tab.key().equals(selectedTabKey)))
                    .withAttribute("hx-get", tab.route(projectId))
                    .withAttribute("hx-target", "#project-tab-panel")
                    .withAttribute("hx-swap", "innerHTML")
                    .withAttribute("data-htmx-error-swap", "true");
                groupWrapper.withChild(button);
            }
            nav.withChild(groupWrapper);
        }
        return nav;
    }

    // ── Controlled unavailable fragment ──
    public String unavailableTab(String tabLabel, String group) {
        return new Div().withClass("project-unavailable-tab")
            .withChild(Header.H3(tabLabel))
            .withChild(new Div().withClass("project-tab-state unavailable")
                .withChild(new Paragraph(tabLabel + " is not yet available in this release."))
                .withChild(new Paragraph("This surface will be implemented in a future phase. No controls or API are provisioned.")
                    .withClass("orch-muted-note")))
            .render();
    }

    // ── Error fragments ──
    public String errorNotFound(String message) {
        return new Div().withClass("orch-error")
            .withInnerText(message != null ? message : "Project not found.")
            .render();
    }

    public String errorUnavailable(String message) {
        return new Div().withClass("orch-error")
            .withInnerText(message != null ? message : "Project data is temporarily unavailable.")
            .render();
    }

    public String errorBadRequest(String message) {
        return new Div().withClass("orch-error")
            .withInnerText(message).render();
    }

    public String errorConflict(String message) {
        return new Div().withClass("orch-error")
            .withInnerText(message).render();
    }

    // ── Queue-specific controlled states ──
    public String queueEmptyState() {
        return new Div().withClass("dashboard-empty")
            .withInnerText("No queued or active assignments for this project.")
            .render();
    }

    public String queueStaleCursor() {
        return new Div().withId("project-queue-status").withClass("orch-status")
            .withChild(new Paragraph("The cursor has expired. Refresh to reload the current queue state."))
            .withChild(Button.create("Refresh Queue")
                .withClass("orch-primary")
                .withAttribute("hx-get", "")
                .withAttribute("hx-target", "#project-queue-list")
                .withAttribute("hx-swap", "innerHTML"))
            .render();
    }

    // ── Transcript / diagnostics unavailable states ──
    public String transcriptUnavailable(String reason) {
        return new Div().withClass("assignment-diagnostics")
            .withChild(Header.H3("Transcript"))
            .withChild(new Div().withClass("dashboard-empty")
                .withInnerText(reason != null ? reason : "Transcript is unavailable for this assignment."))
            .render();
    }

    public String diagnosticsUnavailable(String reason) {
        return new Div().withClass("assignment-diagnostics")
            .withChild(Header.H3("Diagnostics"))
            .withChild(new Div().withClass("dashboard-empty")
                .withInnerText(reason != null ? reason : "Diagnostics are unavailable for this assignment."))
            .render();
    }

    // ── Shared display helpers ──
    static String displayValue(String value) {
        return StringUtils.hasText(value) ? value : "—";
    }

    static String e(String value) {
        return URLEncoder.encode(value != null ? value : "", StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String nn(String value) {
        return value == null ? "" : value;
    }

    static String escapeAttr(String value) {
        if (value == null) return "";
        return value.replace("\"", "&quot;").replace("&", "&amp;")
            .replace("<", "&lt;").replace(">", "&gt;");
    }
}
