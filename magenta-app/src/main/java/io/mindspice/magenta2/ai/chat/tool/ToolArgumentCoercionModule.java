package io.mindspice.magenta2.ai.chat.tool;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Queue;
import java.util.Set;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.DeserializationConfig;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.deser.ValueDeserializerModifier;
import tools.jackson.databind.deser.std.DelegatingDeserializer;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.type.CollectionType;

/**
 * Keeps model tool arguments forgiving when a model emits one scalar for a collection
 * parameter. Spring AI 2 uses its own Jackson 3 mapper, so this is discovered through
 * Jackson's module service loader rather than Spring's application ObjectMapper.
 */
public final class ToolArgumentCoercionModule extends SimpleModule {

    public ToolArgumentCoercionModule() {
        super("magenta-tool-argument-coercion");
        setDeserializerModifier(new CollectionCoercionModifier());
    }

    private static final class CollectionCoercionModifier extends ValueDeserializerModifier {
        @Override
        public ValueDeserializer<?> modifyCollectionDeserializer(
            DeserializationConfig config,
            CollectionType type,
            BeanDescription.Supplier beanDescription,
            ValueDeserializer<?> deserializer
        ) {
            return new ScalarCollectionDeserializer(deserializer, type);
        }
    }

    private static final class ScalarCollectionDeserializer extends DelegatingDeserializer {
        private final CollectionType collectionType;

        private ScalarCollectionDeserializer(ValueDeserializer<?> delegate, CollectionType collectionType) {
            super(delegate);
            this.collectionType = collectionType;
        }

        @Override
        protected ValueDeserializer<?> newDelegatingInstance(ValueDeserializer<?> delegate) {
            return new ScalarCollectionDeserializer(delegate, collectionType);
        }

        @Override
        public Object deserialize(JsonParser parser, DeserializationContext context) throws JacksonException {
            JsonToken token = parser.currentToken();
            if (token == JsonToken.START_ARRAY || token == JsonToken.VALUE_NULL) {
                return super.deserialize(parser, context);
            }
            Collection<Object> collection = newCollection(collectionType.getRawClass());
            collection.add(context.readValue(parser, collectionType.getContentType()));
            return collection;
        }

        @SuppressWarnings("unchecked")
        private Collection<Object> newCollection(Class<?> collectionClass) {
            if (collectionClass.isInterface()) {
                if (Set.class.isAssignableFrom(collectionClass)) {
                    return new LinkedHashSet<>();
                }
                if (Queue.class.isAssignableFrom(collectionClass)) {
                    return new ArrayDeque<>();
                }
                return new ArrayList<>();
            }
            try {
                return (Collection<Object>) collectionClass.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException exception) {
                return new ArrayList<>();
            }
        }
    }
}
