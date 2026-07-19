package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceFileActionLogRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void recordsWorkspaceFileActionsWithoutAbsolutePathsOrContent() {
        JdbcTemplate jdbc = jdbc();
        WorkspaceFileActionLogRepository repository = new WorkspaceFileActionLogRepository(jdbc);
        WorkspaceDescriptor descriptor = descriptor("agent", "agent-1");

        WorkspaceFileActionRecord record = repository.record(
            descriptor,
            "web",
            "user-1",
            WorkspaceFileActionType.RENAME,
            "home/a.txt",
            "home/b.txt",
            "SUCCEEDED",
            "{\"kind\":\"file\"}"
        );

        assertThat(record.workspaceIdentity()).isEqualTo("agent/agent-1");
        assertThat(record.actorType()).isEqualTo("web");
        assertThat(record.actionType()).isEqualTo(WorkspaceFileActionType.RENAME);
        assertThat(record.sourceRelativePath()).isEqualTo("home/a.txt");
        assertThat(record.targetRelativePath()).isEqualTo("home/b.txt");
        assertThat(record.sourceRelativePath()).doesNotStartWith("/");
        assertThat(record.payloadJson()).doesNotContain("contents");
        assertThat(repository.recentForWorkspace("agent/agent-1", 10)).hasSize(1);
    }

    @Test
    void warmObsoleteActionsRemainUntouched() {
        JdbcTemplate jdbc = jdbc();
        jdbc.execute("create table workspace_file_actions (id text primary key, payload text not null)");
        jdbc.update("insert into workspace_file_actions (id, payload) values (?, ?)", "legacy-1", "unchanged");

        WorkspaceFileActionLogRepository repository = new WorkspaceFileActionLogRepository(jdbc);
        repository.record(descriptor("agent", "agent-1"), WorkspaceFileActionType.CREATE_FOLDER,
            "new", null, "SUCCEEDED", "{}");

        assertThat(jdbc.queryForObject(
            "select payload from workspace_file_actions where id = 'legacy-1'", String.class))
            .isEqualTo("unchanged");
        assertThat(repository.recentForWorkspace("agent/agent-1", 10)).hasSize(1);
    }

    @Test
    void recentActionsAreScopedToWorkspaceIdentity() {
        JdbcTemplate jdbc = jdbc();
        WorkspaceFileActionLogRepository repository = new WorkspaceFileActionLogRepository(jdbc);
        WorkspaceDescriptor agent1 = descriptor("agent", "agent-1");
        WorkspaceDescriptor agent2 = descriptor("agent", "agent-2");

        repository.record(agent1, WorkspaceFileActionType.CREATE_TEXT_FILE,
            "a.txt", null, "SUCCEEDED", "{}");
        repository.record(agent2, WorkspaceFileActionType.CREATE_FOLDER,
            "b", null, "SUCCEEDED", "{}");

        assertThat(repository.recentForWorkspace("agent/agent-1", 10)).hasSize(1);
        assertThat(repository.recentForWorkspace("agent/agent-2", 10)).hasSize(1);
        assertThat(repository.recentForWorkspace("agent/agent-3", 10)).isEmpty();
    }

    private WorkspaceDescriptor descriptor(String kindToken, String key) {
        return new WorkspaceDescriptor(WorkspaceKind.fromToken(kindToken), key,
            tempDir.resolve(kindToken + "/" + key).toAbsolutePath().normalize());
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
    }
}
