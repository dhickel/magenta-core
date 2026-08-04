package io.mindspice.magenta2.ai.chat.plan;

import java.util.List;

import io.mindspice.magenta2.ai.chat.service.ChatModelRouter;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

@Component
class ChatModelPlanCompletionValidator implements PlanCompletionValidator {
    private final ChatModelRouter chatModelRouter;

    ChatModelPlanCompletionValidator(ChatModelRouter chatModelRouter) {
        this.chatModelRouter = chatModelRouter;
    }

    @Override
    public ValidationResponse validate(ValidationRequest request) {
        String response = chatModelRouter.chatClient(request.model())
            .prompt(new Prompt(
                List.of(
                    new SystemMessage(request.systemPrompt()),
                    new UserMessage(request.userInput())
                ),
                chatModelRouter.chatOptions(request.model())
            ))
            .call()
            .content();
        return new ValidationResponse(request.model(), response);
    }
}
