package io.mindspice.magenta2.ai.chat.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;

public sealed interface ChatRequest {
    record MsgRequest(
        String conversationId,
        @NotBlank String message,
        String model,
        String planningModel,
        String contextCapture
    ) implements ChatRequest {
        public MsgRequest(String conversationId, String message, String model, String planningModel) {
            this(conversationId, message, model, planningModel, null);
        }
    }

    record CmdRequest(String conversationId, @NotBlank String command, String model, String planningModel) implements ChatRequest {
        public CmdRequest(String conversationId, String command) {
            this(conversationId, command, null, null);
        }
    }

    record Archive(boolean archived) implements ChatRequest { }

    record Favorite(@JsonAlias("isFavorite") boolean favorite) implements ChatRequest { }

    record SetTitle(@NotBlank String title) implements ChatRequest { }

    record TurnInterrupt(@NotBlank String conversationId, @NotBlank String interruptToken, @NotBlank String message) implements ChatRequest { }

    record PlanAnswer(String answer, String notes, Integer questionIndex) implements ChatRequest { }

}
