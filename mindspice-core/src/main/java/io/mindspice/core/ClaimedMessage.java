package io.mindspice.core;

import java.time.Instant;

/**
 * A claimed message with its claim token and claim timestamp.
 *
 * @param message    the original pending message
 * @param claimToken token used to ack/release the claim
 * @param claimedAt  when the claim was created
 */
public record ClaimedMessage(
    @NonNull PendingMessage message,
    @NonNull String claimToken,
    @NonNull Instant claimedAt
) {}
