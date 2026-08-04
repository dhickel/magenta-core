package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.nio.file.Path;
import java.util.List;

/**
 * Resolver result for runtime AGENTS.md context.
 */
public record AgentsMdResolution(
    Path boundRoot,
    Path activePath,
    List<AgentsMdLayer> layers
) {
    public AgentsMdResolution {
        if (boundRoot == null) {
            throw new IllegalArgumentException("boundRoot is required");
        }
        if (activePath == null) {
            throw new IllegalArgumentException("activePath is required");
        }
        if (layers == null) {
            throw new IllegalArgumentException("layers is required");
        }
        layers = List.copyOf(layers);
    }

    public boolean hasLayers() {
        return !layers.isEmpty();
    }
}
