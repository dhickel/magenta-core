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

/** Renders secret-free project-chat fragments from controller-authorized records. */
final class ProjectChatFragments {
    static final String CHAT_PANEL_ID = "project-chat-panel";
    static final String HISTORY_ID = "project-chat-history";

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    private ProjectChatFragments() {}

    static String panel(String projectId, String memberAgentId,
                        String conversationId, boolean available) {
        Div panel = new Div().withId(CHAT_PANEL_ID).withClass("orch-panel project-chat-panel");
        if (!available) {
            panel.withChild(heading("Project Chat"))
                .withChild(new Div().withClass("orch-status")
                    .withInnerText("Project chat is currently unavailable. "
                        + "Ensure system chat is enabled, a member agent is selected, "
                        + "and the agent has a ready project materialization."))
                .withChild(Button.create("Retry").withClass("button-secondary")
                    .hxGet(route(projectId, memberAgentId, "/panel"))
                    .hxTarget("#" + CHAT_PANEL_ID).hxSwap("outerHTML"))
                .withChild(new Div().withId(HISTORY_ID).withClass("project-chat-history"));
            return panel.render();
        }

        panel.withAttribute("data-project-chat-project", projectId)
            .withAttribute("data-project-chat-agent", memberAgentId)
            .withAttribute("data-project-chat-conversation", conversationId)
            .withChild(heading("Project Chat", chip(conversationId)))
            .withChild(new Div().withId(HISTORY_ID).withClass("project-chat-history")
                .hxGet(route(projectId, memberAgentId, "/panel/history"))
                .hxTrigger("load").hxSwap("outerHTML")
                .withChild(new Paragraph("Loading conversation…").withClass("orch-muted")))
            .withChild(inputForm())
            .withChild(new Div().withClass("project-chat-stream-status"));
        return panel.render();
    }

    static String history(String projectId, String memberAgentId,
                          List<MessageRecord> messages) {
        Div history = new Div().withId(HISTORY_ID).withClass("project-chat-history");
        if (messages == null || messages.isEmpty()) {
            return history.withChild(new Paragraph("No messages yet. Start a conversation.")
                .withClass("orch-muted")).render();
        }
        for (MessageRecord message : messages) {
            boolean user = "user".equals(message.role());
            history.withChild(new Div().withClass("project-chat-msg "
                    + (user ? "project-chat-msg-user" : "project-chat-msg-assistant"))
                .withChild(new Div().withClass("project-chat-msg-role")
                    .withInnerText(user ? "You" : "Assistant"))
                .withChild(new Div().withClass("project-chat-msg-text")
                    .withInnerText(message.text() == null ? "" : message.text())));
        }
        return history.render();
    }

    static String streamStatus(String status) {
        return new Div().withClass("project-chat-stream-status")
            .withInnerText(status == null ? "" : status).render();
    }

    /** Render a member-agent selector for the project chat tab. */
    static String memberSelector(String projectId, String selectedAgentId,
                                  List<MemberOption> members) {
        Div selector = new Div().withClass("project-chat-agent-selector");
        for (MemberOption member : members) {
            Button button = Button.create(member.displayName());
            if (member.agentId().equals(selectedAgentId)) {
                button.withClass("active");
            }
            button.withAttribute("hx-get", route(projectId, member.agentId(), "/panel"))
                .withAttribute("hx-target", "#" + CHAT_PANEL_ID)
                .withAttribute("hx-swap", "outerHTML")
                .withAttribute("data-htmx-error-swap", "true");
            selector.withChild(button);
        }
        return selector.render();
    }

    private static Component inputForm() {
        return Form.create().withId("project-chat-input-form")
            .withClass("project-chat-input-form")
            .withAttribute("onsubmit", "return false;")
            .withChild(TextInput.create("").withId("project-chat-input")
                .withClass("orch-input")
                .withAttribute("type", "text")
                .withAttribute("placeholder", "Send a message to the project assistant…")
                .withAttribute("autocomplete", "off"))
            .withChild(Button.create("Send").withId("project-chat-send-btn")
                .withClass("orch-primary").withAttribute("type", "button"))
            .withChild(Button.create("Cancel").withId("project-chat-cancel-btn")
                .withClass("button-secondary").withAttribute("type", "button")
                .withAttribute("hidden", "hidden"));
    }

    private static Component heading(String title, Component... trailing) {
        Div heading = new Div().withClass("orch-panel-heading")
            .withChild(new HtmlTag("h3").withInnerText(title));
        for (Component component : trailing) {
            heading.withChild(component);
        }
        return heading;
    }

    private static Component chip(String value) {
        return new HtmlTag("span").withClass("orch-chip")
            .withInnerText(value == null ? "" : value);
    }

    static String route(String projectId, String memberAgentId, String suffix) {
        return "/api/projects/" + encode(projectId) + "/chat" + suffix
                + (suffix.contains("?") ? "&" : "?")
                + "memberAgentId=" + encode(memberAgentId);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String time(Instant instant) {
        return instant == null ? "—" : TIME_FORMAT.format(instant);
    }

    record MessageRecord(String role, String text, Instant at) {}
    record MemberOption(String agentId, String displayName) {}
}
