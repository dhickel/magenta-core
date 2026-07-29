package io.mindspice.magenta2.api.web;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.mindspice.magenta2.ai.orchestration.runtime.EventReactionService.ReactionDefinition;
import io.mindspice.magenta2.ai.orchestration.runtime.EventReactionService.ReactionDispatch;
import io.mindspice.magenta2.ai.orchestration.runtime.EventType;
import io.mindspice.magenta2.ai.orchestration.runtime.ReactionActionType;
import io.mindspice.magenta2.ai.orchestration.runtime.ScheduleMode;
import io.mindspice.magenta2.ai.orchestration.runtime.ScheduleService.ScheduleDefinition;
import io.mindspice.magenta2.ai.orchestration.runtime.ScheduleService.ScheduleFiring;
import io.mindspice.magenta2.ai.orchestration.runtime.ScheduleService.ScheduleFiringAttempt;
import io.mindspice.magenta2.ai.orchestration.runtime.ScheduleService.ScheduleSimulation;
import io.mindspice.magenta2.ai.orchestration.runtime.ScheduleService.ScheduleSimRequest;
import io.mindspice.magenta2.ai.orchestration.runtime.ScheduleService;
import io.mindspice.magenta2.ai.orchestration.runtime.EventReactionService;
import io.mindspice.magenta2.ai.orchestration.runtime.EventReactionService.ReactionSimRequest;
import io.mindspice.magenta2.ai.orchestration.runtime.EventReactionService.ReactionSimulation;
import io.mindspice.simplypages.components.Div;
import io.mindspice.simplypages.components.Header;
import io.mindspice.simplypages.components.Paragraph;
import io.mindspice.simplypages.components.TextNode;
import io.mindspice.simplypages.components.display.Table;
import io.mindspice.simplypages.components.forms.Button;
import io.mindspice.simplypages.core.Component;
import io.mindspice.simplypages.core.HtmlTag;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Reusable, slot-safe SimplyPages HTMX fragments for schedule and reaction
 * management—compact lists, editors, simulation previews, bounded firing/dispatch
 * audit, linked runtime outcomes, and state clarity.
 *
 * <p>All interactions are HTMX-first. Simulation is visibly labelled
 * non-executing. Enqueued work is distinct from completed work.
 */
@Controller
public class ScheduleReactionFragments {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final ScheduleService scheduleService;
    private final EventReactionService reactionService;
    private final ObjectMapper objectMapper;

    public ScheduleReactionFragments(ScheduleService scheduleService,
                                      EventReactionService reactionService,
                                      ObjectMapper objectMapper) {
        this.scheduleService = scheduleService;
        this.reactionService = reactionService;
        this.objectMapper = objectMapper;
    }

    // ── Schedule composition and mutations ──

    @GetMapping("/fragments/agents/{agentId}/schedules")
    @ResponseBody
    public String schedules(@PathVariable String agentId) {
        return """
                <section class="automation-management" data-schedule-management="true">
                  <div id="schedule-editor">%s</div>
                  %s
                  <div id="schedule-firings-panel" class="automation-audit-panel">
                    <p>Select a schedule to inspect firing history.</p>
                  </div>
                </section>
                """.formatted(scheduleForm(agentId, null, Map.of(), null), scheduleList(agentId));
    }

    @GetMapping("/fragments/agents/{agentId}/schedules/new")
    @ResponseBody
    public String newSchedule(@PathVariable String agentId) {
        return scheduleForm(agentId, null, Map.of(), null);
    }

    @GetMapping("/fragments/agents/{agentId}/schedules/{scheduleId}/edit")
    @ResponseBody
    public String editSchedule(@PathVariable String agentId, @PathVariable String scheduleId) {
        return scheduleForm(agentId, scheduleService.definition(agentId, scheduleId), Map.of(), null);
    }

    @PostMapping("/fragments/agents/{agentId}/schedules")
    @ResponseBody
    public String createSchedule(@PathVariable String agentId, @RequestParam Map<String, String> params) {
        try {
            ScheduleDefinition saved = scheduleService.save(agentId, scheduleInput(agentId, null, params));
            return scheduleForm(agentId, saved, Map.of(), null) + scheduleListOob(agentId);
        } catch (RuntimeException exception) {
            return scheduleForm(agentId, null, params, message(exception));
        }
    }

    @PutMapping("/fragments/agents/{agentId}/schedules/{scheduleId}")
    @ResponseBody
    public String updateSchedule(@PathVariable String agentId, @PathVariable String scheduleId,
                                 @RequestParam Map<String, String> params) {
        try {
            ScheduleDefinition saved = scheduleService.update(
                    agentId, scheduleId, scheduleInput(agentId, scheduleId, params));
            return scheduleForm(agentId, saved, Map.of(), null) + scheduleListOob(agentId);
        } catch (RuntimeException exception) {
            ScheduleDefinition existing = null;
            try { existing = scheduleService.definition(agentId, scheduleId); } catch (RuntimeException ignored) { }
            return scheduleForm(agentId, existing, params, message(exception));
        }
    }

    @PostMapping("/fragments/agents/{agentId}/schedules/{scheduleId}/toggle")
    @ResponseBody
    public String toggleSchedule(@PathVariable String agentId, @PathVariable String scheduleId) {
        ScheduleDefinition current = scheduleService.definition(agentId, scheduleId);
        ScheduleDefinition saved = scheduleService.enable(agentId, scheduleId, !current.enabled());
        return scheduleForm(agentId, saved, Map.of(), null) + scheduleListOob(agentId);
    }

    @DeleteMapping("/fragments/agents/{agentId}/schedules/{scheduleId}")
    @ResponseBody
    public String removeSchedule(@PathVariable String agentId, @PathVariable String scheduleId) {
        scheduleService.delete(agentId, scheduleId);
        return scheduleForm(agentId, null, Map.of(), null) + scheduleListOob(agentId);
    }

    // ── Schedule list ──

    @GetMapping("/fragments/agents/{agentId}/schedules/list")
    @ResponseBody
    public String scheduleList(@PathVariable String agentId) {
        List<ScheduleDefinition> defs = scheduleService.definitions(agentId);
        StringBuilder html = new StringBuilder();
        html.append("<div id=\"schedule-list\" class=\"schedule-list-container automation-list-panel\">");
        html.append("<div class=\"automation-panel-heading\"><h3>Schedules</h3>")
                .append("<button type=\"button\" class=\"btn-sm\" hx-get=\"/fragments/agents/")
                .append(esc(agentId)).append("/schedules/new\" hx-target=\"#schedule-editor\" ")
                .append("hx-swap=\"innerHTML\">New Schedule</button></div>");

        if (defs.isEmpty()) {
            html.append("<p>No schedules configured.</p>");
        } else {
            html.append("<table class=\"schedule-list-table data-table compact\">");
            html.append("<thead><tr><th>Cron</th><th>Timezone</th><th>State</th><th>Rev</th><th>Actions</th></tr></thead>");
            html.append("<tbody>");
            for (ScheduleDefinition def : defs) {
                html.append("<tr>");
                html.append("<td>").append(esc(def.cronExpression())).append("</td>");
                html.append("<td>").append(esc(def.timezone())).append("</td>");
                html.append("<td>").append(stateBadge(def)).append("</td>");
                html.append("<td>").append(def.revision()).append("</td>");
                html.append("<td>").append(scheduleActions(def)).append("</td>");
                html.append("</tr>");
            }
            html.append("</tbody></table>");
        }
        html.append("</div>");
        return html.toString();
    }

    private String stateBadge(ScheduleDefinition def) {
        if (def.deleted()) return "<span class=\"status-chip status-chip-deleted\">Deleted</span>";
        if (!def.enabled()) return "<span class=\"status-chip status-chip-disabled\">Disabled</span>";
        return "<span class=\"status-chip status-chip-enabled\">Enabled</span>";
    }

    private String scheduleActions(ScheduleDefinition def) {
        String agentId = esc(def.agentId());
        String id = esc(def.id());
        StringBuilder sb = new StringBuilder();
        sb.append("<button class=\"btn-sm\" hx-get=\"/fragments/agents/").append(agentId)
                .append("/schedules/").append(id).append("/edit\" ")
                .append("hx-target=\"#schedule-editor\" hx-swap=\"innerHTML\">Edit</button> ");
        sb.append("<button class=\"btn-sm\" hx-post=\"/fragments/agents/").append(agentId)
                .append("/schedules/").append(id).append("/toggle\" ")
                .append("hx-target=\"#schedule-editor\" hx-swap=\"innerHTML\">");
        sb.append(def.enabled() ? "Disable" : "Enable").append("</button> ");

        sb.append("<button class=\"btn-sm btn-danger\" hx-delete=\"/fragments/agents/").append(agentId)
                .append("/schedules/").append(id).append("\" ")
                .append("hx-target=\"#schedule-editor\" hx-swap=\"innerHTML\" ")
                .append("hx-confirm=\"Delete this schedule?\">Delete</button> ");

        sb.append("<button class=\"btn-sm\" hx-get=\"/fragments/agents/").append(agentId)
                .append("/schedules/").append(id).append("/firings\" ")
                .append("hx-target=\"#schedule-firings-panel\" hx-swap=\"innerHTML\">Firings</button>");
        return sb.toString();
    }

    private String scheduleListOob(String agentId) {
        return scheduleList(agentId).replaceFirst("<div id=\"schedule-list\"",
                "<div id=\"schedule-list\" hx-swap-oob=\"outerHTML\"");
    }

    private String scheduleForm(String agentId, ScheduleDefinition definition,
                                Map<String, String> entered, String error) {
        boolean edit = definition != null;
        String action = "/fragments/agents/" + esc(agentId) + "/schedules"
                + (edit ? "/" + esc(definition.id()) : "");
        String method = edit ? "hx-put" : "hx-post";
        String cron = field(entered, "cronExpression", edit ? definition.cronExpression() : "0 0 * * * *");
        String timezone = field(entered, "timezone", edit ? definition.timezone() : "UTC");
        String jobId = field(entered, "jobId", edit ? definition.jobId() : "");
        String enabled = field(entered, "enabled", edit ? String.valueOf(definition.enabled()) : "true");
        String template = field(entered, "assignmentTemplate",
                edit ? json(definition.assignmentTemplate()) : "{}");
        return """
                <form class="automation-editor" %s="%s" hx-target="#schedule-editor" hx-swap="innerHTML">
                  <div class="automation-panel-heading"><h3>%s Schedule</h3>%s</div>
                  <div class="automation-form-grid">
                    <label>Cron (six fields)<input name="cronExpression" value="%s" required aria-required="true"></label>
                    <label>Timezone<input name="timezone" value="%s" required aria-required="true"></label>
                    <label>Job ID<input name="jobId" value="%s"></label>
                    <label>Status<select name="enabled"><option value="true"%s>Enabled</option><option value="false"%s>Disabled</option></select></label>
                  </div>
                  <label>Assignment template JSON<textarea name="assignmentTemplate" rows="4" required>%s</textarea></label>
                  %s
                  <div class="automation-form-actions">
                    <button type="submit" class="btn-sm automation-primary">%s Schedule</button>
                    %s
                  </div>
                </form>
                """.formatted(method, action, edit ? "Edit" : "Create",
                edit ? "<span class=\"status-chip status-chip-neutral\">Revision " + definition.revision() + "</span>" : "",
                esc(cron), esc(timezone), esc(jobId), selected(enabled, "true"), selected(enabled, "false"),
                esc(template), validation(error), edit ? "Save" : "Create",
                edit ? "<button type=\"button\" class=\"btn-sm\" hx-get=\"/fragments/agents/" + esc(agentId)
                        + "/schedules/new\" hx-target=\"#schedule-editor\" hx-swap=\"innerHTML\">Cancel</button>" : "");
    }

    private ScheduleDefinition scheduleInput(String agentId, String scheduleId, Map<String, String> params) {
        return new ScheduleDefinition(scheduleId, agentId, 0,
                ScheduleMode.CRON,
                required(params, "cronExpression"), required(params, "timezone"),
                null,
                Boolean.parseBoolean(params.getOrDefault("enabled", "true")), false,
                null,
                blankToNull(params.get("jobId")), parseMap(params.get("assignmentTemplate"), "assignmentTemplate"),
                null, null, null, null);
    }

    // ── Schedule simulation preview ──

    @PostMapping("/fragments/agents/{agentId}/schedules/simulate-preview")
    @ResponseBody
    public String simulateSchedulePreview(@PathVariable String agentId,
                                           @RequestParam String cronExpression,
                                           @RequestParam String timezone,
                                           @RequestParam(defaultValue = "true") boolean enabled) {
        ScheduleSimulation result = scheduleService.simulate(
                new ScheduleSimRequest(cronExpression, timezone, ScheduleMode.CRON, null, enabled, 5, null));

        StringBuilder html = new StringBuilder();
        html.append("<div class=\"simulation-preview schedule-sim\">");
        html.append("<h4>Schedule Simulation — <span class=\"badge badge-sim\">Non-Executing</span></h4>");

        if (result.error() != null) {
            html.append("<p class=\"text-error\">Error: ").append(esc(result.error())).append("</p>");
            html.append("</div>");
            return html.toString();
        }

        html.append("<table class=\"data-table compact sim-table\">");
        html.append("<thead><tr><th>Property</th><th>Value</th></tr></thead><tbody>");
        propRow(html, "Dialect", result.dialectVersion());
        propRow(html, "Timezone", result.timezone());
        propRow(html, "DST Gap", result.dstGap() ? "YES" : "No");
        propRow(html, "DST Overlap", result.dstOverlap() ? "YES" : "No");
        propRow(html, "Misfire", result.misfireDetected()
                ? "YES (" + result.missedSlots() + " missed)" : "No");
        html.append("</tbody></table>");

        if (!result.nextSlots().isEmpty()) {
            html.append("<h5>Next ").append(result.nextSlots().size()).append(" slots:</h5>");
            html.append("<ul class=\"next-slots\">");
            for (String slot : result.nextSlots()) {
                html.append("<li>").append(esc(slot)).append("</li>");
            }
            html.append("</ul>");
        }
        html.append("</div>");
        return html.toString();
    }

    private void propRow(StringBuilder sb, String prop, String value) {
        sb.append("<tr><td>").append(esc(prop)).append("</td><td>").append(esc(value)).append("</td></tr>");
    }

    // ── Schedule firings audit ──

    @GetMapping("/fragments/agents/{agentId}/schedules/{scheduleId}/firings")
    @ResponseBody
    public String scheduleFiringsPanel(@PathVariable String agentId,
                                        @PathVariable String scheduleId,
                                        @RequestParam(defaultValue = "1") int revision,
                                        @RequestParam(defaultValue = "0") int offset,
                                        @RequestParam(defaultValue = "25") int limit) {
        List<ScheduleFiring> firings = scheduleService.firings(scheduleId, revision, offset, limit);

        StringBuilder html = new StringBuilder();
        html.append("<div id=\"schedule-firings-panel\" class=\"firings-panel\">");
        html.append("<h4>Schedule Firings (rev ").append(revision).append(")</h4>");

        if (firings.isEmpty()) {
            html.append("<p>No firings recorded.</p>");
            html.append("</div>");
            return html.toString();
        }

        html.append("<table class=\"data-table compact firings-table\">");
        html.append("<thead><tr><th>Slot</th><th>State</th><th>Assignment</th><th>Task Run</th><th>Created</th></tr></thead><tbody>");
        for (ScheduleFiring f : firings) {
            String stateClass = switch (f.firingState()) {
                case "ENQUEUED" -> "badge-success";
                case "EXHAUSTED" -> "badge-error";
                case "RECOVERED" -> "badge-warning";
                case "CONFLICT" -> "badge-error";
                default -> "badge-info";
            };
            html.append("<tr>");
            html.append("<td>").append(esc(TIME_FORMAT.format(f.slotInstant()))).append("</td>");
            html.append("<td><span class=\"badge ").append(stateClass).append("\">")
                    .append(esc(f.firingState())).append("</span></td>");
            html.append("<td>").append(f.assignmentId() != null ? esc(f.assignmentId()) : "—").append("</td>");
            html.append("<td>").append(f.taskRunId() != null ? esc(f.taskRunId()) : "—").append("</td>");
            html.append("<td>").append(esc(TIME_FORMAT.format(f.createdAt()))).append("</td>");
            html.append("</tr>");
        }
        html.append("</tbody></table>");

        if (firings.size() == limit) {
            html.append("<button class=\"btn-sm\" hx-get=\"/fragments/agents/").append(esc(agentId))
                    .append("/schedules/").append(esc(scheduleId))
                    .append("/firings?revision=").append(revision)
                    .append("&offset=").append(offset + limit)
                    .append("&limit=").append(limit)
                    .append("\" hx-target=\"#schedule-firings-panel\" hx-swap=\"outerHTML\">Next →</button>");
        }
        html.append("</div>");
        return html.toString();
    }

    // ── Firing attempt details ──

    @GetMapping("/fragments/agents/{agentId}/schedules/{scheduleId}/firings/{firingId}/attempts")
    @ResponseBody
    public String firingAttemptsDetail(@PathVariable String agentId,
                                        @PathVariable String scheduleId,
                                        @PathVariable String firingId) {
        List<ScheduleFiringAttempt> attempts = scheduleService.firingAttempts(firingId);

        StringBuilder html = new StringBuilder();
        html.append("<div class=\"firing-attempts-panel\">");
        html.append("<h4>Firing Attempts</h4>");

        if (attempts.isEmpty()) {
            html.append("<p>No attempts recorded.</p>");
            html.append("</div>");
            return html.toString();
        }

        html.append("<table class=\"data-table compact attempts-table\">");
        html.append("<thead><tr><th>#</th><th>Outcome</th><th>Eligible At</th><th>Attempted At</th><th>Assignment</th><th>Error</th></tr></thead><tbody>");
        for (ScheduleFiringAttempt a : attempts) {
            String outcomeClass = "ENQUEUED".equals(a.outcome()) ? "badge-success" : "badge-error";
            html.append("<tr>");
            html.append("<td>").append(a.attemptNumber()).append("</td>");
            html.append("<td><span class=\"badge ").append(outcomeClass).append("\">")
                    .append(esc(a.outcome())).append("</span></td>");
            html.append("<td>").append(a.eligibleAt() != null ? esc(TIME_FORMAT.format(a.eligibleAt())) : "—").append("</td>");
            html.append("<td>").append(a.attemptedAt() != null ? esc(TIME_FORMAT.format(a.attemptedAt())) : "—").append("</td>");
            html.append("<td>").append(a.assignmentId() != null ? esc(a.assignmentId()) : "—").append("</td>");
            html.append("<td>").append(a.errorClass() != null ? esc(a.errorClass()) : "—").append("</td>");
            html.append("</tr>");
        }
        html.append("</tbody></table>");
        html.append("</div>");
        return html.toString();
    }

    // ── Reaction composition and mutations ──

    @GetMapping("/fragments/agents/{agentId}/reactions")
    @ResponseBody
    public String reactions(@PathVariable String agentId) {
        return """
                <section class="automation-management" data-reaction-management="true">
                  <div id="reaction-editor">%s</div>
                  %s
                  <div id="reaction-dispatches-panel" class="automation-audit-panel">
                    <p>Select a reaction to inspect dispatch history.</p>
                  </div>
                </section>
                """.formatted(reactionForm(agentId, null, Map.of(), null), reactionList(agentId));
    }

    @GetMapping("/fragments/agents/{agentId}/reactions/new")
    @ResponseBody
    public String newReaction(@PathVariable String agentId) {
        return reactionForm(agentId, null, Map.of(), null);
    }

    @GetMapping("/fragments/agents/{agentId}/reactions/{reactionId}/edit")
    @ResponseBody
    public String editReaction(@PathVariable String agentId, @PathVariable String reactionId) {
        return reactionForm(agentId, reactionService.definition(agentId, reactionId), Map.of(), null);
    }

    @PostMapping("/fragments/agents/{agentId}/reactions")
    @ResponseBody
    public String createReaction(@PathVariable String agentId, @RequestParam Map<String, String> params) {
        try {
            ReactionDefinition saved = reactionService.save(agentId, reactionInput(agentId, null, params));
            return reactionForm(agentId, saved, Map.of(), null) + reactionListOob(agentId);
        } catch (RuntimeException exception) {
            return reactionForm(agentId, null, params, message(exception));
        }
    }

    @PutMapping("/fragments/agents/{agentId}/reactions/{reactionId}")
    @ResponseBody
    public String updateReaction(@PathVariable String agentId, @PathVariable String reactionId,
                                 @RequestParam Map<String, String> params) {
        try {
            ReactionDefinition saved = reactionService.update(
                    agentId, reactionId, reactionInput(agentId, reactionId, params));
            return reactionForm(agentId, saved, Map.of(), null) + reactionListOob(agentId);
        } catch (RuntimeException exception) {
            ReactionDefinition existing = null;
            try { existing = reactionService.definition(agentId, reactionId); } catch (RuntimeException ignored) { }
            return reactionForm(agentId, existing, params, message(exception));
        }
    }

    @PostMapping("/fragments/agents/{agentId}/reactions/{reactionId}/toggle")
    @ResponseBody
    public String toggleReaction(@PathVariable String agentId, @PathVariable String reactionId) {
        ReactionDefinition current = reactionService.definition(agentId, reactionId);
        ReactionDefinition saved = reactionService.enable(agentId, reactionId, !current.enabled());
        return reactionForm(agentId, saved, Map.of(), null) + reactionListOob(agentId);
    }

    @DeleteMapping("/fragments/agents/{agentId}/reactions/{reactionId}")
    @ResponseBody
    public String removeReaction(@PathVariable String agentId, @PathVariable String reactionId) {
        reactionService.delete(agentId, reactionId);
        return reactionForm(agentId, null, Map.of(), null) + reactionListOob(agentId);
    }

    // ── Reaction list ──

    @GetMapping("/fragments/agents/{agentId}/reactions/list")
    @ResponseBody
    public String reactionList(@PathVariable String agentId) {
        List<ReactionDefinition> defs = reactionService.definitions(agentId);
        StringBuilder html = new StringBuilder();
        html.append("<div id=\"reaction-list\" class=\"reaction-list-container automation-list-panel\">");
        html.append("<div class=\"automation-panel-heading\"><h3>Event Reactions</h3>")
                .append("<button type=\"button\" class=\"btn-sm\" hx-get=\"/fragments/agents/")
                .append(esc(agentId)).append("/reactions/new\" hx-target=\"#reaction-editor\" ")
                .append("hx-swap=\"innerHTML\">New Reaction</button></div>");

        if (defs.isEmpty()) {
            html.append("<p>No reactions configured.</p>");
        } else {
            html.append("<table class=\"reaction-list-table data-table compact\">");
            html.append("<thead><tr><th>Event Type</th><th>State</th><th>Rev</th><th>Actions</th></tr></thead><tbody>");
            for (ReactionDefinition def : defs) {
                String stateBadge = def.deleted() ? "<span class=\"status-chip status-chip-deleted\">Deleted</span>"
                        : def.enabled() ? "<span class=\"status-chip status-chip-enabled\">Enabled</span>"
                        : "<span class=\"status-chip status-chip-disabled\">Disabled</span>";
                html.append("<tr>");
                html.append("<td>").append(esc(def.eventType().name())).append("</td>");
                html.append("<td>").append(stateBadge).append("</td>");
                html.append("<td>").append(def.revision()).append("</td>");
                html.append("<td>").append(reactionActions(def)).append("</td>");
                html.append("</tr>");
            }
            html.append("</tbody></table>");
        }
        html.append("</div>");
        return html.toString();
    }

    private String reactionActions(ReactionDefinition def) {
        String agentId = esc(def.agentId());
        String id = esc(def.id());
        StringBuilder sb = new StringBuilder();
        sb.append("<button class=\"btn-sm\" hx-get=\"/fragments/agents/").append(agentId)
                .append("/reactions/").append(id).append("/edit\" ")
                .append("hx-target=\"#reaction-editor\" hx-swap=\"innerHTML\">Edit</button> ");
        sb.append("<button class=\"btn-sm\" hx-post=\"/fragments/agents/").append(agentId)
                .append("/reactions/").append(id).append("/toggle\" ")
                .append("hx-target=\"#reaction-editor\" hx-swap=\"innerHTML\">");
        sb.append(def.enabled() ? "Disable" : "Enable").append("</button> ");

        sb.append("<button class=\"btn-sm btn-danger\" hx-delete=\"/fragments/agents/").append(agentId)
                .append("/reactions/").append(id).append("\" ")
                .append("hx-target=\"#reaction-editor\" hx-swap=\"innerHTML\" ")
                .append("hx-confirm=\"Delete this reaction?\">Delete</button> ");

        sb.append("<button class=\"btn-sm\" hx-get=\"/fragments/agents/").append(agentId)
                .append("/reactions/").append(id).append("/dispatches\" ")
                .append("hx-target=\"#reaction-dispatches-panel\" hx-swap=\"innerHTML\">Dispatches</button>");
        return sb.toString();
    }

    private String reactionListOob(String agentId) {
        return reactionList(agentId).replaceFirst("<div id=\"reaction-list\"",
                "<div id=\"reaction-list\" hx-swap-oob=\"outerHTML\"");
    }

    private String reactionForm(String agentId, ReactionDefinition definition,
                                Map<String, String> entered, String error) {
        boolean edit = definition != null;
        String action = "/fragments/agents/" + esc(agentId) + "/reactions"
                + (edit ? "/" + esc(definition.id()) : "");
        String method = edit ? "hx-put" : "hx-post";
        String eventType = field(entered, "eventType",
                edit ? definition.eventType().name() : EventType.MANUAL_USER_EVENT.name());
        String actionType = field(entered, "actionType",
                edit ? definition.actionType().name() : ReactionActionType.ENQUEUE_ASSIGNMENT.name());
        String enabled = field(entered, "enabled", edit ? String.valueOf(definition.enabled()) : "true");
        String filter = field(entered, "filter", edit ? json(definition.filter()) : "{}");
        String template = field(entered, "assignmentTemplate",
                edit ? json(definition.assignmentTemplate()) : "{}");
        StringBuilder eventOptions = new StringBuilder();
        for (EventType type : EventType.values()) {
            eventOptions.append("<option value=\"").append(type.name()).append("\"")
                    .append(selected(eventType, type.name())).append(">")
                    .append(esc(type.name())).append("</option>");
        }
        StringBuilder actionOptions = new StringBuilder();
        for (ReactionActionType type : ReactionActionType.values()) {
            actionOptions.append("<option value=\"").append(type.name()).append("\"")
                    .append(selected(actionType, type.name())).append(">")
                    .append(esc(type.name())).append("</option>");
        }
        return """
                <form class="automation-editor" %s="%s" hx-target="#reaction-editor" hx-swap="innerHTML">
                  <div class="automation-panel-heading"><h3>%s Reaction</h3>%s</div>
                  <div class="automation-form-grid">
                    <label>Event type<select name="eventType" required>%s</select></label>
                    <label>Action<select name="actionType" required>%s</select></label>
                    <label>Status<select name="enabled"><option value="true"%s>Enabled</option><option value="false"%s>Disabled</option></select></label>
                  </div>
                  <label>Filter JSON<textarea name="filter" rows="3" required>%s</textarea></label>
                  <label>Assignment template JSON<textarea name="assignmentTemplate" rows="4" required>%s</textarea></label>
                  %s
                  <div class="automation-form-actions">
                    <button type="submit" class="btn-sm automation-primary">%s Reaction</button>
                    %s
                  </div>
                </form>
                """.formatted(method, action, edit ? "Edit" : "Create",
                edit ? "<span class=\"status-chip status-chip-neutral\">Revision " + definition.revision() + "</span>" : "",
                eventOptions, actionOptions, selected(enabled, "true"), selected(enabled, "false"),
                esc(filter), esc(template), validation(error), edit ? "Save" : "Create",
                edit ? "<button type=\"button\" class=\"btn-sm\" hx-get=\"/fragments/agents/" + esc(agentId)
                        + "/reactions/new\" hx-target=\"#reaction-editor\" hx-swap=\"innerHTML\">Cancel</button>" : "");
    }

    private ReactionDefinition reactionInput(String agentId, String reactionId, Map<String, String> params) {
        EventType eventType;
        ReactionActionType actionType;
        try {
            eventType = EventType.valueOf(required(params, "eventType"));
            actionType = ReactionActionType.valueOf(required(params, "actionType"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Select a valid event type and action");
        }
        return new ReactionDefinition(reactionId, agentId, 0, eventType,
                parseMap(params.get("filter"), "filter"), actionType,
                parseMap(params.get("assignmentTemplate"), "assignmentTemplate"),
                Boolean.parseBoolean(params.getOrDefault("enabled", "true")), false,
                null, null, null);
    }

    // ── Reaction simulation preview ──

    @PostMapping("/fragments/agents/{agentId}/reactions/simulate-preview")
    @ResponseBody
    public String simulateReactionPreview(@PathVariable String agentId,
                                           @RequestParam String eventType,
                                           @RequestParam(required = false) String filter,
                                           @RequestParam(required = false) String eventPayload) {
        Map<String, Object> filterMap = Map.of();
        Map<String, Object> payloadMap = Map.of();
        try {
            if (StringUtils.hasText(filter)) {
                filterMap = new com.fasterxml.jackson.databind.ObjectMapper().readValue(filter, Map.class);
            }
            if (StringUtils.hasText(eventPayload)) {
                payloadMap = new com.fasterxml.jackson.databind.ObjectMapper().readValue(eventPayload, Map.class);
            }
        } catch (Exception ignored) { /* use empty maps */ }

        ReactionSimulation result = reactionService.simulate(
                new ReactionSimRequest(eventType, filterMap, payloadMap, null, null, null, null, null, null));

        StringBuilder html = new StringBuilder();
        html.append("<div class=\"simulation-preview reaction-sim\">");
        html.append("<h4>Reaction Simulation — <span class=\"badge badge-sim\">Non-Executing</span></h4>");
        html.append("<table class=\"data-table compact sim-table\">");
        html.append("<thead><tr><th>Property</th><th>Value</th></tr></thead><tbody>");
        propRow(html, "Event Type", result.eventType());
        propRow(html, "Filter Matches", result.filterMatches() ? "YES" : "No");
        propRow(html, "Loop Detected", result.loopDetected() ? "YES" : "No");
        propRow(html, "Depth Exceeded", result.depthExceeded() ? "YES" : "No");
        propRow(html, "Rate Exceeded", result.rateExceeded() ? "YES (" + result.recentDispatchCount() + " recent)" : "No");
        propRow(html, "Would Dispatch", result.suppressionReason() == null
                ? "YES — " + (result.actionDescription() != null ? esc(result.actionDescription()) : "would enqueue")
                : "No");
        if (result.suppressionReason() != null) {
            propRow(html, "Reason", result.suppressionReason());
        }
        html.append("</tbody></table></div>");
        return html.toString();
    }

    // ── Reaction dispatches audit ──

    @GetMapping("/fragments/agents/{agentId}/reactions/{reactionId}/dispatches")
    @ResponseBody
    public String reactionDispatchesPanel(@PathVariable String agentId,
                                           @PathVariable String reactionId,
                                           @RequestParam(defaultValue = "1") int revision,
                                           @RequestParam(defaultValue = "0") int offset,
                                           @RequestParam(defaultValue = "25") int limit) {
        List<ReactionDispatch> dispatches = reactionService.dispatches(reactionId, revision, offset, limit);

        StringBuilder html = new StringBuilder();
        html.append("<div id=\"reaction-dispatches-panel\" class=\"dispatches-panel\">");
        html.append("<h4>Reaction Dispatches (rev ").append(revision).append(")</h4>");

        if (dispatches.isEmpty()) {
            html.append("<p>No dispatches recorded.</p>");
            html.append("</div>");
            return html.toString();
        }

        html.append("<table class=\"data-table compact dispatches-table\">");
        html.append("<thead><tr><th>Event</th><th>State</th><th>Depth</th><th>Assignment</th><th>Created</th></tr></thead><tbody>");
        for (ReactionDispatch d : dispatches) {
            String stateClass = switch (d.dispatchState()) {
                case "ENQUEUED" -> "badge-success";
                case "SUPPRESSED_LOOP", "SUPPRESSED_RATE", "SUPPRESSED_DEPTH" -> "badge-warning";
                case "CONFLICT" -> "badge-error";
                default -> "badge-info";
            };
            String stateLabel = d.dispatchState();
            if (d.suppressionReason() != null) {
                stateLabel += " (" + d.suppressionReason() + ")";
            }
            html.append("<tr>");
            html.append("<td>").append(esc(d.sourceEventId())).append("</td>");
            html.append("<td><span class=\"badge ").append(stateClass).append("\">")
                    .append(esc(stateLabel)).append("</span></td>");
            html.append("<td>").append(d.depth()).append("</td>");
            html.append("<td>").append(d.assignmentId() != null ? esc(d.assignmentId()) : "—").append("</td>");
            html.append("<td>").append(esc(TIME_FORMAT.format(d.createdAt()))).append("</td>");
            html.append("</tr>");
        }
        html.append("</tbody></table>");

        if (dispatches.size() == limit) {
            html.append("<button class=\"btn-sm\" hx-get=\"/fragments/agents/").append(esc(agentId))
                    .append("/reactions/").append(esc(reactionId))
                    .append("/dispatches?revision=").append(revision)
                    .append("&offset=").append(offset + limit)
                    .append("&limit=").append(limit)
                    .append("\" hx-target=\"#reaction-dispatches-panel\" hx-swap=\"outerHTML\">Next →</button>");
        }
        html.append("</div>");
        return html.toString();
    }

    private Map<String, Object> parseMap(String value, String fieldName) {
        if (!StringUtils.hasText(value)) return Map.of();
        try {
            return Map.copyOf(objectMapper.readValue(value, MAP_TYPE));
        } catch (Exception exception) {
            throw new IllegalArgumentException(fieldName + " must be a JSON object");
        }
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception exception) {
            return "{}";
        }
    }

    private static String field(Map<String, String> entered, String name, String fallback) {
        return entered.containsKey(name) ? entered.get(name) : fallback == null ? "" : fallback;
    }

    private static String required(Map<String, String> params, String name) {
        String value = params.get(name);
        if (!StringUtils.hasText(value)) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String selected(String actual, String expected) {
        return expected.equals(actual) ? " selected" : "";
    }

    private static String validation(String error) {
        return StringUtils.hasText(error)
                ? "<div class=\"automation-validation\" role=\"alert\">" + esc(error) + "</div>"
                : "";
    }

    private static String message(RuntimeException exception) {
        return StringUtils.hasText(exception.getMessage()) ? exception.getMessage() : "Request failed";
    }

    private static String esc(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
