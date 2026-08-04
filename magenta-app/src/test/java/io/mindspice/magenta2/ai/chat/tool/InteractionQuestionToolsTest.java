package io.mindspice.magenta2.ai.chat.tool;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InteractionQuestionToolsTest {

    @Test
    void normalizesObjectShapedPlanningQuestions() {
        List<String> questions = InteractionQuestionTools.normalizeQuestions(List.of(
            Map.of(
                "header", "Citation style",
                "question", "How should forum sources be cited?",
                "type", "free_response"
            ),
            Map.of("text", "Should direct grower quotations be included?"),
            "What final report format should be produced?",
            Map.of("header", "Missing question text")
        ));

        assertThat(questions).containsExactly(
            "How should forum sources be cited?",
            "Should direct grower quotations be included?",
            "What final report format should be produced?"
        );
    }
}
