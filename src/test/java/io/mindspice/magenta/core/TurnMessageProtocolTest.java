package io.mindspice.magenta.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnMessageProtocolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rootIsSealedToTheFourMessageRoles() {
        assertEquals(Set.of(
            TurnMessage.System.class,
            TurnMessage.User.class,
            TurnMessage.Assistant.class,
            TurnMessage.Tool.class),
            Set.of(TurnMessage.class.getPermittedSubclasses()));
        assertThrows(NoSuchMethodException.class,
            () -> TurnMessage.User.class.getMethod("toolName"));
        assertThrows(NoSuchMethodException.class,
            () -> TurnMessage.Assistant.class.getMethod("toolCallId"));
    }

    @Test
    void constructorsAllowEmptyTextAndValidateOnlyToolIdentity() {
        Map<String, String> sourceMetadata = new HashMap<>();
        sourceMetadata.put("source", "caller");
        TurnMessage.User user = new TurnMessage.User("", sourceMetadata);
        sourceMetadata.put("later", "not copied");

        assertEquals("", user.text());
        assertEquals(Map.of("source", "caller"), user.metadata());
        assertThrows(UnsupportedOperationException.class,
            () -> user.metadata().put("blocked", "value"));

        TurnMessage.Tool emptyResult = new TurnMessage.Tool(
            "lookup", "call-1", "", Map.of());
        assertEquals("", emptyResult.text());
        assertThrows(NullPointerException.class,
            () -> new TurnMessage.System(null, Map.of()));
        assertThrows(NullPointerException.class,
            () -> new TurnMessage.User("text", null));
        assertThrows(IllegalArgumentException.class,
            () -> new TurnMessage.Tool(" ", "call-1", "result", Map.of()));
        assertThrows(IllegalArgumentException.class,
            () -> new TurnMessage.Tool("lookup", "\t", "result", Map.of()));
        assertThrows(NullPointerException.class,
            () -> new TurnMessage.Tool("lookup", "call-1", null, Map.of()));
    }

    @Test
    void everyVariantUsesStableJsonTagAndRoundTrips() throws Exception {
        List<TurnMessage> messages = List.of(
            new TurnMessage.System("system", Map.of("kind", "policy")),
            new TurnMessage.User("user", Map.of()),
            new TurnMessage.Assistant("assistant", Map.of("trace", "1")),
            new TurnMessage.Tool("lookup", "call-1", "", Map.of("kind", "result")));
        List<String> tags = List.of("system", "user", "assistant", "tool");

        for (int index = 0; index < messages.size(); index++) {
            String json = objectMapper.writeValueAsString(messages.get(index));
            assertTrue(json.contains("\"type\":\"" + tags.get(index) + "\""), json);
            TurnMessage restored = objectMapper.readValue(json, TurnMessage.class);
            assertEquals(messages.get(index), restored);
        }

        assertThrows(Exception.class, () -> objectMapper.readValue(
            "{\"type\":\"unknown\",\"text\":\"x\",\"metadata\":{}}",
            TurnMessage.class));
    }

    @Test
    void providerConversionIsExhaustiveAndRetainsMetadata() {
        Message system = DefaultTurnEngine.turnMessageToSpring(
            new TurnMessage.System("system", Map.of("kind", "policy")));
        Message user = DefaultTurnEngine.turnMessageToSpring(
            new TurnMessage.User("user", Map.of("kind", "input")));
        Message assistant = DefaultTurnEngine.turnMessageToSpring(
            new TurnMessage.Assistant("assistant", Map.of("kind", "output")));
        Message tool = DefaultTurnEngine.turnMessageToSpring(
            new TurnMessage.Tool("lookup", "call-1", "result", Map.of("kind", "tool")));

        assertInstanceOf(SystemMessage.class, system);
        assertInstanceOf(UserMessage.class, user);
        assertInstanceOf(AssistantMessage.class, assistant);
        ToolResponseMessage toolResponse = assertInstanceOf(ToolResponseMessage.class, tool);
        assertEquals("call-1", toolResponse.getResponses().getFirst().id());
        assertEquals("lookup", toolResponse.getResponses().getFirst().name());
        assertEquals("tool", toolResponse.getMetadata().get("kind"));
        assertFalse(system.getMetadata().isEmpty());
    }

    @Test
    void compactionUsesTheClosedRoleTags() {
        CompactionResult result = new DefaultCompactionStrategy().compact(new CompactionContext(
            "session",
            List.of(
                new TurnMessage.System("s", Map.of()),
                new TurnMessage.User("u", Map.of()),
                new TurnMessage.Assistant("a", Map.of()),
                new TurnMessage.Tool("lookup", "call-1", "t", Map.of())),
            new ContextUsage(4, 100, 200)));

        assertTrue(result.summary().contains("system: s"));
        assertTrue(result.summary().contains("user: u"));
        assertTrue(result.summary().contains("assistant: a"));
        assertTrue(result.summary().contains("tool: t"));
    }
}
