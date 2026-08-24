package io.mindspice.magenta.core;

import io.mindspice.magenta.core.spi.CompactionStrategy;

/**
 * Summary-based default {@link CompactionStrategy}. Compacts older messages
 * into a single human-readable summary.
 *
 * <p>Strategy: takes the oldest N messages, concatenates their text with
 * role prefixes, and produces a summary message. Embedding applications can
 * replace this with LLM-based summarization via a custom implementation.</p>
 */
public final class DefaultCompactionStrategy implements CompactionStrategy {

    @Override
    @NonNull
    public CompactionResult compact(@NonNull CompactionContext context) {
        var messages = context.messagesToCompact();
        if (messages.isEmpty()) {
            return new CompactionResult("", 0, 0);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[Conversation summary — earlier messages compacted]\n");
        for (TurnMessage msg : messages) {
            sb.append(msg.role()).append(": ");
            String text = msg.text();
            if (text != null) {
                // Truncate each message to 500 chars for the summary
                if (text.length() > 500) {
                    sb.append(text, 0, 500).append("...");
                } else {
                    sb.append(text);
                }
            }
            sb.append('\n');
        }

        String summary = sb.toString();
        // Rough token estimate: ~4 chars per token
        int summaryTokens = summary.length() / 4;
        return new CompactionResult(summary, messages.size(), summaryTokens);
    }
}
