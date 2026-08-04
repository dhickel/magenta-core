package io.mindspice.magenta2.core.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlainPathSegmentValidatorTest {

    @Test
    void acceptsPlainIds() {
        assertThat(PlainPathSegmentValidator.requirePlainSegment("agent-1_2.3", "agentId"))
            .isEqualTo("agent-1_2.3");
    }

    @Test
    void rejectsTraversalSeparatorsAbsoluteSyntaxEncodedSeparatorsAndBlankIds() {
        for (String invalid : invalidSegments()) {
            assertThatThrownBy(() -> PlainPathSegmentValidator.requirePlainSegment(invalid, "agentId"))
                .as("invalid segment %s", invalid)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agentId");
        }
    }

    private static String[] invalidSegments() {
        return new String[] {
            "",
            " ",
            ".",
            "..",
            "...",
            "a/b",
            "a\\b",
            "/abs",
            "C:abs",
            "%2e%2e",
            "a%2fb",
            "a%5cb"
        };
    }
}
