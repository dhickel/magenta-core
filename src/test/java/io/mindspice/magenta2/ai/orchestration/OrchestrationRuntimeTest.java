package io.mindspice.magenta2.ai.orchestration;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrchestrationRuntimeTest {
    @Test
    void retiredWorkspaceTypesAreAbsentAndAssignmentQueueLeasesRemain() throws Exception {
        for (String type : new String[] {
            "Workspace", "WorkspaceRepository", "WorkspaceService", "WorkspaceLeaseService",
            "WorkspaceLink", "WorkArea", "WorkAreaRepository", "WorkAreaService"
        }) {
            String className = "io.mindspice.magenta2.ai.orchestration.workspaces." + type;
            assertThatThrownBy(() -> Class.forName(className)).isInstanceOf(ClassNotFoundException.class);
        }

        String schema;
        try (var stream = getClass().getResourceAsStream("/schema.sql")) {
            assertThat(stream).isNotNull();
            schema = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
        assertThat(schema).contains("lease_owner", "lease_expires_at");
        assertThat(schema).doesNotContain("create table if not exists workspace_leases");
    }
}
