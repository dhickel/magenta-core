package io.mindspice.magenta2.api.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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

/**
 * HTMX fragment renderer for the agent context panel. Receives only
 * authorized secret-free records; exposes no absolute paths, credentials,
 * or internal claim details.
 */
final class AgentContextFragments {

    static final String CONTEXT_PANEL_ID = "agent-context-panel";
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    private AgentContextFragments() {}

    static String contextSummary(
            String encodedAgentId,
            ContextDefault defaultRecord,
            List<ContextBinding> bindings,
            List<MaterializationRecord> materializations,
            String message) {
        StringBuilder html = new StringBuilder();
        html.append("<div id=\"").append(CONTEXT_PANEL_ID).append("\" class=\"orch-panel agent-context-panel\">");
        html.append("<div class=\"orch-panel-heading\">");
        html.append("<h3>Agent Context</h3>");
        html.append("<button type=\"button\" class=\"button button-secondary small\""
                + " hx-get=\"/api/agents/").append(encodedAgentId).append("/context/panel\""
                + " hx-target=\"#").append(CONTEXT_PANEL_ID).append("\" hx-swap=\"outerHTML\">Refresh</button>");
        html.append("</div>");

        if (message != null && !message.isBlank()) {
            html.append("<div class=\"orch-status\">").append(escape(message)).append("</div>");
        }

        // Default section
        html.append("<section class=\"context-section\"><h4>Default</h4>");
        if (defaultRecord != null) {
            html.append("<dl class=\"context-facts\">");
            html.append("<dt>Kind</dt><dd>").append(escape(defaultRecord.selectionKind().name())).append("</dd>");
            if (defaultRecord.selectionKind() == SelectionKind.MATERIALIZATION && defaultRecord.materializationKey() != null) {
                html.append("<dt>Project</dt><dd><code>").append(escape(defaultRecord.materializationKey().serialized())).append("</code></dd>");
                html.append("<dt>Generation</dt><dd>").append(defaultRecord.materializationGeneration()).append("</dd>");
            } else if (defaultRecord.selectionKind() == SelectionKind.PERSONAL && defaultRecord.personalSelection() != null) {
                html.append("<dt>Area</dt><dd>").append(escape(defaultRecord.personalSelection().name())).append("</dd>");
            }
            html.append("<dt>Version</dt><dd>").append(defaultRecord.version()).append("</dd>");
            html.append("<dt>Reason</dt><dd>").append(escape(defaultRecord.reason())).append("</dd>");
            html.append("</dl>");
        } else {
            html.append("<p class=\"orch-muted\">No context default configured.</p>");
        }
        html.append("</section>");

        // Materializations section
        html.append("<section class=\"context-section\"><h4>Materializations</h4>");
        if (materializations != null && !materializations.isEmpty()) {
            html.append("<table class=\"orch-table compact\"><thead><tr>"
                    + "<th>Key</th><th>State</th><th>Generation</th><th>Updated</th></tr></thead><tbody>");
            for (MaterializationRecord m : materializations) {
                html.append("<tr>")
                        .append("<td><code>").append(escape(m.materializationKey().serialized())).append("</code></td>")
                        .append("<td>").append(stateChip(m.state())).append("</td>")
                        .append("<td>").append(m.generation()).append("</td>")
                        .append("<td>").append(time(m.updatedAt())).append("</td>")
                        .append("</tr>");
            }
            html.append("</tbody></table>");
        } else {
            html.append("<p class=\"orch-muted\">No materializations.</p>");
        }
        html.append("</section>");

        // Bindings section
        html.append("<section class=\"context-section\"><h4>Bindings</h4>");
        if (bindings != null && !bindings.isEmpty()) {
            html.append("<table class=\"orch-table compact\"><thead><tr>"
                    + "<th>Binding Key</th><th>Kind</th><th>Captured</th></tr></thead><tbody>");
            for (ContextBinding b : bindings) {
                html.append("<tr>")
                        .append("<td><code>").append(escape(truncateKey(b.bindingKey()))).append("</code></td>")
                        .append("<td>").append(escape(b.selectionKind() != null ? b.selectionKind().name() : "—")).append("</td>")
                        .append("<td>").append(time(b.capturedAt())).append("</td>")
                        .append("</tr>");
            }
            html.append("</tbody></table>");
        } else {
            html.append("<p class=\"orch-muted\">No bindings captured.</p>");
        }
        html.append("</section>");

        html.append("</div>");
        return html.toString();
    }

    static String defaultEditor(String encodedAgentId, ContextDefault currentDefault,
                                 List<MaterializationRecord> readyMaterializations,
                                 String message, boolean error) {
        StringBuilder html = new StringBuilder();
        html.append("<div id=\"").append(CONTEXT_PANEL_ID).append("\" class=\"orch-panel agent-context-panel\">");
        html.append("<div class=\"orch-panel-heading\"><h3>Context Default</h3></div>");

        if (message != null && !message.isBlank()) {
            html.append("<div class=\"").append(error ? "orch-status-error" : "orch-status").append("\">")
                    .append(escape(message)).append("</div>");
        }

        html.append("<form class=\"orch-form-stack\" data-htmx-error-swap=\"true\""
                + " hx-put=\"/api/agents/").append(encodedAgentId).append("/context/defaults\""
                + " hx-target=\"#").append(CONTEXT_PANEL_ID).append("\" hx-swap=\"outerHTML\">");

        // Expected version (hidden)
        html.append("<input type=\"hidden\" name=\"expectedVersion\" value=\"")
                .append(currentDefault != null ? currentDefault.version() : 0).append("\">");

        // Selection kind
        html.append("<label>Kind<select name=\"selectionKind\" class=\"orch-select\">");
        String currentKind = currentDefault != null && currentDefault.selectionKind() != null
                ? currentDefault.selectionKind().name() : "";
        html.append("<option value=\"MATERIALIZATION\"").append("MATERIALIZATION".equals(currentKind) ? " selected" : "")
                .append(">Materialization (Project)</option>");
        html.append("<option value=\"PERSONAL\"").append("PERSONAL".equals(currentKind) ? " selected" : "")
                .append(">Personal</option>");
        html.append("</select></label>");

        // Materialization key (for materialization kind)
        html.append("<label>Project Key<input type=\"text\" name=\"materializationKey\""
                + " placeholder=\"agent/project-key\" class=\"orch-input\""
                + " value=\"").append(currentDefault != null && currentDefault.materializationKey() != null
                ? escapeAttr(currentDefault.materializationKey().serialized()) : "").append("\"></label>");

        // Materialization generation
        html.append("<label>Generation<input type=\"number\" name=\"materializationGeneration\""
                + " class=\"orch-input\" min=\"1\""
                + " value=\"").append(currentDefault != null ? currentDefault.materializationGeneration() : "").append("\"></label>");

        // Personal selection
        html.append("<label>Personal Area<select name=\"personalSelection\" class=\"orch-select\">");
        String currentPersonal = currentDefault != null && currentDefault.personalSelection() != null
                ? currentDefault.personalSelection().name() : "";
        for (PersonalSelection ps : PersonalSelection.values()) {
            html.append("<option value=\"").append(ps.name()).append("\"")
                    .append(ps.name().equals(currentPersonal) ? " selected" : "")
                    .append(">").append(ps.name()).append("</option>");
        }
        html.append("</select></label>");

        // Reason
        html.append("<label>Reason<input type=\"text\" name=\"reason\" class=\"orch-input\""
                + " placeholder=\"Why this default?\" required></label>");

        // Operation key (for idempotency)
        html.append("<label>Operation Key<input type=\"text\" name=\"operationKey\" class=\"orch-input\""
                + " placeholder=\"optional idempotency key\"></label>");

        html.append("<div class=\"orch-actions\">");
        html.append("<button type=\"submit\" class=\"button orch-primary\">Update Default</button>");
        html.append("<button type=\"button\" class=\"button button-secondary\""
                + " hx-get=\"/api/agents/").append(encodedAgentId).append("/context/panel\""
                + " hx-target=\"#").append(CONTEXT_PANEL_ID).append("\" hx-swap=\"outerHTML\">Cancel</button>");
        html.append("</div>");

        html.append("</form>");

        // Ready materializations reference
        if (readyMaterializations != null && !readyMaterializations.isEmpty()) {
            html.append("<section class=\"context-section\"><h4>Available Projects</h4>");
            html.append("<table class=\"orch-table compact\"><thead><tr><th>Key</th><th>Gen</th></tr></thead><tbody>");
            for (MaterializationRecord m : readyMaterializations) {
                html.append("<tr><td><code>").append(escape(m.materializationKey().serialized()))
                        .append("</code></td><td>").append(m.generation()).append("</td></tr>");
            }
            html.append("</tbody></table></section>");
        }

        html.append("</div>");
        return html.toString();
    }

    static String materializationList(String encodedAgentId, List<MaterializationRecord> records, String message) {
        StringBuilder html = new StringBuilder();
        html.append("<div id=\"").append(CONTEXT_PANEL_ID).append("\" class=\"orch-panel agent-context-panel\">");
        html.append("<div class=\"orch-panel-heading\">");
        html.append("<h3>Materializations</h3>");
        html.append("<button type=\"button\" class=\"button button-secondary small\""
                + " hx-get=\"/api/agents/").append(encodedAgentId).append("/context/panel\""
                + " hx-target=\"#").append(CONTEXT_PANEL_ID).append("\" hx-swap=\"outerHTML\">Back</button>");
        html.append("</div>");

        if (message != null && !message.isBlank()) {
            html.append("<div class=\"orch-status\">").append(escape(message)).append("</div>");
        }

        if (records == null || records.isEmpty()) {
            html.append("<p class=\"orch-muted\">No materializations exist for this agent.</p>");
        } else {
            html.append("<table class=\"orch-table compact\"><thead><tr>"
                    + "<th>Key</th><th>State</th><th>Gen</th><th>Created</th><th>Updated</th></tr></thead><tbody>");
            for (MaterializationRecord m : records) {
                html.append("<tr>")
                        .append("<td><code>").append(escape(m.materializationKey().serialized())).append("</code></td>")
                        .append("<td>").append(stateChip(m.state())).append("</td>")
                        .append("<td>").append(m.generation()).append("</td>")
                        .append("<td>").append(time(m.createdAt())).append("</td>")
                        .append("<td>").append(time(m.updatedAt())).append("</td>")
                        .append("</tr>");
            }
            html.append("</tbody></table>");
        }

        // Provision form
        html.append("<section class=\"context-section\"><h4>Provision Project</h4>");
        html.append("<form class=\"orch-form-stack\" data-htmx-error-swap=\"true\""
                + " hx-post=\"/api/agents/").append(encodedAgentId).append("/context/materializations\""
                + " hx-target=\"#").append(CONTEXT_PANEL_ID).append("\" hx-swap=\"outerHTML\">");
        html.append("<label>Project Key<input type=\"text\" name=\"projectKey\" class=\"orch-input\""
                + " placeholder=\"e.g. my-project\" required></label>");
        html.append("<label>Operation Key<input type=\"text\" name=\"operationKey\" class=\"orch-input\""
                + " placeholder=\"optional\"></label>");
        html.append("<div class=\"orch-actions\">");
        html.append("<button type=\"submit\" class=\"button orch-primary\">Provision</button>");
        html.append("</div>");
        html.append("</form></section>");

        html.append("</div>");
        return html.toString();
    }

    static String errorPanel(String encodedAgentId, String title, String message) {
        return "<div id=\"" + CONTEXT_PANEL_ID + "\" class=\"orch-panel agent-context-panel\">"
                + "<div class=\"orch-panel-heading\"><h3>" + escape(title) + "</h3></div>"
                + "<div class=\"orch-status-error\">" + escape(message) + "</div>"
                + "<button type=\"button\" class=\"button button-secondary\""
                + " hx-get=\"/api/agents/" + encodedAgentId + "/context/panel\""
                + " hx-target=\"#" + CONTEXT_PANEL_ID + "\" hx-swap=\"outerHTML\">Retry</button>"
                + "</div>";
    }

    private static String stateChip(MaterializationState state) {
        if (state == null) return "<span class=\"orch-chip\">UNKNOWN</span>";
        String css = switch (state) {
            case READY -> "orch-status-chip active";
            case PROVISIONING -> "orch-chip";
            case FAILED -> "orch-status-chip disabled";
            case REVOKED -> "orch-status-chip disabled";
        };
        return "<span class=\"" + css + "\">" + escape(state.name()) + "</span>";
    }

    private static String time(Instant instant) {
        return instant == null ? "—" : TIME_FORMAT.format(instant);
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String escapeAttr(String value) {
        return escape(value);
    }

    private static String truncateKey(String key) {
        if (key == null) return "—";
        return key.length() > 48 ? key.substring(0, 45) + "…" : key;
    }
}
