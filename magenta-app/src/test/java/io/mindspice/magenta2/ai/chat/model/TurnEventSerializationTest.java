package io.mindspice.magenta2.ai.chat.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TurnEventSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void startEventSerializesFieldsReadByBrowser() throws Exception {
        TurnEvent.Start event = new TurnEvent.Start(
            "conversation-1", "model-a", "turn-1", "token-1"
        );

        JsonNode json = mapper.readTree(mapper.writeValueAsString(event));

        assertThat(json.get("conversationId").asText()).isEqualTo("conversation-1");
        assertThat(json.get("model").asText()).isEqualTo("model-a");
        assertThat(json.get("turnId").asText()).isEqualTo("turn-1");
        assertThat(json.get("interruptToken").asText()).isEqualTo("token-1");
    }

    @Test
    void chunkEventSerializesFieldsReadByBrowser() throws Exception {
        ContextUsage usage = new ContextUsage(100, 4096, 3072, 2.44);
        TurnEvent.Chunk event = new TurnEvent.Chunk(
            "Hello", usage
        );

        JsonNode json = mapper.readTree(mapper.writeValueAsString(event));

        assertThat(json.get("text").asText()).isEqualTo("Hello");
        assertThat(json.has("contextUsage")).isTrue();
        assertThat(json.get("contextUsage").get("usedTokens").asInt()).isEqualTo(100);
    }

    @Test
    void toolEventSerializesFieldsReadByBrowser() throws Exception {
        ChatToolActivity toolActivity = new ChatToolActivity(
            "tool-1", "call-1", "search", "completed", "2025-01-01T00:00:00Z",
            "Found results", "search(\"query\")", "search(\"query\") full",
            "3 results", "result detail text", false, false
        );
        ContextUsage usage = new ContextUsage(200, 4096, 3072, 4.88);
        TurnEvent.Tool event = new TurnEvent.Tool(toolActivity, usage);

        JsonNode json = mapper.readTree(mapper.writeValueAsString(event));

        assertThat(json.has("toolActivity")).isTrue();
        assertThat(json.get("toolActivity").get("toolName").asText()).isEqualTo("search");
        assertThat(json.has("contextUsage")).isTrue();
    }

    @Test
    void systemNoticeEventSerializesFieldsReadByBrowser() throws Exception {
        ContextUsage usage = new ContextUsage(50, 4096, 3072, 1.22);
        TurnEvent.SystemNotice event = new TurnEvent.SystemNotice(
            "Notice", usage
        );

        JsonNode json = mapper.readTree(mapper.writeValueAsString(event));

        assertThat(json.get("text").asText()).isEqualTo("Notice");
        assertThat(json.has("contextUsage")).isTrue();
    }

    @Test
    void interruptEventSerializesFieldsReadByBrowser() throws Exception {
        ContextUsage usage = new ContextUsage(300, 4096, 3072, 7.32);
        TurnEvent.Interrupt event = new TurnEvent.Interrupt("Interrupted", usage);

        JsonNode json = mapper.readTree(mapper.writeValueAsString(event));

        assertThat(json.get("text").asText()).isEqualTo("Interrupted");
        assertThat(json.has("contextUsage")).isTrue();
    }

    @Test
    void contextEventSerializesFieldsReadByBrowser() throws Exception {
        ContextUsage usage = new ContextUsage(150, 4096, 3072, 3.66);
        TurnEvent.Context event = new TurnEvent.Context(usage);

        JsonNode json = mapper.readTree(mapper.writeValueAsString(event));

        assertThat(json.get("contextUsage").get("usedTokens").asInt()).isEqualTo(150);
        assertThat(json.get("contextUsage").get("maxTokens").asInt()).isEqualTo(4096);
    }

    @Test
    void doneEventSerializesFieldsReadByBrowser() throws Exception {
        ContextUsage usage = new ContextUsage(400, 4096, 3072, 9.77);
        TurnEvent.Done event = new TurnEvent.Done(
            "conversation-1", "model-a", "Done text", usage
        );

        JsonNode json = mapper.readTree(mapper.writeValueAsString(event));

        assertThat(json.get("conversationId").asText()).isEqualTo("conversation-1");
        assertThat(json.get("model").asText()).isEqualTo("model-a");
        assertThat(json.get("text").asText()).isEqualTo("Done text");
        assertThat(json.has("contextUsage")).isTrue();
    }

    @Test
    void errorEventSerializesFieldsReadByBrowser() throws Exception {
        TurnEvent.Error event = new TurnEvent.Error("Something went wrong");

        JsonNode json = mapper.readTree(mapper.writeValueAsString(event));

        assertThat(json.get("message").asText()).isEqualTo("Something went wrong");
    }
}
