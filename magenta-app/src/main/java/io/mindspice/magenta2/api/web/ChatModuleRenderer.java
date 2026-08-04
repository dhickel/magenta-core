package io.mindspice.magenta2.api.web;

import io.mindspice.simplypages.components.Div;
import io.mindspice.simplypages.components.Paragraph;
import io.mindspice.simplypages.components.chat.ChatTransportMode;
import io.mindspice.simplypages.components.chat.ChatUiConfig;
import io.mindspice.simplypages.components.forms.Button;
import io.mindspice.simplypages.components.forms.Form;
import io.mindspice.simplypages.components.forms.TextArea;
import io.mindspice.simplypages.core.Component;
import io.mindspice.simplypages.core.HtmlTag;
import io.mindspice.simplypages.modules.ChatModule;

final class ChatModuleRenderer {
    private ChatModuleRenderer() {
    }

    static Component sessionChatModule() {
        return ChatModule.create()
            .withModuleId("magenta-chat-module")
            .withTranscript(new Div().withId("chat-history"))
            .withComposer(new Div()
                .withChild(new Div().withId("chat-planning-panel").withAttribute("aria-live", "polite"))
                .withChild(new Div().withId("chat-queued-messages-panel").withAttribute("aria-live", "polite"))
                .withChild(Form.create().withId("chat-form")
                    .withChild(TextArea.create("message").withId("chat-input").withRows(6)
                        .withPlaceholder("Type a message (Enter to send, Shift+Enter newline)")
                        .withAttribute("autocomplete", "off"))
                    .withChild(Button.submit("Send"))))
            .withUiConfig(new ChatUiConfig(
                "new",
                ChatTransportMode.SSE,
                "/api/fragments/chat/transcript",
                "/api/chat/stream",
                "#chat-history",
                "outerHTML",
                null
            ));
    }

    static Component embeddedPlanChatModule(
        String planId,
        Component transcript,
        Component composer
    ) {
        String safePlanId = planId == null || planId.isBlank() ? "new" : planId;
        return ChatModule.create()
            .withModuleId("plan-chat-module-" + safePlanId.replaceAll("[^A-Za-z0-9_-]", "_"))
            .withTranscript(transcript)
            .withComposer(composer)
            .withUiConfig(new ChatUiConfig(
                "plan:" + safePlanId,
                ChatTransportMode.POLLING,
                "/plans/_editor/" + safePlanId + "/planning-chat/transcript",
                null,
                "#plan-chat-history",
                "outerHTML",
                null
            ));
    }

    static Component planningQuestionCard(String id, String promptText, String countLabel) {
        Div card = new Div().withClass("planning-question-card");
        if (id != null && !id.isBlank()) {
            card.withId(id);
        }
        card.withChild(new HtmlTag("span")
            .withClass("planning-question-mark")
            .withAttribute("aria-hidden", "true")
            .withInnerText("?"));
        if (countLabel != null && !countLabel.isBlank()) {
            card.withChild(new HtmlTag("span")
                .withClass("planning-question-count")
                .withInnerText(countLabel));
        }
        return card.withChild(new Div()
            .withClass("planning-question-text")
            .withInnerText(promptText == null ? "" : promptText));
    }

    static HtmlTag message(String role, String text) {
        String resolvedRole = role == null || role.isBlank() ? "assistant" : role.toLowerCase();
        return new HtmlTag("article")
            .withClass("chat-message")
            .withClass("chat-message-" + resolvedRole)
            .withChild(new Div().withClass("chat-message-role").withInnerText(resolvedRole))
            .withChild(new Div().withClass("chat-message-body")
                .withChild(new Paragraph(text == null ? "" : text)));
    }
}
