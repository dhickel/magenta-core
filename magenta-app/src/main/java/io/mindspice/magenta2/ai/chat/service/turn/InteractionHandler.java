package io.mindspice.magenta2.ai.chat.service.turn;

import java.util.List;

/**
 * SPI for handling {@code ask_user} interactions during a turn.
 * <p>
 * This is a temporary Magenta-scoped bridge interface that will be replaced
 * by a framework-native SPI in Phase 07.
 */
public interface InteractionHandler {

    AnswerResult onQuestionsAsked(QuestionPendingContext context);

    record QuestionPendingContext(String sessionId, String turnId, List<String> questions) {}

    sealed interface AnswerResult
            permits AnswerResult.Continue, AnswerResult.Abort,
                    AnswerResult.SingleMessage, AnswerResult.MultiMessage {

        record Continue() implements AnswerResult {}

        record Abort(String reason) implements AnswerResult {}

        record SingleMessage(String message) implements AnswerResult {}

        record MultiMessage(List<String> messages) implements AnswerResult {}
    }
}
