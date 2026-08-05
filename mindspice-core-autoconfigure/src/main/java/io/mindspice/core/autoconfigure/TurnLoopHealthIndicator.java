package io.mindspice.core.autoconfigure;

import io.mindspice.core.internal.ModelRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Spring Boot actuator {@link HealthIndicator} that pings the default
 * configured AI model through the framework's internal {@link ModelRouter}.
 *
 * <h3>Model access resolution</h3>
 * The {@link io.mindspice.core.TurnLoop} facade does not expose
 * {@link ChatModel} access (Spring AI types are intentionally internal).
 * A separate lightweight {@link ModelRouter} bean is created in
 * {@link TurnLoopAutoConfiguration} from the same configuration
 * properties, producing an independent {@code ChatModel} cache per alias.
 * This trade-off is acceptable because health checks are infrequent.
 *
 * <h3>Health status</h3>
 * <ul>
 *   <li><strong>UP</strong> — model responds with non-null, non-empty
 *       text within the configured timeout</li>
 *   <li><strong>DOWN</strong> — timeout, connection refused, empty
 *       response, or any exception. The detail map includes the model
 *       alias and error description.</li>
 * </ul>
 */
public class TurnLoopHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(TurnLoopHealthIndicator.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private final ModelRouter modelRouter;
    private final String defaultModelAlias;
    private final int timeoutSeconds;

    TurnLoopHealthIndicator(ModelRouter modelRouter,
                            String defaultModelAlias,
                            int timeoutSeconds) {
        this.modelRouter = modelRouter;
        this.defaultModelAlias = defaultModelAlias;
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
    }

    @Override
    public Health health() {
        try {
            ChatModel chatModel = modelRouter.springAiChatModel(defaultModelAlias);
            var future = CompletableFuture.supplyAsync(() -> {
                var response = chatModel.call(new Prompt(new UserMessage("health check")));
                return response.getResult().getOutput().getText();
            });

            String text = future.get(timeoutSeconds, TimeUnit.SECONDS);

            if (text != null && !text.isBlank()) {
                log.debug("Health check OK for model '{}': {} chars", defaultModelAlias, text.length());
                return Health.up()
                    .withDetail("model", defaultModelAlias)
                    .build();
            }

            log.warn("Health check for model '{}' returned empty response", defaultModelAlias);
            return Health.down()
                .withDetail("model", defaultModelAlias)
                .withDetail("error", "empty response")
                .build();

        } catch (TimeoutException e) {
            log.warn("Health check timed out for model '{}' after {}s", defaultModelAlias, timeoutSeconds);
            return Health.down()
                .withDetail("model", defaultModelAlias)
                .withDetail("error", "timeout after " + timeoutSeconds + "s")
                .build();

        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Health check failed for model '{}': {}", defaultModelAlias, errorMsg);
            return Health.down()
                .withDetail("model", defaultModelAlias)
                .withDetail("error", errorMsg)
                .build();
        }
    }
}
