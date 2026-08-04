package io.mindspice.magenta2.ai.chat.service;

import io.mindspice.magenta2.ai.chat.model.ContextUsage;

public record StoredContextUsage(
    ContextUsage usage,
    boolean compacted,
    boolean degraded,
    String degradationReason
) {
    public StoredContextUsage(ContextUsage usage, boolean compacted) {
        this(usage, compacted, false, null);
    }
}
