package io.mindspice.magenta.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelConfigBuilderTest {

    @Test
    void buildsEveryVariantWithSharedComponentsAndOptions() {
        assertEquals(
            new ModelConfig.Ollama(
                "local", "llama3.2", "http://localhost:11434", null, 32_000, 0),
            ModelConfig.ollama()
                .alias("local")
                .remoteModelName("llama3.2")
                .remoteEndpoint("http://localhost:11434")
                .contextLength(32_000)
                .thinkLevel(0)
                .build());
        assertEquals(
            new ModelConfig.OpenAiCompatible(
                "hosted", "example-model", "https://models.example/v1",
                "open-secret", 128_000, 2, "max"),
            ModelConfig.openAiCompatible()
                .alias("hosted")
                .remoteModelName("example-model")
                .remoteEndpoint("https://models.example/v1")
                .apiKey("open-secret")
                .contextLength(128_000)
                .thinkLevel(2)
                .reasoningEffort("max")
                .build());
        assertEquals(
            new ModelConfig.DeepSeek(
                "deep", "deepseek-chat", "https://api.deepseek.com", "deep-secret", null, 0),
            ModelConfig.deepSeek()
                .alias("deep")
                .remoteModelName("deepseek-chat")
                .remoteEndpoint("https://api.deepseek.com")
                .apiKey("deep-secret")
                .thinkLevel(0)
                .build());
        assertEquals(
            new ModelConfig.Responses(
                "resp", "deepseek-v4-flash", "https://api.deepseek.com",
                "resp-secret", 1_048_576, 4, "high"),
            ModelConfig.responses()
                .alias("resp")
                .remoteModelName("deepseek-v4-flash")
                .remoteEndpoint("https://api.deepseek.com")
                .apiKey("resp-secret")
                .contextLength(1_048_576)
                .thinkLevel(4)
                .reasoningEffort("high")
                .build());
    }

    @Test
    void optionalComponentsDefaultToNull() {
        ModelConfig.Ollama ollama = (ModelConfig.Ollama) ModelConfig.ollama()
            .alias("local")
            .remoteModelName("llama3.2")
            .remoteEndpoint("http://localhost:11434")
            .build();
        assertNull(ollama.apiKey());
        assertNull(ollama.contextLength());
        assertNull(ollama.thinkLevel());

        ModelConfig.OpenAiCompatible compatible =
            (ModelConfig.OpenAiCompatible) ModelConfig.openAiCompatible()
                .alias("hosted")
                .remoteModelName("example-model")
                .remoteEndpoint("https://models.example/v1")
                .build();
        assertNull(compatible.apiKey());
        assertNull(compatible.contextLength());
        assertNull(compatible.thinkLevel());
        assertNull(compatible.reasoningEffort());
    }

    @Test
    void settersReturnTheSameBuilder() {
        ModelConfig.Builder builder = ModelConfig.ollama();
        assertSame(builder, builder.alias("local"));
        assertSame(builder, builder.remoteModelName("llama3.2"));
        assertSame(builder, builder.remoteEndpoint("http://localhost:11434"));
        assertSame(builder, builder.apiKey(null));
        assertSame(builder, builder.contextLength(4096));
        assertSame(builder, builder.thinkLevel(1));

        ModelConfig.Builder compatible = ModelConfig.openAiCompatible();
        assertSame(compatible, compatible.reasoningEffort("low"));
    }

    @Test
    void buildIsRepeatable() {
        ModelConfig.Builder builder = ModelConfig.responses()
            .alias("resp")
            .remoteModelName("deepseek-v4-flash")
            .remoteEndpoint("https://api.deepseek.com")
            .apiKey("secret");
        assertEquals(builder.build(), builder.build());
    }

    @Test
    void delegatesValidationToVariantConstructors() {
        assertThrows(NullPointerException.class,
            () -> ModelConfig.ollama()
                .remoteModelName("llama3.2")
                .remoteEndpoint("http://localhost:11434")
                .build());
        assertThrows(IllegalArgumentException.class,
            () -> ModelConfig.ollama()
                .alias("local")
                .remoteModelName("llama3.2")
                .remoteEndpoint("not-a-url")
                .build());
        assertThrows(IllegalArgumentException.class,
            () -> ModelConfig.ollama()
                .alias("local")
                .remoteModelName("llama3.2")
                .remoteEndpoint("http://localhost:11434")
                .contextLength(0)
                .build());
        assertThrows(IllegalArgumentException.class,
            () -> ModelConfig.ollama()
                .alias("local")
                .remoteModelName("llama3.2")
                .remoteEndpoint("http://localhost:11434")
                .thinkLevel(5)
                .build());
        assertThrows(IllegalArgumentException.class,
            () -> ModelConfig.ollama()
                .alias("local")
                .remoteModelName("llama3.2")
                .remoteEndpoint("http://localhost:11434")
                .apiKey("ollama-secret")
                .build());
        assertThrows(IllegalArgumentException.class,
            () -> ModelConfig.deepSeek()
                .alias("deep")
                .remoteModelName("deepseek-chat")
                .remoteEndpoint("https://api.deepseek.com")
                .build());
        assertThrows(IllegalArgumentException.class,
            () -> ModelConfig.deepSeek()
                .alias("deep")
                .remoteModelName("deepseek-chat")
                .remoteEndpoint("https://api.deepseek.com")
                .apiKey("deep-secret")
                .thinkLevel(2)
                .build());
        assertThrows(IllegalArgumentException.class,
            () -> ModelConfig.responses()
                .alias("resp")
                .remoteModelName("deepseek-v4-flash")
                .remoteEndpoint("https://api.deepseek.com")
                .build());
        assertThrows(IllegalArgumentException.class,
            () -> ModelConfig.openAiCompatible()
                .alias("hosted")
                .remoteModelName("example-model")
                .remoteEndpoint("https://models.example/v1")
                .reasoningEffort("extreme")
                .build());
    }

    @Test
    void reasoningEffortIsRejectedForVariantsWithoutTheComponent() {
        assertThrows(IllegalArgumentException.class,
            () -> ModelConfig.ollama().reasoningEffort("max"));
        assertThrows(IllegalArgumentException.class,
            () -> ModelConfig.deepSeek().reasoningEffort("max"));
    }

    @Test
    void reasoningEffortNormalizesOnSupportedVariants() {
        ModelConfig.OpenAiCompatible compatible =
            (ModelConfig.OpenAiCompatible) ModelConfig.openAiCompatible()
                .alias("hosted")
                .remoteModelName("example-model")
                .remoteEndpoint("https://models.example/v1")
                .reasoningEffort(" MAX ")
                .build();
        assertEquals("max", compatible.reasoningEffort());

        ModelConfig.Responses responses = (ModelConfig.Responses) ModelConfig.responses()
            .alias("resp")
            .remoteModelName("deepseek-v4-flash")
            .remoteEndpoint("https://api.deepseek.com")
            .apiKey("secret")
            .reasoningEffort("XHigh")
            .build();
        assertEquals("xhigh", responses.reasoningEffort());
    }
}
