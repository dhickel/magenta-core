package io.mindspice.magenta2.api.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * HTMX fragment renderer for the system chat panel. Receives only
 * authorized secret-free records; exposes no absolute paths, credentials,
 * or lifecycle proposal internals.
 */
final class SystemChatFragments {

    static final String CHAT_PANEL_ID = "system-chat-panel";
    static final String HISTORY_ID = "system-chat-history";
    static final String PROPOSALS_ID = "system-chat-proposals";

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    private SystemChatFragments() {}

    /** Renders the full system chat panel shell (SSE stream attaches here). */
    static String panel(String encodedAgentId, String conversationId, boolean available) {
        if (!available) {
            return "<div id=\"" + CHAT_PANEL_ID + "\" class=\"orch-panel system-chat-panel\">"
                    + "<div class=\"orch-panel-heading\"><h3>System Chat</h3></div>"
                    + "<div class=\"orch-status\">System chat is currently unavailable. "
                    + "Ensure system chat is enabled and an agent is configured.</div>"
                    + "<button type=\"button\" class=\"button button-secondary\""
                    + " hx-get=\"/api/agents/" + encodedAgentId + "/system-chat/panel\""
                    + " hx-target=\"#" + CHAT_PANEL_ID + "\" hx-swap=\"outerHTML\">Retry</button>"
                    + "<div id=\"" + HISTORY_ID + "\" class=\"system-chat-history\"></div>"
                    + "<div id=\"" + PROPOSALS_ID + "\" class=\"orch-panel system-chat-proposals\"></div>"
                    + "</div>";
        }

        return "<div id=\"" + CHAT_PANEL_ID + "\" class=\"orch-panel system-chat-panel\""
                + " data-system-chat-agent=\"" + escapeAttr(encodedAgentId) + "\""
                + " data-system-chat-conversation=\"" + escapeAttr(conversationId) + "\">"
                + "<div class=\"orch-panel-heading\">"
                + "<h3>System Chat</h3>"
                + "<span class=\"orch-chip\">" + escape(conversationId) + "</span>"
                + "</div>"
                + "<div id=\"" + HISTORY_ID + "\" class=\"system-chat-history\""
                + " hx-get=\"/api/agents/" + encodedAgentId + "/system-chat/panel/history\""
                + " hx-trigger=\"load\" hx-swap=\"innerHTML\">"
                + "<p class=\"orch-muted\">Loading conversation…</p>"
                + "</div>"
                + "<form id=\"system-chat-input-form\" class=\"system-chat-input-form\""
                + " onsubmit=\"return false;\">"
                + "<input type=\"text\" id=\"system-chat-input\" class=\"orch-input\""
                + " placeholder=\"Send a message to the system assistant…\""
                + " autocomplete=\"off\">"
                + "<button type=\"button\" id=\"system-chat-send-btn\" class=\"button orch-primary\">Send</button>"
                + "<button type=\"button\" id=\"system-chat-cancel-btn\" class=\"button button-secondary\""
                + " style=\"display:none;\">Cancel</button>"
                + "</form>"
                + "<div class=\"system-chat-stream-status\"></div>"
                + "<div id=\"" + PROPOSALS_ID + "\" class=\"orch-panel system-chat-proposals\""
                + " hx-get=\"/api/agents/" + encodedAgentId + "/system-chat/proposals\""
                + " hx-trigger=\"load, refreshProposals from:body\""
                + " hx-target=\"#" + PROPOSALS_ID + "\" hx-swap=\"outerHTML\">"
                + "<p class=\"orch-muted\">Loading action proposals…</p></div>"
                + "</div>";
    }

    static String history(String encodedAgentId, List<MessageRecord> messages) {
        StringBuilder html = new StringBuilder();
        html.append("<div id=\"").append(HISTORY_ID).append("\" class=\"system-chat-history\">");
        if (messages == null || messages.isEmpty()) {
            html.append("<p class=\"orch-muted\">No messages yet. Start a conversation.</p>");
        } else {
            for (MessageRecord msg : messages) {
                String roleClass = "user".equals(msg.role()) ? "system-chat-msg-user" : "system-chat-msg-assistant";
                html.append("<div class=\"system-chat-msg ").append(roleClass).append("\">");
                html.append("<div class=\"system-chat-msg-role\">")
                        .append(escape("user".equals(msg.role()) ? "You" : "Assistant"))
                        .append("</div>");
                html.append("<div class=\"system-chat-msg-text\">")
                        .append(escape(msg.text() != null ? msg.text() : ""))
                        .append("</div>");
                html.append("</div>");
            }
        }
        html.append("</div>");
        return html.toString();
    }

    static String proposals(String encodedAgentId, List<ProposalRecord> proposals) {
        StringBuilder html = new StringBuilder();
        html.append("<div id=\"").append(PROPOSALS_ID).append("\" class=\"orch-panel system-chat-proposals\">");
        html.append("<div class=\"orch-panel-heading\"><h4>Action Proposals</h4>");
        html.append("<button type=\"button\" class=\"button button-secondary small\""
                + " hx-get=\"/api/agents/").append(encodedAgentId).append("/system-chat/proposals\""
                + " hx-target=\"#").append(PROPOSALS_ID).append("\" hx-swap=\"outerHTML\">Refresh</button>");
        html.append("</div>");

        if (proposals == null || proposals.isEmpty()) {
            html.append("<p class=\"orch-muted\">No pending proposals.</p>");
        } else {
            html.append("<table class=\"orch-table compact\"><thead><tr>"
                    + "<th>Action</th><th>Target</th><th>State</th><th>Created</th><th></th></tr></thead><tbody>");
            for (ProposalRecord p : proposals) {
                html.append("<tr>")
                        .append("<td>").append(stateChip(p.action())).append("</td>")
                        .append("<td><code>").append(escape(p.targetId())).append("</code></td>")
                        .append("<td>").append(escape(p.state())).append("</td>")
                        .append("<td>").append(time(p.createdAt())).append("</td>");
                if ("PENDING".equals(p.state())) {
                    html.append("<td><button type=\"button\" class=\"button button-secondary small\""
                            + " data-htmx-error-swap=\"true\""
                            + " hx-post=\"/api/agents/" + encodedAgentId
                            + "/system-chat/proposals/" + URLEncoder.encode(p.proposalId(), StandardCharsets.UTF_8)
                            + "/confirm\""
                            + " hx-target=\"#" + PROPOSALS_ID + "\" hx-swap=\"outerHTML\""
                            + " hx-confirm=\"Confirm " + escape(p.action()) + " on "
                            + escape(p.targetId()) + "?\">Confirm</button></td>");
                } else {
                    html.append("<td></td>");
                }
                html.append("</tr>");
            }
            html.append("</tbody></table>");
        }
        html.append("</div>");
        return html.toString();
    }

    static String streamStatus(String status) {
        return "<div class=\"system-chat-stream-status\">" + escape(status) + "</div>";
    }

    private static String stateChip(String action) {
        if (action == null) return "<span class=\"orch-chip\">—</span>";
        String css = switch (action.toUpperCase(Locale.ROOT)) {
            case "PAUSE" -> "orch-chip";
            case "RESUME" -> "orch-status-chip active";
            case "RETRY", "REPLAY" -> "orch-chip";
            case "CANCEL" -> "orch-status-chip disabled";
            default -> "orch-chip";
        };
        return "<span class=\"" + css + "\">" + escape(action) + "</span>";
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

    /** Local record for rendering; never exposes service internals. */
    record MessageRecord(String role, String text, Instant at) {}
    record ProposalRecord(String proposalId, String action, String targetId,
                          String state, Instant createdAt) {}
}
