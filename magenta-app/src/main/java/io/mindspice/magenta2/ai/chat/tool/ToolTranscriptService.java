package io.mindspice.magenta2.ai.chat.tool;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta2.ai.chat.model.ChatToolActivity;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ToolTranscriptService {
    public static final String FULL_PREFIX = "[[MAGENTA_TOOL_RESULT_FULL]]\n";
    public static final String SUMMARY_PREFIX = "[[MAGENTA_TOOL_RESULT_SUMMARY]]\n";

    private static final int RAW_OUTPUT_CHARACTER_LIMIT = 4_000;
    private static final int STORED_RAW_OUTPUT_CHARACTER_LIMIT = 40_000;
    private static final int STORED_ARGUMENT_CHARACTER_LIMIT = 40_000;
    private static final int DISPLAY_DETAIL_CHARACTER_LIMIT = 10_000;
    private static final int DISPLAY_PREVIEW_CHARACTER_LIMIT = 240;
    private static final int DISPLAY_SUMMARY_CHARACTER_LIMIT = 180;
    private static final int SHELL_COMMAND_SUMMARY_CHARACTER_LIMIT = 96;
    private static final int SHELL_DIRECTORY_SUMMARY_CHARACTER_LIMIT = 64;
    private static final int RETAIN_FULL_OUTPUT_USER_TURNS = 4;
    private static final int ARGUMENT_SUMMARY_CHARACTER_LIMIT = 500;

    private final ObjectMapper objectMapper;

    public ToolTranscriptService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public SystemMessage fullResult(String toolCallId, String toolName, String argumentsJson, String resultText) {
        return message(fullResultEntry(toolCallId, toolName, argumentsJson, resultText));
    }

    public ToolTranscriptEntry fullResultEntry(String toolCallId, String toolName, String argumentsJson, String resultText) {
        String status = resultLooksLikeError(resultText) ? "error" : "completed";
        ToolTranscriptEntry entry = new ToolTranscriptEntry(
            UUID.randomUUID().toString(),
            valueOrGenerated(toolCallId),
            toolName,
            summarizeArguments(argumentsJson),
            storedArgumentText(argumentsJson),
            summarizeResult(toolName, resultText),
            preview(argumentsJson),
            preview(resultText),
            storedResultText(resultText),
            status,
            Instant.now().toString(),
            resultText != null && resultText.length() > STORED_RAW_OUTPUT_CHARACTER_LIMIT,
            resultText != null && resultText.length() > RAW_OUTPUT_CHARACTER_LIMIT
        );
        return entry;
    }

    public ToolTranscriptEntry malformedArgumentsEntry(
        String toolCallId,
        String toolName,
        String argumentsJson,
        String parserMessage
    ) {
        String detail = "Tool was not executed because its arguments were not valid JSON.";
        if (StringUtils.hasText(parserMessage)) {
            detail += " Parser message: " + parserMessage.trim();
        }
        return new ToolTranscriptEntry(
            UUID.randomUUID().toString(),
            valueOrGenerated(toolCallId),
            toolName,
            summarizeArguments(argumentsJson),
            storedArgumentText(argumentsJson),
            "Malformed tool-call arguments; tool was not executed.",
            preview(argumentsJson),
            preview(detail),
            storedResultText(detail),
            "error",
            Instant.now().toString(),
            false,
            false
        );
    }

    public SystemMessage message(ToolTranscriptEntry entry) {
        return new SystemMessage(FULL_PREFIX + serialize(entry));
    }

    public List<Message> truncateExpiredLargeResults(List<Message> messages) {
        List<Message> rewritten = new ArrayList<>(messages.size());
        boolean changed = false;
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (isFullToolTranscript(message)) {
                ToolTranscriptEntry entry = parse(message);
                if (entry != null && entry.largeResult() && userTurnsAfter(messages, i) >= RETAIN_FULL_OUTPUT_USER_TURNS) {
                    rewritten.add(new SystemMessage(SUMMARY_PREFIX + serialize(entry.withoutRawOutput())));
                    changed = true;
                    continue;
                }
            }
            rewritten.add(message);
        }
        return changed ? rewritten : messages;
    }

    public boolean isToolTranscript(Message message) {
        return isFullToolTranscript(message) || isSummaryToolTranscript(message);
    }

    public boolean isFullToolTranscript(Message message) {
        return message instanceof SystemMessage
            && message.getText() != null
            && message.getText().startsWith(FULL_PREFIX);
    }

    public boolean isSummaryToolTranscript(Message message) {
        return message instanceof SystemMessage
            && message.getText() != null
            && message.getText().startsWith(SUMMARY_PREFIX);
    }

    public String renderForModel(Message message) {
        ToolTranscriptEntry entry = parse(message);
        if (entry == null) {
            return message.getText() == null ? "" : message.getText();
        }
        StringBuilder builder = new StringBuilder();
        builder.append("Tool activity available as context.\n")
            .append("Tool: ").append(entry.toolName()).append("\n")
            .append("Status: ").append(entry.status()).append("\n")
            .append("Time: ").append(entry.createdAt()).append("\n")
            .append("Call id: ").append(entry.toolCallId()).append("\n")
            .append("Arguments summary: ").append(entry.argumentsSummary()).append("\n")
            .append("Result summary: ").append(entry.resultSummary()).append("\n");
        if (entry.truncated()) {
            builder.append("Raw output: truncated; exact prior output is no longer in context.");
        } else {
            builder.append("Raw output:\n").append(entry.resultText());
        }
        return builder.toString();
    }

    public String renderForHistory(Message message) {
        ToolTranscriptEntry entry = parse(message);
        if (entry == null) {
            return message.getText() == null ? "" : message.getText();
        }
        return "Tool " + entry.toolName()
            + " " + entry.status()
            + " at " + entry.createdAt()
            + ". " + entry.resultSummary();
    }

    public ChatToolActivity activityFor(Message message) {
        ToolTranscriptEntry entry = parse(message);
        return entry == null ? null : activityFor(entry);
    }

    public ChatToolActivity activityFor(ToolTranscriptEntry entry) {
        if (entry == null) {
            return null;
        }
        String callDetail = displayDetail(entry.argumentsText());
        String resultDetail = displayDetail(entry.resultText());
        return new ChatToolActivity(
            entry.id(),
            entry.toolCallId(),
            entry.toolName(),
            entry.status(),
            entry.createdAt(),
            entry.resultSummary(),
            valueOrFallback(entry.argumentsSummary(), "No arguments."),
            callDetail,
            valueOrFallback(entry.resultPreview(), entry.resultSummary()),
            resultDetail,
            isDisplayTruncated(entry.argumentsText()),
            entry.truncated() || isDisplayTruncated(entry.resultText())
        );
    }

    private int userTurnsAfter(List<Message> messages, int index) {
        int count = 0;
        for (int i = index + 1; i < messages.size(); i++) {
            if (messages.get(i) instanceof UserMessage) {
                count++;
            }
        }
        return count;
    }

    private ToolTranscriptEntry parse(Message message) {
        String text = message.getText();
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String json;
        if (text.startsWith(FULL_PREFIX)) {
            json = text.substring(FULL_PREFIX.length());
        } else if (text.startsWith(SUMMARY_PREFIX)) {
            json = text.substring(SUMMARY_PREFIX.length());
        } else {
            return null;
        }
        try {
            return objectMapper.readValue(json, ToolTranscriptEntry.class);
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    private String serialize(ToolTranscriptEntry entry) {
        try {
            return objectMapper.writeValueAsString(entry);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize tool transcript entry", exception);
        }
    }

    private String summarizeArguments(String argumentsJson) {
        if (!StringUtils.hasText(argumentsJson)) {
            return "No arguments.";
        }
        return truncate(argumentsJson.replaceAll("\\s+", " ").trim(), ARGUMENT_SUMMARY_CHARACTER_LIMIT);
    }

    private String summarizeResult(String toolName, String resultText) {
        if (!StringUtils.hasText(resultText)) {
            return "Tool returned no text.";
        }
        JsonNode root = jsonNode(resultText);
        if (root == null) {
            return "Returned " + resultText.length() + " characters.";
        }
        String name = toolName == null ? "" : toolName;
        String summary = switch (name) {
            case "file_list" -> fileListSummary(root);
            case "file_read" -> fileReadSummary(root);
            case "file_search" -> fileSearchSummary(root);
            case "file_write" -> fileWriteSummary(root);
            case "file_append" -> fileAppendSummary(root);
            case "file_replace" -> fileReplaceSummary(root);
            case "shell_exec" -> shellSummary(root);
            case "web_search" -> webSearchSummary(root);
            case "web_fetch" -> webFetchSummary(root);
            case "plan_update", "plan_set_goal", "plan_set_task", "plan_put_item", "plan_delete_item",
                "ask_user_questions", "plan_ready_for_approval", "plan_report", "plan_complete",
                "task_set_goal", "task_set_task", "task_put_item", "task_delete_item",
                "task_ready_for_approval", "task_report", "task_complete" -> textSummary(resultText);
            default -> "Returned " + resultText.length() + " characters.";
        };
        return truncate(summary, DISPLAY_SUMMARY_CHARACTER_LIMIT);
    }

    private String fileListSummary(JsonNode root) {
        String path = text(root, "path", ".");
        int count = root.path("entries").isArray() ? root.path("entries").size() : 0;
        return "Listed " + count + " entries under " + path + truncationSuffix(root.path("truncated").asBoolean(false));
    }

    private String fileReadSummary(JsonNode root) {
        String path = text(root, "path", "file");
        int start = root.path("startLine").asInt(0);
        int end = root.path("endLine").asInt(0);
        int total = root.path("totalLines").asInt(0);
        String range = start > 0 && end >= start ? " lines " + start + "-" + end : "";
        return "Read " + path + range + " of " + total + " total lines.";
    }

    private String fileSearchSummary(JsonNode root) {
        int count = root.path("matches").isArray() ? root.path("matches").size() : 0;
        return "Found " + count + " file matches" + truncationSuffix(root.path("truncated").asBoolean(false));
    }

    private String fileWriteSummary(JsonNode root) {
        return "Wrote " + root.path("bytesWritten").asInt(0) + " bytes to " + text(root, "path", "file")
            + (root.path("created").asBoolean(false) ? " (created)." : ".");
    }

    private String fileAppendSummary(JsonNode root) {
        return "Appended " + root.path("bytesAppended").asInt(0) + " bytes to " + text(root, "path", "file")
            + (root.path("created").asBoolean(false) ? " (created)." : ".");
    }

    private String fileReplaceSummary(JsonNode root) {
        return "Replaced " + root.path("replacedLines").asInt(0) + " lines in " + text(root, "path", "file")
            + " with " + root.path("newLines").asInt(0) + " lines.";
    }

    private String shellSummary(JsonNode root) {
        String commandLine = truncate(text(root, "commandLine", text(root, "command", "command")), SHELL_COMMAND_SUMMARY_CHARACTER_LIMIT);
        String workingDirectory = truncate(text(root, "workingDirectory", "."), SHELL_DIRECTORY_SUMMARY_CHARACTER_LIMIT);
        String status = root.path("timedOut").asBoolean(false)
            ? "timed out"
            : "exit " + (root.path("exitCode").isMissingNode() || root.path("exitCode").isNull()
                ? "unknown"
                : root.path("exitCode").asText());
        return "Ran `" + commandLine + "` in " + workingDirectory + " (" + status + ")"
            + truncationSuffix(root.path("truncated").asBoolean(false));
    }

    private String webSearchSummary(JsonNode root) {
        int count = root.path("results").isArray() ? root.path("results").size() : 0;
        return "Searched web for `" + text(root, "query", "") + "` and returned " + count + " results"
            + truncationSuffix(root.path("truncated").asBoolean(false));
    }

    private String webFetchSummary(JsonNode root) {
        String title = text(root, "title", "");
        String titlePart = StringUtils.hasText(title) ? " (" + title + ")" : "";
        return "Fetched " + text(root, "url", "URL") + titlePart
            + truncationSuffix(root.path("truncated").asBoolean(false));
    }

    private String textSummary(String text) {
        return truncate(text.replaceAll("\\s+", " ").trim(), ARGUMENT_SUMMARY_CHARACTER_LIMIT);
    }

    private String truncationSuffix(boolean truncated) {
        return truncated ? " [truncated]." : ".";
    }

    private boolean resultLooksLikeError(String resultText) {
        if (!StringUtils.hasText(resultText)) {
            return false;
        }
        JsonNode root = jsonNode(resultText);
        if (root != null) {
            return root.path("timedOut").asBoolean(false);
        }
        return false;
    }

    private JsonNode jsonNode(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return objectMapper.readTree(text);
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    private String text(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText("");
        return StringUtils.hasText(value) ? value : fallback;
    }

    private String displayDetail(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return truncate(displayText(text), DISPLAY_DETAIL_CHARACTER_LIMIT);
    }

    private boolean isDisplayTruncated(String text) {
        return text != null && displayText(text).length() > DISPLAY_DETAIL_CHARACTER_LIMIT;
    }

    private String displayText(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        JsonNode root = jsonNode(text);
        if (root == null) {
            return text;
        }
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (JsonProcessingException ignored) {
            return text;
        }
    }

    private String preview(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return truncate(text.replaceAll("\\s+", " ").trim(), DISPLAY_PREVIEW_CHARACTER_LIMIT);
    }

    private String valueOrFallback(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private String storedArgumentText(String argumentsJson) {
        if (argumentsJson == null) {
            return "";
        }
        return truncate(argumentsJson, STORED_ARGUMENT_CHARACTER_LIMIT);
    }

    private String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        String marker = " ... [truncated]";
        return text.substring(0, Math.max(0, maxLength - marker.length())).trim() + marker;
    }

    private String storedResultText(String resultText) {
        if (resultText == null) {
            return "";
        }
        return truncate(resultText, STORED_RAW_OUTPUT_CHARACTER_LIMIT);
    }

    private String valueOrGenerated(String value) {
        return StringUtils.hasText(value) ? value : UUID.randomUUID().toString();
    }

    public record ToolTranscriptEntry(
        String id,
        String toolCallId,
        String toolName,
        String argumentsSummary,
        String argumentsText,
        String resultSummary,
        String callPreview,
        String resultPreview,
        String resultText,
        String status,
        String createdAt,
        boolean truncated,
        boolean largeResult
    ) {
        public ToolTranscriptEntry withoutRawOutput() {
            return new ToolTranscriptEntry(
                id,
                toolCallId,
                toolName,
                argumentsSummary,
                argumentsText,
                resultSummary,
                callPreview,
                resultPreview,
                "",
                status,
                createdAt,
                true,
                largeResult
            );
        }
    }
}
