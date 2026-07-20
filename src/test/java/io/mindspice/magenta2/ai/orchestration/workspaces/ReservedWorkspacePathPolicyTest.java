package io.mindspice.magenta2.ai.orchestration.workspaces;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservedWorkspacePathPolicyTest {

    // ── Classification ──

    @Test
    void rootAndEmptyClassifyAsOrdinary() {
        assertThat(ReservedWorkspacePathPolicy.classify("."))
                .isEqualTo(ReservedWorkspacePathPolicy.Classification.ORDINARY_CONTENT);
        assertThat(ReservedWorkspacePathPolicy.classify(""))
                .isEqualTo(ReservedWorkspacePathPolicy.Classification.ORDINARY_CONTENT);
    }

    @Test
    void ordinaryContentPaths() {
        assertThat(ReservedWorkspacePathPolicy.classify("notes"))
                .isEqualTo(ReservedWorkspacePathPolicy.Classification.ORDINARY_CONTENT);
        assertThat(ReservedWorkspacePathPolicy.classify("notes/readme.md"))
                .isEqualTo(ReservedWorkspacePathPolicy.Classification.ORDINARY_CONTENT);
        assertThat(ReservedWorkspacePathPolicy.classify("src/main/java/App.java"))
                .isEqualTo(ReservedWorkspacePathPolicy.Classification.ORDINARY_CONTENT);
        assertThat(ReservedWorkspacePathPolicy.classify("deeply/nested/path/to/file.txt"))
                .isEqualTo(ReservedWorkspacePathPolicy.Classification.ORDINARY_CONTENT);
    }

    @Test
    void workspaceOutputsClassification() {
        assertThat(ReservedWorkspacePathPolicy.classify("outputs"))
                .isEqualTo(ReservedWorkspacePathPolicy.Classification.WORKSPACE_OUTPUTS);
        assertThat(ReservedWorkspacePathPolicy.classify("outputs/result.txt"))
                .isEqualTo(ReservedWorkspacePathPolicy.Classification.WORKSPACE_OUTPUTS);
        assertThat(ReservedWorkspacePathPolicy.classify("outputs/deep/nested/file.log"))
                .isEqualTo(ReservedWorkspacePathPolicy.Classification.WORKSPACE_OUTPUTS);
    }

    @Test
    void skillsClassification() {
        assertThat(ReservedWorkspacePathPolicy.classify(".skills"))
                .isEqualTo(ReservedWorkspacePathPolicy.Classification.SKILLS);
        assertThat(ReservedWorkspacePathPolicy.classify(".skills/my-skill"))
                .isEqualTo(ReservedWorkspacePathPolicy.Classification.SKILLS);
        assertThat(ReservedWorkspacePathPolicy.classify(".skills/my-skill/SKILL.md"))
                .isEqualTo(ReservedWorkspacePathPolicy.Classification.SKILLS);
        assertThat(ReservedWorkspacePathPolicy.classify(".skills/deep/nested"))
                .isEqualTo(ReservedWorkspacePathPolicy.Classification.SKILLS);
    }

    @Test
    void tasksRootClassification() {
        assertThat(ReservedWorkspacePathPolicy.classify("tasks"))
                .isEqualTo(ReservedWorkspacePathPolicy.Classification.TASKS_ROOT);
    }

    @Test
    void taskKeyRootClassification() {
        assertThat(ReservedWorkspacePathPolicy.classify("tasks/v1-task-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
                .isEqualTo(ReservedWorkspacePathPolicy.Classification.TASK_KEY_ROOT);
    }

    @Test
    void runRootClassification() {
        assertThat(ReservedWorkspacePathPolicy.classify(
                "tasks/v1-task-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/550e8400-e29b-41d4-a716-446655440000"))
                .isEqualTo(ReservedWorkspacePathPolicy.Classification.RUN_ROOT);
    }

    @Test
    void runWorkingContentClassification() {
        assertThat(ReservedWorkspacePathPolicy.classify(
                "tasks/v1-task-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/run-id/notes/ideas.txt"))
                .isEqualTo(ReservedWorkspacePathPolicy.Classification.RUN_WORKING_CONTENT);
        assertThat(ReservedWorkspacePathPolicy.classify(
                "tasks/v1-task-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/run-id/src/main.py"))
                .isEqualTo(ReservedWorkspacePathPolicy.Classification.RUN_WORKING_CONTENT);
        assertThat(ReservedWorkspacePathPolicy.classify(
                "tasks/v1-task-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/run-id/deep/nested/file.txt"))
                .isEqualTo(ReservedWorkspacePathPolicy.Classification.RUN_WORKING_CONTENT);
    }

    @Test
    void runOutputsClassification() {
        assertThat(ReservedWorkspacePathPolicy.classify(
                "tasks/v1-task-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/run-id/outputs"))
                .isEqualTo(ReservedWorkspacePathPolicy.Classification.RUN_OUTPUTS);
        assertThat(ReservedWorkspacePathPolicy.classify(
                "tasks/v1-task-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/run-id/outputs/result.json"))
                .isEqualTo(ReservedWorkspacePathPolicy.Classification.RUN_OUTPUTS);
        assertThat(ReservedWorkspacePathPolicy.classify(
                "tasks/v1-task-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/run-id/outputs/deep/nested/out.log"))
                .isEqualTo(ReservedWorkspacePathPolicy.Classification.RUN_OUTPUTS);
    }

    @Test
    void publicationStagingClassification() {
        assertThat(ReservedWorkspacePathPolicy.classify(
                "tasks/v1-task-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/run-id/staging"))
                .isEqualTo(ReservedWorkspacePathPolicy.Classification.PUBLICATION_STAGING);
        assertThat(ReservedWorkspacePathPolicy.classify(
                "tasks/v1-task-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/run-id/staging/temp.dat"))
                .isEqualTo(ReservedWorkspacePathPolicy.Classification.PUBLICATION_STAGING);
        assertThat(ReservedWorkspacePathPolicy.classify(
                "tasks/v1-task-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/run-id/staging/deep/nested"))
                .isEqualTo(ReservedWorkspacePathPolicy.Classification.PUBLICATION_STAGING);
    }

    // ── Decision matrix ──

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        ORDINARY_CONTENT     | BROWSE               | ALLOW
        ORDINARY_CONTENT     | ORDINARY_MUTATION    | ALLOW
        ORDINARY_CONTENT     | STRUCTURAL_MUTATION  | DENY
        ORDINARY_CONTENT     | PUBLICATION_MUTATION | DENY
        ORDINARY_CONTENT     | SKILLS_MUTATION      | DENY
        ORDINARY_CONTENT     | RUN_WRITER_MUTATION  | DENY
        WORKSPACE_OUTPUTS    | BROWSE               | ALLOW
        WORKSPACE_OUTPUTS    | ORDINARY_MUTATION    | DENY
        WORKSPACE_OUTPUTS    | STRUCTURAL_MUTATION  | DENY
        WORKSPACE_OUTPUTS    | PUBLICATION_MUTATION | ALLOW
        WORKSPACE_OUTPUTS    | SKILLS_MUTATION      | DENY
        WORKSPACE_OUTPUTS    | RUN_WRITER_MUTATION  | DENY
        TASKS_ROOT           | BROWSE               | ALLOW
        TASKS_ROOT           | ORDINARY_MUTATION    | DENY
        TASKS_ROOT           | STRUCTURAL_MUTATION  | ALLOW
        TASKS_ROOT           | PUBLICATION_MUTATION | DENY
        TASKS_ROOT           | SKILLS_MUTATION      | DENY
        TASKS_ROOT           | RUN_WRITER_MUTATION  | DENY
        TASK_KEY_ROOT        | BROWSE               | ALLOW
        TASK_KEY_ROOT        | ORDINARY_MUTATION    | DENY
        TASK_KEY_ROOT        | STRUCTURAL_MUTATION  | ALLOW
        TASK_KEY_ROOT        | PUBLICATION_MUTATION | DENY
        TASK_KEY_ROOT        | SKILLS_MUTATION      | DENY
        TASK_KEY_ROOT        | RUN_WRITER_MUTATION  | DENY
        RUN_ROOT             | BROWSE               | ALLOW
        RUN_ROOT             | ORDINARY_MUTATION    | DENY
        RUN_ROOT             | STRUCTURAL_MUTATION  | ALLOW
        RUN_ROOT             | PUBLICATION_MUTATION | DENY
        RUN_ROOT             | SKILLS_MUTATION      | DENY
        RUN_ROOT             | RUN_WRITER_MUTATION  | DENY
        RUN_WORKING_CONTENT  | BROWSE               | ALLOW
        RUN_WORKING_CONTENT  | ORDINARY_MUTATION    | DENY
        RUN_WORKING_CONTENT  | STRUCTURAL_MUTATION  | DENY
        RUN_WORKING_CONTENT  | PUBLICATION_MUTATION | DENY
        RUN_WORKING_CONTENT  | SKILLS_MUTATION      | DENY
        RUN_WORKING_CONTENT  | RUN_WRITER_MUTATION  | ALLOW
        RUN_OUTPUTS          | BROWSE               | ALLOW
        RUN_OUTPUTS          | ORDINARY_MUTATION    | DENY
        RUN_OUTPUTS          | STRUCTURAL_MUTATION  | DENY
        RUN_OUTPUTS          | PUBLICATION_MUTATION | ALLOW
        RUN_OUTPUTS          | SKILLS_MUTATION      | DENY
        RUN_OUTPUTS          | RUN_WRITER_MUTATION  | DENY
        PUBLICATION_STAGING  | BROWSE               | DENY
        PUBLICATION_STAGING  | ORDINARY_MUTATION    | DENY
        PUBLICATION_STAGING  | STRUCTURAL_MUTATION  | DENY
        PUBLICATION_STAGING  | PUBLICATION_MUTATION | ALLOW
        PUBLICATION_STAGING  | SKILLS_MUTATION      | DENY
        PUBLICATION_STAGING  | RUN_WRITER_MUTATION  | DENY
        SKILLS               | BROWSE               | DENY
        SKILLS               | ORDINARY_MUTATION    | DENY
        SKILLS               | STRUCTURAL_MUTATION  | DENY
        SKILLS               | PUBLICATION_MUTATION | DENY
        SKILLS               | SKILLS_MUTATION      | ALLOW
        SKILLS               | RUN_WRITER_MUTATION  | DENY
        """)
    void fullDecisionMatrix(String classificationName, String operationName, String expectedDecision) {
        ReservedWorkspacePathPolicy.Classification classification =
                ReservedWorkspacePathPolicy.Classification.valueOf(classificationName);
        ReservedWorkspacePathPolicy.Operation operation =
                ReservedWorkspacePathPolicy.Operation.valueOf(operationName);
        ReservedWorkspacePathPolicy.Decision expected =
                ReservedWorkspacePathPolicy.Decision.valueOf(expectedDecision);

        assertThat(ReservedWorkspacePathPolicy.decide(classification, operation))
                .isEqualTo(expected);
    }

    // ── Rejected inputs ──

    @ParameterizedTest
    @ValueSource(strings = {
            "../outside",
            "notes/../../outside",
            "notes/..",
            "/absolute/path",
            "C:\\windows\\path",
            "C:/windows/path",
            "path\\with\\backslashes",
            "path\0null",
            "path%2fencoded",
            "path%2Fencoded",
            "path%5cencoded",
            "path%5Cencoded",
            "path%00null",
            "notes/./hidden",
            "./notes",
            "//double/slash",
    })
    void rejectsUnsafePaths(String path) {
        assertThatThrownBy(() -> ReservedWorkspacePathPolicy.classify(path))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "tasks/arbitrary",
        "tasks/v1-task-short/run-1",
        "tasks/v1-task-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/run-1",
        "tasks/v1-task-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/outputs",
        "tasks/v1-task-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/staging"
    })
    void rejectsAmbiguousTaskStructure(String path) {
        assertThatThrownBy(() -> ReservedWorkspacePathPolicy.classify(path))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullPath() {
        assertThatThrownBy(() -> ReservedWorkspacePathPolicy.classify(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allClassificationsCoveredByDecisionMatrix() {
        for (ReservedWorkspacePathPolicy.Classification c : ReservedWorkspacePathPolicy.Classification.values()) {
            for (ReservedWorkspacePathPolicy.Operation op : ReservedWorkspacePathPolicy.Operation.values()) {
                ReservedWorkspacePathPolicy.Decision d = ReservedWorkspacePathPolicy.decide(c, op);
                assertThat(d).isNotNull();
            }
        }
    }

    // ── Edge cases ──

    @Test
    void skillsAlwaysHiddenRegardlessOfDepth() {
        assertThat(ReservedWorkspacePathPolicy.decide(
                ReservedWorkspacePathPolicy.Classification.SKILLS,
                ReservedWorkspacePathPolicy.Operation.BROWSE))
                .isEqualTo(ReservedWorkspacePathPolicy.Decision.DENY);
    }

    @Test
    void stagingAlwaysHiddenFromBrowse() {
        assertThat(ReservedWorkspacePathPolicy.decide(
                ReservedWorkspacePathPolicy.Classification.PUBLICATION_STAGING,
                ReservedWorkspacePathPolicy.Operation.BROWSE))
                .isEqualTo(ReservedWorkspacePathPolicy.Decision.DENY);
    }

    @Test
    void runWorkingContentRejectsOrdinaryMutation() {
        assertThat(ReservedWorkspacePathPolicy.decide(
                ReservedWorkspacePathPolicy.Classification.RUN_WORKING_CONTENT,
                ReservedWorkspacePathPolicy.Operation.ORDINARY_MUTATION))
                .isEqualTo(ReservedWorkspacePathPolicy.Decision.DENY);
    }

    @Test
    void ordinaryMutationNeverAuthorizedOnReservedTrees() {
        for (ReservedWorkspacePathPolicy.Classification c : ReservedWorkspacePathPolicy.Classification.values()) {
            if (c != ReservedWorkspacePathPolicy.Classification.ORDINARY_CONTENT) {
                assertThat(ReservedWorkspacePathPolicy.decide(c,
                        ReservedWorkspacePathPolicy.Operation.ORDINARY_MUTATION))
                        .as("ORDINARY_MUTATION must be DENY for " + c)
                        .isEqualTo(ReservedWorkspacePathPolicy.Decision.DENY);
            }
        }
    }

    @Test
    void runWriterMutationOnlyAllowedOnRunWorkingContent() {
        for (ReservedWorkspacePathPolicy.Classification c : ReservedWorkspacePathPolicy.Classification.values()) {
            ReservedWorkspacePathPolicy.Decision d = ReservedWorkspacePathPolicy.decide(c,
                    ReservedWorkspacePathPolicy.Operation.RUN_WRITER_MUTATION);
            if (c == ReservedWorkspacePathPolicy.Classification.RUN_WORKING_CONTENT) {
                assertThat(d).isEqualTo(ReservedWorkspacePathPolicy.Decision.ALLOW);
            } else {
                assertThat(d).as("RUN_WRITER_MUTATION must be DENY for " + c)
                        .isEqualTo(ReservedWorkspacePathPolicy.Decision.DENY);
            }
        }
    }
}
