package io.mindspice.magenta2.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class SystemChatFragmentsTest {

    @Test
    void confirmationPostsTheExactProposalDigestWithoutSupplyingALifecycleOperationKey() {
        String digest = "a".repeat(64);
        String html = SystemChatFragments.proposals("agent-1", List.of(
                new SystemChatFragments.ProposalRecord("proposal-1", "RETRY", "assignment-1", digest,
                        "PENDING", Instant.EPOCH)));

        assertThat(html).contains("name=\"digest\"", "value=\"" + digest + "\"")
                .contains("/api/agents/agent-1/system-chat/proposals/proposal-1/confirm")
                .doesNotContain("operationKey", "UUID", "random");
    }
}
