package io.mindspice.magenta.core.spi;

import io.mindspice.magenta.core.ModelCapability;
import io.mindspice.magenta.core.NonNull;

import java.util.Set;

/**
 * Declares the capabilities of a model identified by its alias.
 */
public interface ModelCapabilityProvider {

    @NonNull
    Set<ModelCapability> capabilities(@NonNull String modelAlias);
}
