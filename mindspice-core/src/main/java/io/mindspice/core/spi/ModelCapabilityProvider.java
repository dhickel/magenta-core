package io.mindspice.core.spi;

import io.mindspice.core.ModelCapability;
import io.mindspice.core.NonNull;

import java.util.Set;

/**
 * Declares the capabilities of a model identified by its alias.
 */
public interface ModelCapabilityProvider {

    @NonNull
    Set<ModelCapability> capabilities(@NonNull String modelAlias);
}
