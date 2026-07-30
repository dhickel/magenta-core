package io.mindspice.magenta2.api.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.mindspice.magenta2.ai.orchestration.runtime.Project;
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
 * persistence, or authorization logic. The canonical tab registry,
 * order, labels, and deep-link mapping are defined in {@link AppNavigation}.</p>
 */
@org.springframework.stereotype.Component
public final class ProjectConsoleFragments {

    // ── Tab registry (delegates to AppNavigation) ──

    /** Re-export the canonical tab descriptor from {@link AppNavigation} for controller use. */
    public record ProjectTab(String group, String label, String key, boolean available) {
        public String route(String projectId) {
            return AppNavigation.findTab(key)
                .map(t -> t.route(projectId))
                .orElse("/projects/_detail/" + e(projectId) + "/" + key);
        }

        public String hostId() {
            return "project-tab-panel";
        }

        static ProjectTab from(AppNavigation.ProjectTab t) {
            return new ProjectTab(t.group(), t.label(), t.key(), t.available());
        }
    }

    /** Return the ordered list of project-console tabs. */
    public List<ProjectTab> projectTabs() {
        return AppNavigation.consoleTabs().stream().map(ProjectTab::from).toList();
    }

    /** Lookup a tab by canonical key. */
    public Optional<ProjectTab> findTab(String key) {
        return AppNavigation.findTab(key).map(ProjectTab::from);
    }

    /** Default active tab. */
    public ProjectTab defaultTab() {
        return ProjectTab.from(AppNavigation.defaultTab());
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

    // ── Grouped tab navigation (delegates grouping to AppNavigation) ──
    public Component groupedTabNav(String projectId, String selectedTabKey) {
        HtmlTag nav = new HtmlTag("nav")
            .withClass("orch-tabs project-tabs project-tabs-grouped")
            .withAttribute("aria-label", "Project views");

        Map<String, List<AppNavigation.ProjectTab>> groups = AppNavigation.groupedTabs();

        for (Map.Entry<String, List<AppNavigation.ProjectTab>> entry : groups.entrySet()) {
            String groupKey = entry.getKey();
            String groupLabel = AppNavigation.GROUP_LABELS.getOrDefault(groupKey, groupKey);
            HtmlTag groupWrapper = new HtmlTag("div").withClass("project-tab-group")
                .withChild(new HtmlTag("span").withClass("project-tab-group-label").withInnerText(groupLabel));
            for (AppNavigation.ProjectTab tab : entry.getValue()) {
                boolean isActive = tab.key().equals(selectedTabKey);
                boolean isAvailable = tab.available();
                Button button = Button.create(tab.label());
                if (isActive) button.withClass("active");
                if (!isAvailable) button.withClass("tab-unavailable");
                button.withAttribute("data-project-tab", tab.key())
                    .withAttribute("data-tab-button", "true")
                    .withAttribute("data-project-tab-group", tab.group())
                    .withAttribute("data-tab-available", String.valueOf(isAvailable))
                    .withAttribute("aria-selected", String.valueOf(isActive))
                    .withAttribute("hx-get", tab.route(projectId))
                    .withAttribute("hx-target", "#project-tab-panel")
                    .withAttribute("hx-swap", "innerHTML")
                    .withAttribute("data-htmx-error-swap", "true")
                    .withAttribute("aria-disabled", String.valueOf(!isAvailable));
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
