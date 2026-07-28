package io.mindspice.magenta2.api.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import io.mindspice.simplypages.components.Div;
import io.mindspice.simplypages.components.Paragraph;
import io.mindspice.simplypages.components.forms.Button;
import io.mindspice.simplypages.components.forms.Form;
import io.mindspice.simplypages.components.forms.TextInput;
import io.mindspice.simplypages.core.Component;
import io.mindspice.simplypages.core.HtmlTag;

/** Renders secret-free system-chat fragments from controller-authorized records. */
final class SystemChatFragments {
    static final String CHAT_PANEL_ID = "system-chat-panel";
    static final String HISTORY_ID = "system-chat-history";
    static final String PROPOSALS_ID = "system-chat-proposals";

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    private SystemChatFragments() {}

    static String panel(String encodedAgentId, String conversationId, boolean available) {
        Div panel = new Div().withId(CHAT_PANEL_ID).withClass("orch-panel system-chat-panel");
        if (!available) {
            panel.withChild(heading("System Chat"))
                .withChild(new Div().withClass("orch-status")
                    .withInnerText("System chat is currently unavailable. Ensure system chat is enabled and an agent is configured."))
                .withChild(Button.create("Retry").withClass("button-secondary")
                    .hxGet(route(encodedAgentId, "/panel"))
                    .hxTarget("#" + CHAT_PANEL_ID).hxSwap("outerHTML"))
                .withChild(new Div().withId(HISTORY_ID).withClass("system-chat-history"))
                .withChild(new Div().withId(PROPOSALS_ID).withClass("orch-panel system-chat-proposals"));
            return panel.render();
        }

        panel.withAttribute("data-system-chat-agent", encodedAgentId)
            .withAttribute("data-system-chat-conversation", conversationId)
            .withChild(heading("System Chat", chip(conversationId)))
            .withChild(new Div().withId(HISTORY_ID).withClass("system-chat-history")
                .hxGet(route(encodedAgentId, "/panel/history")).hxTrigger("load").hxSwap("innerHTML")
                .withChild(new Paragraph("Loading conversation…").withClass("orch-muted")))
            .withChild(inputForm())
            .withChild(new Div().withClass("system-chat-stream-status"))
            .withChild(new Div().withId(PROPOSALS_ID).withClass("orch-panel system-chat-proposals")
                .hxGet(route(encodedAgentId, "/proposals"))
                .hxTrigger("load, refreshProposals from:body")
                .hxTarget("#" + PROPOSALS_ID).hxSwap("outerHTML")
                .withChild(new Paragraph("Loading action proposals…").withClass("orch-muted")));
        return panel.render();
    }

    static String history(String encodedAgentId, List<MessageRecord> messages) {
        Div history = new Div().withId(HISTORY_ID).withClass("system-chat-history");
        if (messages == null || messages.isEmpty()) {
            return history.withChild(new Paragraph("No messages yet. Start a conversation.").withClass("orch-muted")).render();
        }
        for (MessageRecord message : messages) {
            boolean user = "user".equals(message.role());
            history.withChild(new Div().withClass("system-chat-msg " + (user ? "system-chat-msg-user" : "system-chat-msg-assistant"))
                .withChild(new Div().withClass("system-chat-msg-role").withInnerText(user ? "You" : "Assistant"))
                .withChild(new Div().withClass("system-chat-msg-text").withInnerText(message.text() == null ? "" : message.text())));
        }
        return history.render();
    }

    static String proposals(String encodedAgentId, List<ProposalRecord> proposals) {
        Div panel = new Div().withId(PROPOSALS_ID).withClass("orch-panel system-chat-proposals");
        panel.withChild(heading("Action Proposals", Button.create("Refresh").withClass("button-secondary small")
            .hxGet(route(encodedAgentId, "/proposals")).hxTarget("#" + PROPOSALS_ID).hxSwap("outerHTML")));
        if (proposals == null || proposals.isEmpty()) {
            return panel.withChild(new Paragraph("No pending proposals.").withClass("orch-muted")).render();
        }

        HtmlTag table = new HtmlTag("table").withClass("orch-table compact");
        HtmlTag headRow = new HtmlTag("tr");
        for (String title : List.of("Action", "Target", "State", "Created", "")) {
            headRow.withChild(new HtmlTag("th").withInnerText(title));
        }
        table.withChild(new HtmlTag("thead").withChild(headRow));
        HtmlTag body = new HtmlTag("tbody");
        for (ProposalRecord proposal : proposals) {
            HtmlTag row = new HtmlTag("tr")
                .withChild(new HtmlTag("td").withChild(stateChip(proposal.action())))
                .withChild(new HtmlTag("td").withChild(new HtmlTag("code").withInnerText(proposal.targetId())))
                .withChild(new HtmlTag("td").withInnerText(proposal.state()))
                .withChild(new HtmlTag("td").withInnerText(time(proposal.createdAt())));
            row.withChild(new HtmlTag("td").withChild(
                "PENDING".equals(proposal.state()) ? confirmationForm(encodedAgentId, proposal) : new HtmlTag("span")));
            body.withChild(row);
        }
        return panel.withChild(table.withChild(body)).render();
    }

    static String streamStatus(String status) {
        return new Div().withClass("system-chat-stream-status").withInnerText(status == null ? "" : status).render();
    }

    private static Component inputForm() {
        return Form.create().withId("system-chat-input-form").withClass("system-chat-input-form")
            .withAttribute("onsubmit", "return false;")
            .withChild(TextInput.create("").withId("system-chat-input").withClass("orch-input")
                .withAttribute("type", "text").withAttribute("placeholder", "Send a message to the system assistant…")
                .withAttribute("autocomplete", "off"))
            .withChild(Button.create("Send").withId("system-chat-send-btn").withClass("orch-primary")
                .withAttribute("type", "button"))
            .withChild(Button.create("Cancel").withId("system-chat-cancel-btn").withClass("button-secondary")
                .withAttribute("type", "button").withAttribute("hidden", "hidden"));
    }

    private static Component confirmationForm(String encodedAgentId, ProposalRecord proposal) {
        return Form.create().withClass("system-chat-confirm-form")
            .withAttribute("data-htmx-error-swap", "true")
            .hxPost(route(encodedAgentId, "/proposals/" + URLEncoder.encode(proposal.proposalId(), StandardCharsets.UTF_8) + "/confirm"))
            .hxTarget("#" + PROPOSALS_ID).hxSwap("outerHTML")
            .withChild(new HtmlTag("input").withAttribute("type", "hidden").withAttribute("name", "digest")
                .withAttribute("value", proposal.canonicalDigest()))
            .withChild(Button.create("Confirm").withClass("button-secondary small").withAttribute("type", "submit")
                .withAttribute("hx-confirm", "Confirm " + proposal.action() + " on " + proposal.targetId() + "?"));
    }

    private static Component heading(String title, Component... trailing) {
        Div heading = new Div().withClass("orch-panel-heading")
            .withChild(new HtmlTag("h3").withInnerText(title));
        for (Component component : trailing) {
            heading.withChild(component);
        }
        return heading;
    }

    private static Component stateChip(String action) {
        String css = switch (action == null ? "" : action.toUpperCase(Locale.ROOT)) {
            case "RESUME" -> "orch-status-chip active";
            case "CANCEL" -> "orch-status-chip disabled";
            default -> "orch-chip";
        };
        return new HtmlTag("span").withClass(css).withInnerText(action == null ? "—" : action);
    }

    private static Component chip(String value) {
        return new HtmlTag("span").withClass("orch-chip").withInnerText(value == null ? "" : value);
    }

    private static String route(String encodedAgentId, String suffix) {
        return "/api/agents/" + encodedAgentId + "/system-chat" + suffix;
    }

    private static String time(Instant instant) {
        return instant == null ? "—" : TIME_FORMAT.format(instant);
    }

    record MessageRecord(String role, String text, Instant at) {}
    record ProposalRecord(String proposalId, String action, String targetId, String canonicalDigest,
                          String state, Instant createdAt) {}
}
