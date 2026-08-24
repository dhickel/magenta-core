package io.mindspice.magenta.core.spi;

import io.mindspice.magenta.core.AnswerResult;
import io.mindspice.magenta.core.NonNull;
import io.mindspice.magenta.core.QuestionPendingContext;

/**
 * Callback invoked when the turn engine asks the user one or more questions.
 * The turn is suspended until the embedding app calls {@code TurnLoop.answer()}.
 */
public interface InteractionHandler {

    @NonNull
    AnswerResult onQuestionsAsked(@NonNull QuestionPendingContext context);
}
