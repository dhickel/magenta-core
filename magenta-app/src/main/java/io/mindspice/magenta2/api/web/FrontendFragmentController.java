package io.mindspice.magenta2.api.web;

import io.mindspice.magenta2.ai.chat.model.ChatMessage;
import io.mindspice.magenta2.ai.chat.model.ChatSession;
import io.mindspice.magenta2.ai.chat.service.ChatService;
import io.mindspice.simplypages.components.Div;
import io.mindspice.simplypages.components.Header;
import io.mindspice.simplypages.components.Paragraph;
import io.mindspice.simplypages.core.HtmlTag;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

@Controller
@RequestMapping(value = "/api/fragments", produces = MediaType.TEXT_HTML_VALUE)
public class FrontendFragmentController {
    private final ChatService chatService;

    public FrontendFragmentController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping("/chat/transcript")
    @ResponseBody
    public String chatTranscript(@RequestParam String conversationId) {
        List<ChatMessage> messages = conversationId == null || conversationId.isBlank() || "new".equals(conversationId)
            ? List.of()
            : chatService.history(conversationId);
        Div transcript = new Div().withId("chat-history");
        if (messages.isEmpty()) {
            transcript.withChild(new Paragraph("No messages in this session yet."));
        } else {
            messages.forEach(message -> transcript.withChild(message(message)));
        }
        return transcript.render();
    }

    @GetMapping("/chat/sessions")
    @ResponseBody
    public String chatSessions() {
        HtmlTag list = new HtmlTag("ul").withId("chat-session-list");
        List<ChatSession> sessions = chatService.listSessions();
        if (sessions.isEmpty()) {
            list.withChild(new HtmlTag("li").withClass("chat-session-empty").withInnerText("No persisted sessions yet."));
        } else {
            sessions.forEach(session -> list.withChild(session(session)));
        }
        return list.render();
    }

    @GetMapping("/chat/planning")
    @ResponseBody
    public String chatPlanning(@RequestParam(required = false) String conversationId) {
        return new Div()
            .withId("chat-planning-panel")
            .withAttribute("aria-live", "polite")
            .withChild(Header.H3("Planning"))
            .withChild(new Paragraph("Planning controls update during active plan turns."))
            .render();
    }

    private HtmlTag message(ChatMessage message) {
        String role = message.role() == null || message.role().isBlank() ? "assistant" : message.role();
        Div body = new Div().withClass("chat-message-body");
        if (message.renderedHtml() != null && !message.renderedHtml().isBlank()) {
            body.withUnsafeHtml(message.renderedHtml());
        } else {
            body.withChild(new Paragraph(message.text() == null ? "" : message.text()));
        }
        HtmlTag article = new HtmlTag("article")
            .withClass("chat-message")
            .withClass("chat-message-" + role.toLowerCase())
            .withChild(new Div().withClass("chat-message-role").withInnerText(role))
            .withChild(body);
        if (message.thinkingHtml() != null && !message.thinkingHtml().isBlank()) {
            article.withChild(new HtmlTag("details").withClass("chat-thinking")
                .withChild(new HtmlTag("summary").withClass("chat-thinking-toggle").withInnerText("Show thinking"))
                .withChild(new Div().withClass("chat-thinking-body").withUnsafeHtml(message.thinkingHtml())));
        }
        return article;
    }

    private HtmlTag session(ChatSession session) {
        String title = session.title() == null || session.title().isBlank()
            ? shortConversationLabel(session.conversationId())
            : session.title();
        Div entry = new Div().withClass("chat-session-entry")
            .withChild(new Div().withClass("chat-session-topline")
                .withChild(new HtmlTag("span").withClass("chat-session-check")
                    .withChild(new HtmlTag("input", true)
                        .withAttribute("type", "checkbox")
                        .withAttribute("data-bulk-select", session.conversationId())))
                .withChild(new HtmlTag("a")
                    .withClass("chat-session-title")
                    .withAttribute("href", "/chat?conversationId=" + session.conversationId())
                    .withChild(new HtmlTag("span").withClass("chat-session-title-label")
                        .withChild(new HtmlTag("span").withClass("chat-session-title-text").withInnerText(title)))
                    .withChild(new HtmlTag("span").withClass("chat-session-inline-hash").withInnerText(shortConversationLabel(session.conversationId()))))
                .withChild(new Div().withClass("chat-session-actions")
                    .withChild(new HtmlTag("button").withAttribute("type", "button").withAttribute("data-rename-id", session.conversationId()).withInnerText("R"))
                    .withChild(new HtmlTag("button").withAttribute("type", "button").withAttribute("data-delete-id", session.conversationId()).withInnerText("D"))
                    .withChild(new HtmlTag("button").withAttribute("type", "button").withAttribute("data-archive-id", session.conversationId()).withInnerText("A"))));
        if (session.outputCount() > 0) {
            entry.withChild(new Div().withClass("chat-session-output-row")
                .withChild(new HtmlTag("span")
                    .withClass("chat-session-output-badge")
                    .withInnerText(session.outputCount() + " Outputs")));
        }
        return new HtmlTag("li").withClass("chat-session-item")
            .withChild(entry);
    }

    private String shortConversationLabel(String conversationId) {
        if (conversationId == null || conversationId.length() <= 8) {
            return conversationId == null ? "" : conversationId;
        }
        return conversationId.substring(0, 8);
    }
}
