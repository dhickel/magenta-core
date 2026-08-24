package io.mindspice.magenta.core;

import java.util.List;

/**
 * Context for a pending question-user interaction — the framework has asked
 * one or more questions and the turn is suspended awaiting answers.
 *
 * @param sessionId owning session
 * @param turnId    the suspended turn
 * @param questions the questions asked of the user
 */
public record QuestionPendingContext(
    @NonNull String sessionId,
    @NonNull String turnId,
    @NonNull List<String> questions
) {}
