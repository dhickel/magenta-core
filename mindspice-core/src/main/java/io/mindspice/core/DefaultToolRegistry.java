package io.mindspice.core;

import io.mindspice.core.spi.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link ToolRegistry} implementation with two-stage registration:
 * public tools visible to callers via {@link #definitions()} and
 * framework-internal tools hidden from callers.
 *
 * <h3>ask_user framework tool</h3>
 * The {@code ask_user} tool is auto-registered as a framework-internal tool
 * in the constructor. Its {@link ToolCallback#call(String)} returns a simple
 * JSON acknowledgement. The actual suspension logic lives in the engine,
 * which intercepts the tool call by name before invoking the callback.
 * This design keeps the registry free of {@code InteractionHandler} coupling;
 * the engine owns the full ask_user lifecycle.
 */
public final class DefaultToolRegistry implements ToolRegistry {

    private static final Logger logger = LoggerFactory.getLogger(DefaultToolRegistry.class);

    private static final String ASK_USER_NAME = "ask_user";
    private static final String ASK_USER_DESC = "Ask the user one or more questions. "
        + "The turn suspends until the user answers.";
    private static final String ASK_USER_INPUT_SCHEMA = """
        {"type":"object","properties":{"question":{"type":"string",\
        "description":"The question to ask the user"}},"required":["question"]}""";

    private final Map<String, ToolCallback> publicTools = new ConcurrentHashMap<>();
    private final Map<String, ToolCallback> frameworkTools = new ConcurrentHashMap<>();

    /**
     * Creates a new registry and auto-registers the {@code ask_user}
     * framework-internal tool.
     */
    public DefaultToolRegistry() {
        registerFrameworkTool(new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return new ToolDefinition(ASK_USER_NAME, ASK_USER_DESC, ASK_USER_INPUT_SCHEMA);
            }

            @Override
            public String call(String toolInput) {
                // The engine intercepts ask_user by name before calling this callback.
                // This is a safety net; the actual suspension is engine-owned.
                logger.debug("ask_user callback invoked directly (engine bypass): input={}", toolInput);
                return "{\"acknowledged\":true,\"question\":\"" + escapeJson(toolInput) + "\"}";
            }

            private String escapeJson(String s) {
                return s.replace("\\", "\\\\").replace("\"", "\\\"");
            }
        });
    }

    // ── ToolRegistry implementation ──

    @Override
    public void register(@NonNull ToolCallback tool) {
        String name = tool.getToolDefinition().name();
        if (frameworkTools.containsKey(name)) {
            throw new ToolException.AccessDenied(name,
                "Tool name '" + name + "' conflicts with a framework-internal tool");
        }
        publicTools.put(name, tool);
    }

    @Override
    public void registerFrameworkTool(@NonNull ToolCallback tool) {
        frameworkTools.put(tool.getToolDefinition().name(), tool);
    }

    @Override
    public void registerToolBean(@NonNull Object bean) {
        for (Method method : bean.getClass().getMethods()) {
            Tool toolAnn = method.getAnnotation(Tool.class);
            if (toolAnn == null) {
                continue;
            }

            String name = toolAnn.name().isEmpty() ? method.getName() : toolAnn.name();
            String description = toolAnn.description();
            String inputSchema = buildInputSchema(method);

            ToolDefinition definition = new ToolDefinition(name, description, inputSchema);

            ToolCallback callback = new ToolCallback() {
                @Override
                public ToolDefinition getToolDefinition() {
                    return definition;
                }

                @Override
                public String call(String toolInput) {
                    try {
                        Object[] args = resolveArguments(method, toolInput);
                        Object result = method.invoke(bean, args);
                        return result == null ? "" : result.toString();
                    } catch (InvocationTargetException e) {
                        Throwable cause = e.getCause() != null ? e.getCause() : e;
                        throw new ToolException.ExecutionFailed(name, "",
                            "Tool execution failed: " + cause.getMessage(), cause);
                    } catch (Exception e) {
                        throw new ToolException.ExecutionFailed(name, "",
                            "Tool execution failed: " + e.getMessage(), e);
                    }
                }
            };

            register(callback);
        }
    }

    @Override
    public void unregister(@NonNull String toolName) {
        publicTools.remove(toolName);
    }

    @Override
    @NonNull
    public List<ToolDefinition> definitions() {
        List<ToolDefinition> result = new ArrayList<>();
        for (ToolCallback callback : publicTools.values()) {
            result.add(callback.getToolDefinition());
        }
        return List.copyOf(result);
    }

    @Override
    @NonNull
    public List<ToolCallback> resolveForTurn(@NonNull TurnContext context) {
        List<ToolCallback> merged = new ArrayList<>();
        merged.addAll(publicTools.values());
        merged.addAll(frameworkTools.values());
        return List.copyOf(merged);
    }

    // ── Helpers ──

    /**
     * Builds a simple JSON Schema string from the method's parameter annotations.
     */
    private static String buildInputSchema(Method method) {
        Parameter[] parameters = method.getParameters();
        if (parameters.length == 0) {
            return "{\"type\":\"object\",\"properties\":{}}";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"object\",\"properties\":{");
        List<String> required = new ArrayList<>();

        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            Parameter param = parameters[i];
            String paramName = param.getName();
            ToolParam toolParam = param.getAnnotation(ToolParam.class);

            sb.append("\"").append(paramName).append("\":{");
            sb.append("\"type\":\"").append(javaTypeToJsonType(param.getType())).append("\"");
            if (toolParam != null && !toolParam.description().isEmpty()) {
                sb.append(",\"description\":\"").append(escapeJson(toolParam.description())).append("\"");
            }
            sb.append("}");

            if (toolParam != null && toolParam.required()) {
                required.add(paramName);
            }
        }

        sb.append("}");
        if (!required.isEmpty()) {
            sb.append(",\"required\":[");
            for (int i = 0; i < required.size(); i++) {
                if (i > 0) {
                    sb.append(",");
                }
                sb.append("\"").append(required.get(i)).append("\"");
            }
            sb.append("]");
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Resolves method arguments from a JSON input string. Uses a minimal
     * Jackson-based approach: expects a flat JSON object whose keys match
     * parameter names.
     */
    private static Object[] resolveArguments(Method method, String toolInput) throws Exception {
        Parameter[] parameters = method.getParameters();
        if (parameters.length == 0) {
            return new Object[0];
        }

        // Use Jackson for JSON parsing
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        @SuppressWarnings("unchecked")
        Map<String, Object> inputMap = mapper.readValue(toolInput, Map.class);

        Object[] args = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            String paramName = parameters[i].getName();
            Object raw = inputMap.get(paramName);

            if (raw == null) {
                ToolParam toolParam = parameters[i].getAnnotation(ToolParam.class);
                if (toolParam != null && toolParam.required()) {
                    throw new ToolException.ArgumentCoercionFailed(
                        method.getDeclaringClass().getSimpleName(),
                        "",
                        "Missing required parameter '" + paramName + "'"
                    );
                }
                args[i] = null;
            } else {
                args[i] = mapper.convertValue(raw, parameters[i].getType());
            }
        }
        return args;
    }

    private static String javaTypeToJsonType(Class<?> type) {
        if (type == String.class || type == Character.class || type == char.class) {
            return "string";
        }
        if (type == Integer.class || type == int.class
            || type == Long.class || type == long.class
            || type == Short.class || type == short.class
            || type == Byte.class || type == byte.class) {
            return "integer";
        }
        if (type == Double.class || type == double.class
            || type == Float.class || type == float.class) {
            return "number";
        }
        if (type == Boolean.class || type == boolean.class) {
            return "boolean";
        }
        return "string"; // fallback
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
