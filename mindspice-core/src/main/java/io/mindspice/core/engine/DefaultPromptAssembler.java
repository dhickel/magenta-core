package io.mindspice.core.engine;

import io.mindspice.core.NonNull;
import io.mindspice.core.TurnContext;
import io.mindspice.core.spi.PromptContributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Package-private prompt assembler with a 3-layer priority structure:
 * <ol>
 *   <li>Default system prompt from configuration</li>
 *   <li>Registered {@link PromptContributor} implementations sorted by
 *       {@link org.springframework.core.annotation.Order @Order} /
 *       {@link org.springframework.core.Ordered}</li>
 *   <li>Turn-specific instructions derived from context metadata</li>
 * </ol>
 *
 * <p>This is a framework-public class — external consumers use
 * the {@code TurnLoop} facade exclusively.</p>
 */
public final class DefaultPromptAssembler {

    private final String defaultSystemPrompt;
    private final List<PromptContributor> contributors;

    public DefaultPromptAssembler(@NonNull String defaultSystemPrompt,
                           @NonNull List<PromptContributor> contributors) {
        this.defaultSystemPrompt = defaultSystemPrompt;
        this.contributors = contributorsSorted(contributors);
    }

    /**
     * Assembles the full system prompt for a turn.
     *
     * @param ctx the turn context
     * @return the assembled prompt string
     */
    @NonNull
    String assemble(@NonNull TurnContext ctx) {
        StringBuilder sb = new StringBuilder();

        // Layer 1: default system prompt
        sb.append(defaultSystemPrompt);

        // Layer 2: prompt contributors in order
        for (PromptContributor contributor : contributors) {
            String contribution = contributor.contribute(ctx);
            if (contribution != null && !contribution.isEmpty()) {
                sb.append('\n').append(contribution);
            }
        }

        // Layer 3: turn instructions from context metadata
        String turnInstructions = buildTurnInstructions(ctx);
        if (!turnInstructions.isEmpty()) {
            sb.append('\n').append(turnInstructions);
        }

        return sb.toString();
    }

    private String buildTurnInstructions(TurnContext ctx) {
        // Derive turn-level instructions from request metadata.
        // Embedding apps can set metadata keys to influence this layer.
        var metadata = ctx.request().metadata();
        if (metadata == null || metadata.isEmpty()) {
            return "";
        }
        // Reserved keys for turn instructions
        String instructions = metadata.get("turnInstructions");
        return instructions != null ? instructions : "";
    }

    // ── Order sorting ──

    private static List<PromptContributor> contributorsSorted(List<PromptContributor> list) {
        List<PromptContributor> mutable = new ArrayList<>(list);
        mutable.sort(DefaultPromptAssembler::compareContributors);
        return List.copyOf(mutable);
    }

    private static int compareContributors(PromptContributor a, PromptContributor b) {
        int orderA = getOrder(a);
        int orderB = getOrder(b);
        return Integer.compare(orderA, orderB);
    }

    private static int getOrder(Object obj) {
        // Check for @Order annotation
        try {
            var cls = obj.getClass();
            var ann = cls.getAnnotation(org.springframework.core.annotation.Order.class);
            if (ann != null) {
                return ann.value();
            }
        } catch (Exception ignored) {
            // @Order not on classpath or not present
        }
        // Check for Ordered interface
        if (obj instanceof org.springframework.core.Ordered ordered) {
            return ordered.getOrder();
        }
        return 0;
    }
}
