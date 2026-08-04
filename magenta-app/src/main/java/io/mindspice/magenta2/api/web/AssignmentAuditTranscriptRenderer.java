package io.mindspice.magenta2.api.web;

import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta2.ai.chat.repository.AuditRepository;
import io.mindspice.simplypages.components.Div;
import io.mindspice.simplypages.core.Component;
import io.mindspice.simplypages.core.HtmlTag;
import org.springframework.util.StringUtils;

final class AssignmentAuditTranscriptRenderer {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private AssignmentAuditTranscriptRenderer() {
    }

    static Component render(AuditRepository.AuditEvent event) {
        return switch (event.eventType()) {
            case "assistant_msg" -> assistant(event);
            case "tool_exec" -> tool(event);
            case "error" -> system(event, "error", firstNonBlank(event.messageText(), event.errorType(), "Error"));
            case "context" -> system(event, "context", contextSummary(event));
            case "compaction" -> system(event, "compaction", compactionSummary(event));
            case "model_thinking" -> system(event, "thinking", firstNonBlank(event.messageText(), "Model thinking captured"));
            case "user_msg" -> system(event, "user", firstNonBlank(event.messageText(), "User message"));
            default -> system(event, event.eventType(), firstNonBlank(event.messageText(), event.resultSummary(), event.errorType(), event.eventType()));
        };
    }

    private static Component assistant(AuditRepository.AuditEvent event) {
        Div message = message("chat-message-assistant", "assistant", event);
        String thinking = thinkingHtml(event);
        if (StringUtils.hasText(thinking)) {
            message.withChild(new HtmlTag("details").withClass("chat-thinking")
                .withChild(new HtmlTag("summary").withClass("chat-thinking-toggle")
                    .withChild(new HtmlTag("span").withClass("chat-thinking-show").withInnerText("Show thinking"))
                    .withChild(new HtmlTag("span").withClass("chat-thinking-hide").withInnerText("Hide thinking")))
                .withChild(new Div().withClass("chat-thinking-body").withUnsafeHtml(thinking)));
        }
        message.withChild(new Div().withClass("chat-message-body")
            .withChild(new HtmlTag("p").withInnerText(firstNonBlank(event.messageText(), ""))));
        return message;
    }

    private static Component tool(AuditRepository.AuditEvent event) {
        Div message = message("chat-message-tool", "tool", event);
        HtmlTag details = new HtmlTag("details").withClass("chat-tool");
        details.withChild(new HtmlTag("summary").withClass("chat-tool-toggle")
            .withChild(new HtmlTag("span").withClass("chat-tool-name").withInnerText(firstNonBlank(event.toolName(), "tool")))
            .withChild(new HtmlTag("span").withClass("chat-tool-status").withInnerText(firstNonBlank(event.toolStatus(), "recorded")))
            .withChild(new HtmlTag("span").withClass("chat-tool-summary").withInnerText(firstNonBlank(event.resultSummary(), event.resultPreview(), ""))));
        Div body = new Div().withClass("chat-tool-body");
        body.withChild(new Div().withClass("chat-tool-meta").withInnerText(firstNonBlank(event.recordedAt(), "")));
        body.withChild(toolSection("Call", firstNonBlank(event.argumentsSummary(), event.callPreview(), event.argumentsJson(), "No arguments."), event.resultTruncated()));
        body.withChild(toolSection("Result", firstNonBlank(event.resultPreview(), event.resultSummary(), event.resultText(), "No result."), event.resultTruncated()));
        details.withChild(body);
        message.withChild(details);
        return message;
    }

    private static Component system(AuditRepository.AuditEvent event, String role, String text) {
        Div message = message("chat-message-assistant", role, event);
        message.withChild(new Div().withClass("chat-message-body")
            .withChild(new HtmlTag("p").withInnerText(text)));
        return message;
    }

    private static Div message(String className, String role, AuditRepository.AuditEvent event) {
        return new Div().withClass("chat-message " + className)
            .withChild(new Div().withClass("chat-message-role")
                .withInnerText(role + " | " + firstNonBlank(event.conversationId(), "conversation") + " #" + event.sequence()));
    }

    private static Component toolSection(String label, String text, boolean truncated) {
        Div section = new Div().withClass("chat-tool-section");
        String suffix = truncated ? " truncated" : "";
        section.withChild(new Div().withClass("chat-tool-label").withInnerText(label + suffix));
        section.withChild(new HtmlTag("pre").withInnerText(text));
        return section;
    }

    private static String thinkingHtml(AuditRepository.AuditEvent event) {
        String json = event.messageMetadataJson();
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            Map<String, Object> metadata = JSON.readValue(json, MAP_TYPE);
            Object html = firstPresent(metadata, "thinkingHtml", "magenta.thinkingHtml");
            if (html != null && StringUtils.hasText(html.toString())) {
                return html.toString();
            }
            Object text = firstPresent(metadata, "magenta.thinking", "thinking");
            return text == null || !StringUtils.hasText(text.toString())
                ? null
                : "<pre>" + escapeHtml(text.toString()) + "</pre>";
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Object firstPresent(Map<String, Object> metadata, String... keys) {
        for (String key : keys) {
            Object value = metadata.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String contextSummary(AuditRepository.AuditEvent event) {
        return "Context " + event.usedTokens() + "/" + event.maxTokens() + " tokens, "
            + event.storedMessageCount() + " stored messages";
    }

    private static String compactionSummary(AuditRepository.AuditEvent event) {
        return "Compaction " + firstNonBlank(event.compactionMethod(), "recorded")
            + ": " + firstNonBlank(event.compactionSummary(), "no summary");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return "";
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }
}
