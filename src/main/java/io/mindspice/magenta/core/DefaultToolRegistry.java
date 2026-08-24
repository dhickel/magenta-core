package io.mindspice.magenta.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta.core.spi.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        {"type":"object","properties":{"questions":{"type":"array","items":{"type":"string"},\
        "description":"Ordered questions to ask the user"},"question":{"type":"string",\
        "description":"One question to ask the user"}},"anyOf":[{"required":["questions"]},{"required":["question"]}]}""";

    private final Map<String, ToolCallback> publicTools = new ConcurrentHashMap<>();
    private final Map<String, ToolCallback> frameworkTools = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    /**
     * Creates a new registry and auto-registers the {@code ask_user}
     * framework-internal tool.
     */
    public DefaultToolRegistry() {
        this(new ObjectMapper().findAndRegisterModules());
    }

    public DefaultToolRegistry(ObjectMapper objectMapper) {
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper");
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
        if (publicTools.putIfAbsent(name, tool) != null) {
            throw new ToolException.AccessDenied(name,
                "Tool name '" + name + "' is already registered");
        }
    }

    @Override
    public void registerFrameworkTool(@NonNull ToolCallback tool) {
        frameworkTools.put(tool.getToolDefinition().name(), tool);
    }

    @Override
    public void registerToolBean(@NonNull Object bean) {
        List<ToolCallback> discovered = new ArrayList<>();
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

            discovered.add(callback);
        }
        Set<String> names = new HashSet<>();
        for (ToolCallback callback : discovered) {
            String name = callback.getToolDefinition().name();
            if (!names.add(name) || frameworkTools.containsKey(name) || publicTools.containsKey(name)) {
                throw new ToolException.AccessDenied(name,
                    "Tool name '" + name + "' is already registered or reserved");
            }
        }
        for (ToolCallback callback : discovered) {
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
        List<String> requested = context.request().toolNames();
        if (requested == null) {
            List<ToolCallback> merged = new ArrayList<>();
            merged.addAll(publicTools.values());
            merged.addAll(frameworkTools.values());
            return List.copyOf(merged);
        }

        List<ToolCallback> selected = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String name : requested) {
            if (!seen.add(name)) continue;
            ToolCallback callback = publicTools.get(name);
            if (callback == null) callback = frameworkTools.get(name);
            if (callback == null) {
                throw new ToolException.AccessDenied(name,
                    "Requested tool '" + name + "' is not registered");
            }
            selected.add(callback);
        }
        return List.copyOf(selected);
    }

    // ── Helpers ──

    /**
     * Builds a simple JSON Schema string from the method's parameter annotations.
     */
    private String buildInputSchema(Method method) {
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
            String paramName = resolveParameterName(method, param);
            ToolParam toolParam = param.getAnnotation(ToolParam.class);

            sb.append("\"").append(escapeJson(paramName)).append("\":{");
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
                sb.append("\"").append(escapeJson(required.get(i))).append("\"");
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
    private Object[] resolveArguments(Method method, String toolInput) throws Exception {
        Parameter[] parameters = method.getParameters();
        if (parameters.length == 0) {
            return new Object[0];
        }

        // Use the configured shared ObjectMapper so consumer modules and coercion rules apply.
        @SuppressWarnings("unchecked")
        Map<String, Object> inputMap = objectMapper.readValue(toolInput, Map.class);

        Object[] args = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            String paramName = resolveParameterName(method, parameters[i]);
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
                Type targetType = parameters[i].getParameterizedType();
                Object source = coerceStructuredString(raw, parameters[i].getType());
                args[i] = objectMapper.convertValue(source,
                    objectMapper.constructType(targetType));
            }
        }
        return args;
    }

    private static String resolveParameterName(Method method, Parameter parameter) {
        ToolParam toolParam = parameter.getAnnotation(ToolParam.class);
        if (toolParam != null && !toolParam.name().isBlank()) {
            return toolParam.name();
        }
        if (parameter.isNamePresent()) {
            return parameter.getName();
        }
        throw new IllegalArgumentException("Tool parameter on "
            + method.getDeclaringClass().getName() + "#" + method.getName()
            + " must declare @ToolParam(name = ...) when compiled without -parameters");
    }

    private Object coerceStructuredString(Object raw, Class<?> targetType) {
        if (!(raw instanceof String text) || targetType == String.class) return raw;
        String trimmed = text.trim();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) return raw;
        try {
            return objectMapper.readTree(trimmed);
        } catch (Exception ignored) {
            return raw;
        }
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
        if (type.isArray() || Collection.class.isAssignableFrom(type)) {
            return "array";
        }
        if (Map.class.isAssignableFrom(type) || type.isRecord()
            || (!type.isPrimitive() && !type.getName().startsWith("java."))) {
            return "object";
        }
        return "string";
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
