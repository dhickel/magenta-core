package io.mindspice.magenta2.ai.chat.service.turn;

import java.util.List;

import org.springframework.ai.chat.messages.Message;

/**
 * SPI for producing repair messages when a turn enters a known degenerate state.
 * <p>
 * This is a temporary Magenta-scoped bridge that uses {@code org.springframework.ai.chat.messages.Message}
 * as its return type. Phase 07 replaces this with a framework-native SPI returning {@code TurnMessage}.
 */
public interface RepairStrategy {

    List<Message> repair(TurnContext context, RepairReason reason);

    enum RepairReason {
        EMPTY_RESPONSE,
        TOOL_LOOP_EXCEEDED,
        COMPLETION_NOT_DETECTED,
        CUSTOM
    }
}
