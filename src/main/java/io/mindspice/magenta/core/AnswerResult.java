package io.mindspice.magenta.core;

/**
 * Result of answering pending questions in a question-user interaction loop.
 */
public sealed interface AnswerResult
    permits AnswerResult.Continue, AnswerResult.Abort {

    record Continue() implements AnswerResult {}

    record Abort(@NonNull String reason) implements AnswerResult {}
}
