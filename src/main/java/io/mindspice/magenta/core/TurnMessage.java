package io.mindspice.magenta.core;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Map;
import java.util.Objects;

/**
 * Framework-native conversation message with a closed set of provider roles.
 *
 * <p>The role is represented by the Java variant rather than a caller-supplied
 * string. Every message carries immutable metadata, while tool identity exists
 * only on {@link Tool}.</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = TurnMessage.System.class, name = "system"),
    @JsonSubTypes.Type(value = TurnMessage.User.class, name = "user"),
    @JsonSubTypes.Type(value = TurnMessage.Assistant.class, name = "assistant"),
    @JsonSubTypes.Type(value = TurnMessage.Tool.class, name = "tool")
})
public sealed interface TurnMessage
    permits TurnMessage.System, TurnMessage.User, TurnMessage.Assistant, TurnMessage.Tool {

    /** Message content. Empty text is valid for provider/tool-call transcripts. */
    @NonNull
    String text();

    /** Immutable extension metadata shared by every message variant. */
    @NonNull
    Map<String, String> metadata();

    /** A system instruction or framework-generated system context message. */
    record System(
        @NonNull String text,
        @NonNull Map<String, String> metadata
    ) implements TurnMessage {
        public System {
            text = requiredText(text);
            metadata = immutableMetadata(metadata);
        }
    }

    /** A caller-authored user message. */
    record User(
        @NonNull String text,
        @NonNull Map<String, String> metadata
    ) implements TurnMessage {
        public User {
            text = requiredText(text);
            metadata = immutableMetadata(metadata);
        }
    }

    /** A model-authored assistant message, including tool-call transcripts. */
    record Assistant(
        @NonNull String text,
        @NonNull Map<String, String> metadata
    ) implements TurnMessage {
        public Assistant {
            text = requiredText(text);
            metadata = immutableMetadata(metadata);
        }
    }

    /** A tool result message with required provider correlation identity. */
    record Tool(
        @NonNull String toolName,
        @NonNull String toolCallId,
        @NonNull String text,
        @NonNull Map<String, String> metadata
    ) implements TurnMessage {
        public Tool {
            toolName = ResponseValidation.nonBlank(toolName, "toolName");
            toolCallId = ResponseValidation.nonBlank(toolCallId, "toolCallId");
            text = requiredText(text);
            metadata = immutableMetadata(metadata);
        }
    }

    private static String requiredText(String text) {
        return Objects.requireNonNull(text, "text must not be null");
    }

    private static Map<String, String> immutableMetadata(Map<String, String> metadata) {
        return Map.copyOf(Objects.requireNonNull(metadata, "metadata must not be null"));
    }
}
