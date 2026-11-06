package io.mindspice.magenta.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MagentaToolRegistryTest {
    @Test
    void registersMagentaOwnedAnnotatedBeanWithoutSpringAnnotations() throws Exception {
        assertFalse(ExampleTool.class.getDeclaredMethod("echo", String.class)
            .getParameters()[0].isNamePresent());

        DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.registerToolBean(new ExampleTool());

        assertEquals(List.of("echo"), registry.definitions().stream()
            .map(ToolDefinition::name).sorted().toList());
        assertEquals("{\"type\":\"object\",\"properties\":{\"message\":{\"type\":\"string\","
                + "\"description\":\"Message\"}},\"required\":[\"message\"]}",
            registry.definitions().getFirst().inputSchema());
        assertEquals("hello", assertInstanceOf(ToolResult.Success.class,
            registry.resolveForTurn(
                new TurnContext(TurnRequest.of("session", "message"), "turn", "model"))
                .stream().filter(callback -> "echo".equals(callback.getToolDefinition().name()))
                .findFirst().orElseThrow().call("{\"message\":\"hello\"}")).output());
    }

    @Test
    void rejectsSyntheticParameterNamesAtRegistration() {
        DefaultToolRegistry registry = new DefaultToolRegistry();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> registry.registerToolBean(new ToolWithoutParameterName()));

        assertEquals("Tool parameter on " + ToolWithoutParameterName.class.getName()
                + "#echo must declare @ToolParam(name = ...) when compiled without -parameters",
            failure.getMessage());
    }

    @Test
    void coercesStructuredStringArgumentsWithFullGenericTypes() throws Exception {
        DefaultToolRegistry registry = new DefaultToolRegistry(
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
        registry.registerToolBean(new StructuredTool());
        ToolCallback callback = registry.resolveForTurn(new TurnContext(
            new TurnRequest("session", "message", "model", List.of("join"),
                java.util.Map.of()), "turn", "model")).getFirst();
        assertEquals("ab", assertInstanceOf(ToolResult.Success.class,
            callback.call("{\"values\":\"[\\\"a\\\",\\\"b\\\"]\"}")).output());
        assertEquals(1, new com.fasterxml.jackson.databind.ObjectMapper()
            .findAndRegisterModules().readValue("1", Object.class));
    }

    @Test
    void annotatedToolResultsAndFailuresRemainTyped() {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.registerToolBean(new TypedTool());
        registry.registerToolBean(new ExampleTool());

        List<ToolCallback> callbacks = registry.resolveForTurn(
            new TurnContext(TurnRequest.of("session", "message"), "turn", "model"));
        ToolResult.Failure typed = assertInstanceOf(ToolResult.Failure.class,
            callbacks.stream().filter(callback -> "typed".equals(
                callback.getToolDefinition().name())).findFirst().orElseThrow().call("{}"));
        assertEquals(ToolFailureCategory.ACCESS_DENIED, typed.category());

        ToolResult.Failure thrown = assertInstanceOf(ToolResult.Failure.class,
            callbacks.stream().filter(callback -> "throws".equals(
                callback.getToolDefinition().name())).findFirst().orElseThrow().call("{}"));
        assertEquals(ToolFailureCategory.EXECUTION_FAILED, thrown.category());
        assertFalse(thrown.message().contains("secret exception details"));

        ToolResult.Failure invalid = assertInstanceOf(ToolResult.Failure.class,
            callbacks.stream().filter(callback -> "echo".equals(
                callback.getToolDefinition().name())).findFirst().orElseThrow().call("{}"));
        assertEquals(ToolFailureCategory.INVALID_ARGUMENTS, invalid.category());
    }

    @Test
    void rejectsDuplicateAndHonorsExplicitToolSelection() {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.registerToolBean(new ExampleTool());
        assertThrows(ToolException.AccessDenied.class,
            () -> registry.registerToolBean(new ExampleTool()));

        TurnContext selected = new TurnContext(
            new TurnRequest("session", "message", "model", List.of("echo"), java.util.Map.of()),
            "turn", "model");
        assertEquals(List.of("echo"), registry.resolveForTurn(selected).stream()
            .map(callback -> callback.getToolDefinition().name()).toList());
        TurnContext noTools = new TurnContext(
            new TurnRequest("session", "message", "model", List.of(), java.util.Map.of()),
            "turn-empty", "model");
        assertEquals(List.of(), registry.resolveForTurn(noTools));
        assertThrows(ToolException.AccessDenied.class, () -> registry.resolveForTurn(
            new TurnContext(new TurnRequest("session", "message", "model", List.of("missing"),
                java.util.Map.of()), "turn-2", "model")));
    }

    static final class TypedTool {
        @Tool(name = "typed")
        public ToolResult typed() {
            return new ToolResult.Failure("not allowed", ToolFailureCategory.ACCESS_DENIED);
        }

        @Tool(name = "throws")
        public String throwsTool() {
            throw new IllegalStateException("secret exception details");
        }
    }

    static final class StructuredTool {
        @Tool(name = "join", description = "Join values")
        public String join(@ToolParam(name = "values") List<String> values) {
            return String.join("", values);
        }
    }

    static final class ExampleTool {
        @Tool(name = "echo", description = "Echo")
        public String echo(@ToolParam(name = "message", description = "Message") String message) {
            return message;
        }
    }

    static final class ToolWithoutParameterName {
        @Tool(name = "echo")
        public String echo(String message) {
            return message;
        }
    }
}
