package io.mindspice.magenta2.ai.chat.plan;

import java.util.Arrays;

import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlanServiceTest {
    @Test
    void planServiceUsesDescriptorDirectoryBoundaryWithoutRetiredWorkspacePersistence() {
        assertThat(Arrays.stream(PlanService.class.getDeclaredFields()).map(field -> field.getType().getSimpleName()))
            .contains("WorkspaceDirectoryService")
            .doesNotContain("WorkspaceService", "WorkspaceRepository", "WorkAreaService");
        assertThat(WorkspaceDirectoryService.class).isNotNull();
    }
}
