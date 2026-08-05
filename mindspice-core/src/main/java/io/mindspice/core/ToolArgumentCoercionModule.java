package io.mindspice.core;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Jackson module that installs a custom deserializer which attempts to coerce
 * JSON string values into target parameter types when the LLM returns
 * arguments that are string-wrapped objects or arrays.
 *
 * <p>Registered automatically via {@code META-INF/services/com.fasterxml.jackson.databind.Module}
 * so Jackson auto-discovers it on the classpath.</p>
 *
 * <p>When coercion is not possible the deserializer logs a warning and
 * returns the raw string, letting the caller handle it gracefully.</p>
 */
public final class ToolArgumentCoercionModule extends SimpleModule {

    private static final Logger LOG =
        LoggerFactory.getLogger(ToolArgumentCoercionModule.class);

    public ToolArgumentCoercionModule() {
        super("ToolArgumentCoercionModule");
        // Register a general-purpose deserializer that attempts string coercion
        addDeserializer(Object.class, new CoercionDeserializer());
    }

    /**
     * Deserializer that intercepts {@code VALUE_STRING} tokens and attempts
     * to parse them as JSON before falling back to the raw string value.
     */
    private static final class CoercionDeserializer extends JsonDeserializer<Object>
            implements ContextualDeserializer {

        @Override
        public Object deserialize(JsonParser p, DeserializationContext ctxt)
                throws IOException {

            // If the current token is a string that looks like JSON, try to
            // parse it as a tree and convert to the target type.
            if (p.currentToken() == JsonToken.VALUE_STRING) {
                String raw = p.getText();
                if (raw != null && (raw.trim().startsWith("{") || raw.trim().startsWith("["))) {
                    try {
                        // Attempt to re-parse the string as structured JSON
                        JsonParser subParser = p.getCodec()
                            .getFactory()
                            .createParser(raw);
                        subParser.nextToken();
                        // Return the parsed tree — Jackson will convert it
                        // to the target type via its normal conversion.
                        return subParser.readValueAsTree()
                            .traverse(p.getCodec())
                            .readValueAs(Object.class);
                    } catch (Exception e) {
                        LOG.warn("ToolArgumentCoercionModule: unable to coerce "
                            + "JSON-like string; passing through raw value. "
                            + "String preview: {}",
                            raw.length() > 120
                                ? raw.substring(0, 120) + "..."
                                : raw);
                    }
                }
                return raw;
            }

            // For non-string tokens, delegate to default deserialization
            return p.readValueAs(Object.class);
        }

        @Override
        public JsonDeserializer<?> createContextual(DeserializationContext ctxt,
                                                     BeanProperty property)
                throws JsonMappingException {

            // When the target type is String, don't intercept — let Jackson
            // deserialize normally. The coercion only applies when Jackson
            // would otherwise fail to map a string to a structured type.
            if (property != null && property.getType() != null
                    && property.getType().getRawClass() == String.class) {
                return ctxt.findNonContextualValueDeserializer(
                    ctxt.constructType(String.class));
            }
            return this;
        }
    }
}
