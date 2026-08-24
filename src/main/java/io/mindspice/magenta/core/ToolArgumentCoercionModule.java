package io.mindspice.magenta.core;

import com.fasterxml.jackson.databind.module.SimpleModule;

/**
 * Marker module for Magenta tool coercion.
 *
 * <p>Coercion is applied locally by {@link DefaultToolRegistry} with the
 * configured mapper and full parameter generic types. The module deliberately
 * registers no global {@code Object.class} deserializer, which would alter
 * unrelated consumer JSON parsing.</p>
 */
public final class ToolArgumentCoercionModule extends SimpleModule {
    public ToolArgumentCoercionModule() {
        super("ToolArgumentCoercionModule");
    }
}
