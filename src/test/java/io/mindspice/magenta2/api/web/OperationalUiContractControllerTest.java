package io.mindspice.magenta2.api.web;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OperationalUiContractControllerTest {
    @Test
    void projectControllerHasNoFilesystemWorkspaceOperations() {
        assertThat(Arrays.stream(ProjectController.class.getDeclaredMethods()).map(method -> method.getName()))
            .doesNotContain("workspace", "requestWorkspaceRelease", "workspaceStatus");
    }
}
