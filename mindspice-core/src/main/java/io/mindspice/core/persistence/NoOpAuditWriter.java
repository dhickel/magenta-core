package io.mindspice.core.persistence;

import io.mindspice.core.ContextUsage;
import io.mindspice.core.TurnMessage;
import io.mindspice.core.spi.AuditWriter;

/**
 * No-op {@link AuditWriter} implementation — the production default.
 * All methods silently discard their arguments.
 */
public final class NoOpAuditWriter implements AuditWriter {

    private static final NoOpAuditWriter INSTANCE = new NoOpAuditWriter();

    private NoOpAuditWriter() {}

    /** Returns the singleton no-op instance. */
    public static NoOpAuditWriter instance() {
        return INSTANCE;
    }

    @Override
    public void recordUserMessage(String sessionId, TurnMessage message) { /* no-op */ }

    @Override
    public void recordAssistantMessage(String sessionId, TurnMessage message) { /* no-op */ }

    @Override
    public void recordToolExecution(String sessionId, ToolExecutionRecord record) { /* no-op */ }

    @Override
    public void recordContextUsage(String sessionId, ContextUsage usage, String model) { /* no-op */ }

    @Override
    public void recordError(String sessionId, TurnError error) { /* no-op */ }
}
