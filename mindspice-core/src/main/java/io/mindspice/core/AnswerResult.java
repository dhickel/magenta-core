package io.mindspice.core;

import java.util.List;

/**
 * Result of answering pending questions in a question-user interaction loop.
 */
public sealed interface AnswerResult
    permits AnswerResult.Continue, AnswerResult.Abort,
            AnswerResult.SingleMessage, AnswerResult.MultiMessage {

    record Continue() implements AnswerResult {}

    record Abort(@NonNull String reason) implements AnswerResult {}

    record SingleMessage(@NonNull String message) implements AnswerResult {}

    record MultiMessage(@NonNull List<String> messages) implements AnswerResult {}
}
