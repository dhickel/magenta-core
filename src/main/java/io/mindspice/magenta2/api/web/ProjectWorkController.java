package io.mindspice.magenta2.api.web;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentControlService;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentControlService.OperationConflictException;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentControlService.StaleRevisionException;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentQueryService;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentQueryService.AssignmentListEntry;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentQueryService.CursorPage;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentQueryService.CursorSnapshot;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentQueryService.InvalidCursorException;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentQueryService.ProjectScope;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentService;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentService.AssignmentDiagnostics;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentService.AssignmentTranscript;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationStatus;
import io.mindspice.magenta2.ai.orchestration.runtime.Project;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectOperationsAccessService;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectOperationsAccessService.ProjectAccessDeniedException;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectService;
import io.mindspice.magenta2.ai.orchestration.runtime.WorkAssignment;
import io.mindspice.simplypages.components.Div;
import io.mindspice.simplypages.components.Header;
import io.mindspice.simplypages.components.Paragraph;
import io.mindspice.simplypages.components.display.Table;
import io.mindspice.simplypages.components.forms.Button;
import io.mindspice.simplypages.components.forms.Form;
import io.mindspice.simplypages.components.forms.TextInput;
import io.mindspice.simplypages.core.Component;
import io.mindspice.simplypages.core.HtmlTag;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Project Queue web adapter over the canonical Phase 01 assignment projection.
 * All row resolution is sealed by {@link ProjectScope}; this controller never
 * reads repositories or renders raw assignment/runtime payloads.
 */
@Controller
public class ProjectWorkController {
    private static final int PAGE_SIZE = 25;
    private static final int SNAPSHOT_SIZE = 100;

    private final AssignmentQueryService assignmentQueryService;
    private final AssignmentControlService assignmentControlService;
    private final AssignmentService assignmentService;
    private final ProjectService projectService;
    private final ProjectOperationsAccessService projectAccessService;
    private final ProjectConsoleFragments fragments;

    public ProjectWorkController(AssignmentQueryService assignmentQueryService,
                                 AssignmentControlService assignmentControlService,
                                 AssignmentService assignmentService,
                                 ProjectService projectService,
                                 ProjectOperationsAccessService projectAccessService,
                                 ProjectConsoleFragments fragments) {
        this.assignmentQueryService = assignmentQueryService;
        this.assignmentControlService = assignmentControlService;
        this.assignmentService = assignmentService;
        this.projectService = projectService;
        this.projectAccessService = projectAccessService;
        this.fragments = fragments;
    }

    @GetMapping("/projects/_detail/{projectId}/queue")
    @ResponseBody
    public String activeQueue(@PathVariable String projectId,
                              @RequestParam(value = "cursor", required = false) String encodedCursor,
                              HttpServletResponse response) {
        return queue(projectId, "active", encodedCursor, response);
    }

    @GetMapping("/projects/_detail/{projectId}/queue/history")
    @ResponseBody
    public String historyQueue(@PathVariable String projectId,
                               @RequestParam(value = "cursor", required = false) String encodedCursor,
                               HttpServletResponse response) {
        return queue(projectId, "history", encodedCursor, response);
    }

    private String queue(String projectId, String filter, String encodedCursor,
                         HttpServletResponse response) {
        boolean history = "history".equals(filter);
        try {
            ProjectScope scope = readScope(projectId, history);
            if (StringUtils.hasText(encodedCursor)) {
                CursorSnapshot cursor = CursorSnapshot.decode(encodedCursor);
                CursorPage page = assignmentQueryService.resolveCursor(scope, filter, cursor);
                return queueList(projectId, filter, page.members(), page.nextCursor()).render();
            }
            List<WorkAssignment> snapshot = history
                ? assignmentQueryService.listHistory(scope, SNAPSHOT_SIZE)
                : assignmentQueryService.listActive(scope, SNAPSHOT_SIZE);
            CursorSnapshot cursor = snapshot.isEmpty() ? null
                : assignmentQueryService.snapshotCursor(scope, filter, snapshot);
            List<AssignmentListEntry> page = snapshot.stream().limit(PAGE_SIZE)
                .map(row -> AssignmentListEntry.from(row,
                    assignmentQueryService.computeAllowedActions(row, scope)))
                .toList();
            CursorSnapshot next = snapshot.size() > PAGE_SIZE
                ? assignmentQueryService.resolveCursor(scope, filter, cursor).nextCursor() : null;
            return StringUtils.hasText(encodedCursor)
                ? queueList(projectId, filter, page, next).render()
                : queuePanel(projectId, filter, page, next).render();
        } catch (InvalidCursorException e) {
            boolean stale = isStaleCursor(e);
            response.setStatus(stale ? HttpStatus.CONFLICT.value() : HttpStatus.BAD_REQUEST.value());
            return cursorError(projectId, filter, stale
                ? "The queue changed or the cursor expired. Refresh the queue."
                : "The queue cursor is malformed.").render();
        } catch (ProjectAccessDeniedException e) {
            response.setStatus(projectAccessStatus(projectId));
            return queueError("This project queue view is unavailable.").render();
        } catch (IllegalArgumentException | IllegalStateException e) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return fragments.errorNotFound("Project or assignment not found.");
        } catch (RuntimeException e) {
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            return fragments.errorUnavailable("Project queue is temporarily unavailable.");
        }
    }

    @GetMapping("/projects/_detail/{projectId}/queue/{assignmentId}")
    @ResponseBody
    public String inspector(@PathVariable String projectId, @PathVariable String assignmentId,
                            HttpServletResponse response) {
        try {
            AssignmentListEntry entry = resolveAnyProjectEntry(projectId, assignmentId);
            return inspector(entry).render();
        } catch (ProjectAccessDeniedException e) {
            response.setStatus(projectAccessStatus(projectId));
            return inspectorState("This assignment is unavailable in the active project queue.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return inspectorState("Assignment not found in this project.");
        }
    }

    @GetMapping("/projects/_detail/{projectId}/queue/{assignmentId}/transcript")
    @ResponseBody
    public String transcript(@PathVariable String projectId, @PathVariable String assignmentId,
                             HttpServletResponse response) {
        try {
            AssignmentListEntry entry = resolveAnyProjectEntry(projectId, assignmentId);
            AssignmentTranscript transcript = assignmentService.exactLinkTranscript(entry.agentId(), entry.id());
            if (transcript.conversationIds().isEmpty()) {
                return transcriptState("No persisted conversation link is available for this assignment.");
            }
            return transcript(entry, transcript).render();
        } catch (ProjectAccessDeniedException e) {
            response.setStatus(projectAccessStatus(projectId));
            return transcriptState("Transcript is unavailable for this project state.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return transcriptState("Transcript is unavailable for this assignment.");
        }
    }

    @GetMapping("/projects/_detail/{projectId}/queue/{assignmentId}/diagnostics")
    @ResponseBody
    public String diagnostics(@PathVariable String projectId, @PathVariable String assignmentId,
                              HttpServletResponse response) {
        try {
            AssignmentListEntry entry = resolveAnyProjectEntry(projectId, assignmentId);
            AssignmentDiagnostics diagnostics = assignmentService.diagnostics(entry.id());
            return diagnostics(entry, diagnostics).render();
        } catch (ProjectAccessDeniedException e) {
            response.setStatus(projectAccessStatus(projectId));
            return inspectorState("Diagnostics are unavailable for this project state.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return inspectorState("Diagnostics are unavailable for this assignment.");
        }
    }

    @PostMapping("/projects/_detail/{projectId}/queue/{assignmentId}/set-priority")
    @ResponseBody
    public String setPriority(@PathVariable String projectId, @PathVariable String assignmentId,
                              @RequestParam String priority, @RequestParam String rowVersion,
                              @RequestParam(value = "operationKey", required = false) String operationKey,
                              HttpServletResponse response) {
        try {
            ProjectScope scope = readScope(projectId, false);
            assignmentQueryService.resolveEntry(scope, assignmentId);
            assignmentControlService.setPriority(assignmentId, nonNegativeInt(priority, "priority"),
                nonNegativeInt(rowVersion, "row version"), projectId, operationKey, scope.label());
            return controlSuccess(projectId, "Priority updated.");
        } catch (ProjectAccessDeniedException e) {
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            return queueError("Priority controls are disabled.").render();
        } catch (StaleRevisionException | OperationConflictException e) {
            response.setStatus(HttpStatus.CONFLICT.value());
            return queueError("The assignment changed. Refresh the queue before retrying.").render();
        } catch (IllegalArgumentException e) {
            boolean missing = "Assignment not found".equals(e.getMessage());
            response.setStatus(missing ? HttpStatus.NOT_FOUND.value() : HttpStatus.BAD_REQUEST.value());
            return queueError(missing ? "Assignment not found in this project."
                : "Priority and row version must be valid non-negative integers.").render();
        } catch (RuntimeException e) {
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            return queueError("Priority controls are temporarily unavailable.").render();
        }
    }

    @PostMapping("/projects/_detail/{projectId}/queue/{assignmentId}/cancel")
    @ResponseBody
    public String cancel(@PathVariable String projectId, @PathVariable String assignmentId,
                         @RequestParam(value = "operationKey", required = false) String operationKey,
                         HttpServletResponse response) {
        try {
            ProjectScope scope = readScope(projectId, false);
            AssignmentListEntry entry = assignmentQueryService.resolveEntry(scope, assignmentId);
            assignmentControlService.requestCancel(entry.agentId(), entry.id(), operationKey,
                "agent:" + entry.agentId());
            return controlSuccess(projectId, "Cancellation request submitted.");
        } catch (ProjectAccessDeniedException e) {
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            return queueError("Cancellation controls are disabled.").render();
        } catch (OperationConflictException e) {
            response.setStatus(HttpStatus.CONFLICT.value());
            return queueError("The assignment cannot be cancelled in its current state.").render();
        } catch (IllegalArgumentException | IllegalStateException e) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return queueError("Assignment not found in this project.").render();
        } catch (RuntimeException e) {
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            return queueError("Cancellation controls are temporarily unavailable.").render();
        }
    }

    private Component queuePanel(String projectId, String filter, List<AssignmentListEntry> entries,
                                 CursorSnapshot next) {
        boolean history = "history".equals(filter);
        Div panel = new Div().withId("project-queue-panel").withClass("project-queue-panel");
        panel.withChild(Header.H2(history ? "Queue History" : "Active Queue"));
        Div nav = new Div().withClass("project-queue-subnav");
        nav.withChild(queueNavButton(projectId, "Active", "queue", !history));
        nav.withChild(queueNavButton(projectId, "History", "queue/history", history));
        panel.withChild(nav);
        panel.withChild(queueStatus(""));
        panel.withChild(queueList(projectId, filter, entries, next));
        panel.withChild(inspectorHost("Select an assignment to inspect."));
        panel.withChild(transcriptHost("Select an assignment to view its transcript."));
        return panel;
    }

    private Component queueNavButton(String projectId, String label, String route, boolean active) {
        Button button = Button.create(label).withClass(active ? "active" : "");
        return button.withAttribute("hx-get", "/projects/_detail/" + e(projectId) + "/" + route)
            .withAttribute("hx-target", "#project-queue-panel")
            .withAttribute("hx-swap", "outerHTML")
            .withAttribute("data-htmx-error-swap", "true");
    }

    private Div cursorError(String projectId, String filter, String message) {
        Div host = new Div().withId("project-queue-list");
        host.withChild(new Div().withClass("orch-error").withInnerText(message));
        String route = "history".equals(filter) ? "queue/history" : "queue";
        host.withChild(Button.create("Refresh Queue").withClass("button-secondary small")
            .withAttribute("hx-get", "/projects/_detail/" + e(projectId) + "/" + route)
            .withAttribute("hx-target", "#project-queue-panel").withAttribute("hx-swap", "outerHTML")
            .withAttribute("data-htmx-error-swap", "true"));
        return host;
    }

    private Div queueList(String projectId, String filter, List<AssignmentListEntry> entries,
                          CursorSnapshot next) {
        Div host = new Div().withId("project-queue-list");
        if (entries.isEmpty()) {
            host.withChild(new Div().withClass("dashboard-empty").withInnerText(
                "history".equals(filter) ? "No completed assignments." : "No queued or active assignments."));
            return host;
        }
        Table table = Table.create().withClass("dashboard-table project-queue-table")
            .withHeaders("Assignment", "Agent", "Type", "Status", "Priority", "Run Name", "Created", "Actions");
        for (AssignmentListEntry entry : entries) {
            table.addRow(new HtmlTag("code").withInnerText(entry.id()),
                new HtmlTag("span").withInnerText(ProjectConsoleFragments.displayValue(entry.agentId())),
                new HtmlTag("span").withInnerText(entry.assignmentType() == null ? "—" : entry.assignmentType().name()),
                status(entry.status()), new HtmlTag("span").withInnerText(String.valueOf(entry.priority())),
                new HtmlTag("span").withInnerText(ProjectConsoleFragments.displayValue(entry.runDisplayName())),
                new HtmlTag("span").withInnerText(entry.createdAt() == null ? "—" : since(entry.createdAt())),
                actions(projectId, entry));
        }
        host.withChild(table);
        if (next != null) {
            String route = "history".equals(filter) ? "queue/history" : "queue";
            host.withChild(Button.create("Load more").withClass("button-secondary small")
                .withAttribute("hx-get", "/projects/_detail/" + e(projectId) + "/" + route
                    + "?cursor=" + e(next.encode()))
                .withAttribute("hx-target", "#project-queue-list")
                .withAttribute("hx-swap", "outerHTML")
                .withAttribute("data-htmx-error-swap", "true"));
        }
        return host;
    }

    private Component actions(String projectId, AssignmentListEntry entry) {
        Div actions = new Div().withClass("orch-actions");
        actions.withChild(Button.create("Inspect").withClass("button-secondary small")
            .withAttribute("hx-get", assignmentRoute(projectId, entry.id()))
            .withAttribute("hx-target", "#project-queue-inspector").withAttribute("hx-swap", "outerHTML")
            .withAttribute("data-htmx-error-swap", "true"));
        if (entry.allowedActions().contains("SET_PRIORITY")) {
            Form form = Form.create();
            form.withClass("orch-form-inline-priority")
                .withAttribute("hx-post", assignmentRoute(projectId, entry.id()) + "/set-priority")
                .withAttribute("hx-target", "#project-queue-status").withAttribute("hx-swap", "outerHTML")
                .withAttribute("data-htmx-error-swap", "true");
            form.withChild(TextInput.create("priority").withValue(String.valueOf(entry.priority())).withAttribute("size", "3"));
            form.withChild(hidden("rowVersion", String.valueOf(entry.rowVersion())));
            form.withChild(Button.create("Set").withClass("orch-primary small").withAttribute("type", "submit"));
            actions.withChild(form);
        }
        if (entry.allowedActions().contains("CANCEL")) {
            actions.withChild(Button.create("Cancel").withClass("orch-danger small")
                .withAttribute("hx-post", assignmentRoute(projectId, entry.id()) + "/cancel")
                .withAttribute("hx-confirm", "Cancel this assignment?")
                .withAttribute("hx-target", "#project-queue-status").withAttribute("hx-swap", "outerHTML")
                .withAttribute("data-htmx-error-swap", "true"));
        }
        actions.withChild(Button.create("Transcript").withClass("button-secondary small")
            .withAttribute("hx-get", assignmentRoute(projectId, entry.id()) + "/transcript")
            .withAttribute("hx-target", "#project-queue-transcript").withAttribute("hx-swap", "outerHTML")
            .withAttribute("data-htmx-error-swap", "true"));
        actions.withChild(Button.create("Diagnostics").withClass("button-secondary small")
            .withAttribute("hx-get", assignmentRoute(projectId, entry.id()) + "/diagnostics")
            .withAttribute("hx-target", "#project-queue-inspector").withAttribute("hx-swap", "outerHTML")
            .withAttribute("data-htmx-error-swap", "true"));
        return actions;
    }

    private String controlSuccess(String projectId, String message) {
        try {
            ProjectScope scope = readScope(projectId, false);
            List<WorkAssignment> rows = assignmentQueryService.listActive(scope, SNAPSHOT_SIZE);
            CursorSnapshot cursor = rows.isEmpty() ? null : assignmentQueryService.snapshotCursor(scope, "active", rows);
            List<AssignmentListEntry> entries = rows.stream().limit(PAGE_SIZE)
                .map(row -> AssignmentListEntry.from(row, assignmentQueryService.computeAllowedActions(row, scope))).toList();
            CursorSnapshot next = rows.size() > PAGE_SIZE
                ? assignmentQueryService.resolveCursor(scope, "active", cursor).nextCursor() : null;
            Div refreshedList = queueList(projectId, "active", entries, next);
            refreshedList.withAttribute("hx-swap-oob", "outerHTML");
            return queueStatus(message).render() + refreshedList.render();
        } catch (RuntimeException e) {
            return queueStatus(message).render();
        }
    }

    private Component inspector(AssignmentListEntry entry) {
        Div panel = baseInspector("Inspector: " + entry.id());
        Div facts = new Div().withClass("orch-form-grid");
        facts.withChild(fact("Agent", entry.agentId()));
        facts.withChild(fact("Type", entry.assignmentType() == null ? "—" : entry.assignmentType().name()));
        facts.withChild(fact("Status", entry.status() == null ? "Unknown" : entry.status().name()));
        facts.withChild(fact("Priority", String.valueOf(entry.priority())));
        facts.withChild(fact("Run Name", ProjectConsoleFragments.displayValue(entry.runDisplayName())));
        facts.withChild(fact("Created", entry.createdAt() == null ? "—" : since(entry.createdAt())));
        facts.withChild(fact("Updated", entry.updatedAt() == null ? "—" : since(entry.updatedAt())));
        panel.withChild(facts);
        return panel;
    }

    private Component diagnostics(AssignmentListEntry entry, AssignmentDiagnostics details) {
        Div panel = baseInspector("Diagnostics: " + entry.id());
        Div facts = new Div().withClass("orch-form-grid");
        facts.withChild(fact("Status", entry.status() == null ? "Unknown" : entry.status().name()));
        facts.withChild(fact("Last Progress", details.lastProgressAt() == null ? "—" : since(details.lastProgressAt())));
        facts.withChild(fact("Last Heartbeat", details.lastHeartbeatAt() == null ? "—" : since(details.lastHeartbeatAt())));
        facts.withChild(fact("Progress Age", duration(details.progressAge())));
        facts.withChild(fact("Heartbeat Age", duration(details.heartbeatAge())));
        panel.withChild(facts);
        if (details.suspectedStuck()) {
            panel.withChild(new Div().withClass("orch-error").withInnerText("Progress appears stalled."));
        }
        return panel;
    }

    private Component transcript(AssignmentListEntry entry, AssignmentTranscript transcript) {
        Div panel = new Div().withId("project-queue-transcript").withClass("orch-panel agent-live-transcript");
        if (entry.status() != null && !entry.status().isTerminal()) {
            panel.withAttribute("hx-get", assignmentRoute(entry.projectId(), entry.id()) + "/transcript")
                .withAttribute("hx-trigger", "every 3s").withAttribute("hx-swap", "outerHTML");
        }
        panel.withChild(Header.H3("Transcript"));
        panel.withChild(new Div().withClass("orch-meta").withChild(new HtmlTag("span")
            .withInnerText("Persisted conversation links: " + String.join(", ", transcript.conversationIds()))));
        if (transcript.auditEvents().isEmpty()) {
            panel.withChild(new Div().withClass("dashboard-empty").withInnerText("No persisted transcript events yet."));
            return panel;
        }
        Div events = new Div().withClass("agent-live-transcript-messages");
        transcript.auditEvents().stream().limit(12).forEach(event -> events.withChild(new Div()
            .withClass("chat-message chat-message-assistant")
            .withInnerText("Recorded " + safeEventType(event.eventType()) + " event #" + event.sequence())));
        return panel.withChild(events);
    }

    private Div queueStatus(String message) {
        return queueStatus(message, "orch-status");
    }

    private Div queueError(String message) {
        return queueStatus(message, "orch-error");
    }

    private Div queueStatus(String message, String messageClass) {
        Div host = new Div().withId("project-queue-status").withClass("project-queue-status");
        if (StringUtils.hasText(message)) host.withChild(new Div().withClass(messageClass).withInnerText(message));
        return host;
    }

    private String inspectorState(String message) {
        return inspectorHost(message).render();
    }

    private Div inspectorHost(String message) {
        return baseInspector("Inspector").withClass("orch-error")
            .withChild(new Div().withClass("dashboard-empty").withInnerText(message));
    }

    private String transcriptState(String message) {
        return transcriptHost(message).render();
    }

    private Div transcriptHost(String message) {
        return new Div().withId("project-queue-transcript").withClass("orch-panel orch-error")
            .withChild(Header.H3("Transcript"))
            .withChild(new Div().withClass("dashboard-empty").withInnerText(message));
    }

    private Div baseInspector(String title) {
        return new Div().withId("project-queue-inspector").withClass("orch-panel assignment-diagnostics")
            .withChild(Header.H3(title));
    }

    private AssignmentListEntry resolveAnyProjectEntry(String projectId, String assignmentId) {
        try {
            return assignmentQueryService.resolveEntry(readScope(projectId, false), assignmentId);
        } catch (ProjectAccessDeniedException archived) {
            return assignmentQueryService.resolveEntry(readScope(projectId, true), assignmentId);
        }
    }

    private ProjectScope readScope(String projectId, boolean history) {
        Project project = projectService.getProject(projectId);
        if (project.archived()) {
            if (!history) throw new ProjectAccessDeniedException("Archived project");
            if (!projectAccessService.isTrustedDeployment()) {
                throw new ProjectAccessDeniedException("Historical project reads require capability");
            }
        }
        return new ProjectScope(project.id());
    }

    private int projectAccessStatus(String projectId) {
        try {
            return projectService.getProject(projectId).archived() && !projectAccessService.isTrustedDeployment()
                ? HttpStatus.SERVICE_UNAVAILABLE.value() : HttpStatus.CONFLICT.value();
        } catch (RuntimeException ignored) {
            return HttpStatus.NOT_FOUND.value();
        }
    }

    private boolean isStaleCursor(InvalidCursorException error) {
        String message = error.getMessage();
        return message != null && (message.contains("expired") || message.contains("unavailable")
            || message.contains("binding") || message.contains("scope") || message.contains("membership"));
    }

    private int nonNegativeInt(String value, String label) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) throw new NumberFormatException();
            return parsed;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(label + " is invalid");
        }
    }

    private Component fact(String label, String value) {
        return new Div().withClass("agent-meta-item")
            .withChild(new HtmlTag("span").withClass("agent-meta-label").withInnerText(label))
            .withChild(new HtmlTag("span").withClass("agent-meta-value").withInnerText(value));
    }

    private Component status(OrchestrationStatus status) {
        String name = status == null ? "Unknown" : status.name();
        String css = status == null || status.isTerminal() ? "orch-status-chip disabled" : "orch-status-chip active";
        return new HtmlTag("span").withClass(css).withInnerText(name);
    }

    private HtmlTag hidden(String name, String value) {
        return new HtmlTag("input").withAttribute("type", "hidden").withAttribute("name", name)
            .withAttribute("value", value);
    }

    private String assignmentRoute(String projectId, String assignmentId) {
        return "/projects/_detail/" + e(projectId) + "/queue/" + e(assignmentId);
    }

    private String e(String value) {
        return ProjectConsoleFragments.e(value);
    }

    private String safeEventType(String eventType) {
        return StringUtils.hasText(eventType) && eventType.matches("[a-z_]{1,40}") ? eventType : "audit";
    }

    private String since(Instant value) {
        long seconds = Math.max(0, Duration.between(value, Instant.now()).toSeconds());
        if (seconds < 60) return seconds + "s ago";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m ago";
        long hours = minutes / 60;
        return hours < 24 ? hours + "h ago" : (hours / 24) + "d ago";
    }

    private String duration(Duration value) {
        if (value == null) return "—";
        long seconds = Math.max(0, value.toSeconds());
        return seconds < 60 ? seconds + "s" : (seconds / 60) + "m";
    }
}
