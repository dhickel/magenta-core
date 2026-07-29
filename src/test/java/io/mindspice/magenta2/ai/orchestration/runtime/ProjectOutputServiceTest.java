package io.mindspice.magenta2.ai.orchestration.runtime;

import java.time.Instant;

import io.mindspice.magenta2.ai.orchestration.workspaces.PublicationRecord;
import io.mindspice.magenta2.ai.orchestration.workspaces.PublicationState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused tests for ProjectOutputService metadata helpers and DTO invariants
 * introduced in Phase 05.
 */
class ProjectOutputServiceTest {

    @Test
    void formatSizeNull() {
        assertThat(ProjectOutputService.formatSize(null)).isNull();
    }

    @Test
    void formatSizeBytes() {
        assertThat(ProjectOutputService.formatSize(0L)).isEqualTo("0 B");
        assertThat(ProjectOutputService.formatSize(1L)).isEqualTo("1 B");
        assertThat(ProjectOutputService.formatSize(1023L)).isEqualTo("1023 B");
    }

    @Test
    void formatSizeKilobytes() {
        assertThat(ProjectOutputService.formatSize(1024L)).isEqualTo("1.0 KB");
        assertThat(ProjectOutputService.formatSize(1536L)).isEqualTo("1.5 KB");
        assertThat(ProjectOutputService.formatSize(1048575L)).isEqualTo("1024.0 KB");
    }

    @Test
    void formatSizeMegabytes() {
        assertThat(ProjectOutputService.formatSize(1048576L)).isEqualTo("1.0 MB");
        assertThat(ProjectOutputService.formatSize(5242880L)).isEqualTo("5.0 MB");
    }

    @Test
    void formatSizeGigabytes() {
        assertThat(ProjectOutputService.formatSize(1073741824L)).isEqualTo("1.0 GB");
    }

    @Test
    void outputItemDtosCarryOnlySafeFields() {
        // Verify the DTO carries no filesystem paths, staging details, or writer secrets
        ProjectOutputService.ProjectOutputItem item = new ProjectOutputService.ProjectOutputItem(
                "artifact-123", "report.pdf", ".pdf", "application/pdf",
                10240L, "10.0 KB", "task-run-1", "AGENT", "agent-1",
                "TASK_RUN", Instant.parse("2026-07-29T00:00:00Z"), null, null);

        assertThat(item.artifactId()).isEqualTo("artifact-123");
        assertThat(item.logicalName()).isEqualTo("report.pdf");
        assertThat(item.fileExtension()).isEqualTo(".pdf");
        assertThat(item.mediaType()).isEqualTo("application/pdf");
        assertThat(item.verifiedSize()).isEqualTo(10240L);
        assertThat(item.formattedSize()).isEqualTo("10.0 KB");
        assertThat(item.origin()).isEqualTo("TASK_RUN");

        // No filesystem paths exposed
        assertThat(item.toString()).doesNotContain("/workspace", "/data", "staging");
    }

    @Test
    void outputLegacyDtos() {
        ProjectOutputService.ProjectOutputItem item = new ProjectOutputService.ProjectOutputItem(
                "legacy-1", "output.txt", ".txt", null, null, null,
                "run-1", "AGENT", "agent-1", "LEGACY",
                Instant.parse("2026-07-29T00:00:00Z"), "NATIVE_RUN", "project-1");

        assertThat(item.origin()).isEqualTo("LEGACY");
        assertThat(item.runType()).isEqualTo("NATIVE_RUN");
        assertThat(item.mediaType()).isNull();
        assertThat(item.formattedSize()).isNull();
    }

    @Test
    void outputItemNullTimestampsAreAllowed() {
        ProjectOutputService.ProjectOutputItem item = new ProjectOutputService.ProjectOutputItem(
                "a", "b", null, null, null, null, null, null, null,
                "LEGACY", null, null, null);

        assertThat(item.createdAt()).isNull();
        assertThat(item.workspaceKind()).isNull();
    }

    @Test
    void formatSizeLargeValueDoesNotOverflow() {
        // 1 TB
        String formatted = ProjectOutputService.formatSize(1099511627776L);
        assertThat(formatted).isNotNull();
        assertThat(formatted).contains("GB");
    }
}
