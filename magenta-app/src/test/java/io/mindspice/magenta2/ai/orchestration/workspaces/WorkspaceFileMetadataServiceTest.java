package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceFileMetadataServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void addAndRemoveLabelsWriteActionLogs() {
        JdbcTemplate jdbc = jdbc();
        WorkspaceFileActionLogRepository actionLogRepository = new WorkspaceFileActionLogRepository(jdbc);
        WorkspaceFileMetadataRepository metadataRepository = new WorkspaceFileMetadataRepository(jdbc);
        WorkspaceFileMetadataService service =
            new WorkspaceFileMetadataService(metadataRepository, actionLogRepository);
        WorkspaceDescriptor descriptor = descriptor("agent", "agent-1");

        WorkspaceFileLabel custom = service.ensureTag("Project Alpha", "Project Alpha");
        assertThat(custom.slug()).isEqualTo("project-alpha");
        assertThat(custom.displayName()).isEqualTo("Project Alpha");

        service.addLabel(descriptor, "home/folder", "project-alpha");
        service.addLabel(descriptor, "home/note.md", "note");
        service.removeLabel(descriptor, "home/note.md", "note");

        assertThat(service.labelsForPath(descriptor, "home/folder"))
            .extracting(a -> a.label().slug())
            .containsExactly("project-alpha");
        assertThat(actionLogRepository.recentForWorkspace(descriptor.identity(), 10))
            .extracting(WorkspaceFileActionRecord::actionType)
            .containsExactly(
                WorkspaceFileActionType.TAG_REMOVE,
                WorkspaceFileActionType.TAG_ADD,
                WorkspaceFileActionType.TAG_ADD
            );
    }

    @Test
    void followHelpersDelegateToRepository() {
        JdbcTemplate jdbc = jdbc();
        WorkspaceFileActionLogRepository actionLogRepository = new WorkspaceFileActionLogRepository(jdbc);
        WorkspaceFileMetadataRepository metadataRepository = new WorkspaceFileMetadataRepository(jdbc);
        WorkspaceFileMetadataService service =
            new WorkspaceFileMetadataService(metadataRepository, actionLogRepository);
        WorkspaceDescriptor descriptor = descriptor("agent", "agent-1");

        service.addLabel(descriptor, "home/a.txt", "note");
        service.onMove(descriptor, "home/a.txt", "home/b.txt");
        service.onCopy(descriptor, "home/b.txt", "home/c.txt");
        service.onDelete(descriptor, "home/b.txt");

        assertThat(service.labelsForPath(descriptor, "home/b.txt")).isEmpty();
        assertThat(service.labelsForPath(descriptor, "home/c.txt"))
            .extracting(a -> a.label().slug())
            .containsExactly("note");
    }

    private WorkspaceDescriptor descriptor(String kindToken, String key) {
        return new WorkspaceDescriptor(WorkspaceKind.fromToken(kindToken), key,
            tempDir.resolve(kindToken + "/" + key).toAbsolutePath().normalize());
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
    }
}
