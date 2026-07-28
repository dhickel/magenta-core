package io.mindspice.magenta2.ai.chat.tool;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.util.JsonHelper;
import org.springframework.core.ParameterizedTypeReference;

import static org.assertj.core.api.Assertions.assertThat;

class ToolArgumentCoercionConfigTest {

    private final JsonHelper json = new JsonHelper();

    @Test
    void scalarIsCoercedToSingleElementList() {
        List<String> result = json.fromJson("\"None.\"",
            new ParameterizedTypeReference<>() {});

        assertThat(result).containsExactly("None.");
    }

    @Test
    void actualArraysPassThrough() {
        List<String> result = json.fromJson("[\"a\", \"b\"]",
            new ParameterizedTypeReference<>() {});

        assertThat(result).containsExactly("a", "b");
    }

    @Test
    void integerScalarCoercedToSingleElementList() {
        List<Integer> result = json.fromJson("42",
            new ParameterizedTypeReference<>() {});

        assertThat(result).containsExactly(42);
    }
}
