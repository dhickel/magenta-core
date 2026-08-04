package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceFileMetadataRepositoryTest {

    @Test
    void createsSchemaAndSeedsSystemLabelsIdempotently() {
        JdbcTemplate jdbc = jdbc();
        WorkspaceFileMetadataRepository repository = new WorkspaceFileMetadataRepository(jdbc);
        new WorkspaceFileMetadataRepository(jdbc);

        assertThat(columns(jdbc, "workspace_file_labels"))
            .contains("slug", "display_name", "system_flag");
        assertThat(columns(jdbc, "workspace_file_annotations"))
            .contains("workspace_identity", "file_relative_path", "label_id");
        assertThat(repository.ensureLabel("note", "Note", true).system()).isTrue();
    }

    @Test
    void addsListsMovesCopiesAndDeletesLabels() {
        JdbcTemplate jdbc = jdbc();
        WorkspaceFileMetadataRepository repository = new WorkspaceFileMetadataRepository(jdbc);

        WorkspaceFileLabel custom = repository.ensureLabel("Project Alpha", "Project Alpha", false);
        assertThat(custom.slug()).isEqualTo("project-alpha");
        assertThat(custom.displayName()).isEqualTo("Project Alpha");
        assertThat(custom.system()).isFalse();

        repository.addLabel("agent/agent-1", "home/notes", "project-alpha");
        assertThat(repository.labelsForPath("agent/agent-1", "home/notes"))
            .extracting(a -> a.label().slug())
            .containsExactly("project-alpha");

        repository.addLabel("agent/agent-1", "home/notes/a.md", "note");
        assertThat(repository.labelsForPath("agent/agent-1", "home/notes/a.md"))
            .extracting(a -> a.label().slug())
            .containsExactly("note");

        repository.moveSubtree("agent/agent-1", "home/notes", "home/archive");
        assertThat(repository.labelsForPath("agent/agent-1", "home/archive"))
            .extracting(a -> a.label().slug())
            .containsExactly("project-alpha");
        assertThat(repository.labelsForPath("agent/agent-1", "home/archive/a.md"))
            .extracting(a -> a.label().slug())
            .containsExactly("note");

        repository.copySubtree("agent/agent-1", "home/archive", "home/copy");
        assertThat(repository.labelsForPath("agent/agent-1", "home/copy"))
            .extracting(a -> a.label().slug())
            .containsExactly("project-alpha");
        assertThat(repository.labelsForPath("agent/agent-1", "home/copy/a.md"))
            .extracting(a -> a.label().slug())
            .containsExactly("note");

        assertThat(repository.deleteSubtree("agent/agent-1", "home/archive")).isEqualTo(2);
        assertThat(repository.labelsForPath("agent/agent-1", "home/archive")).isEmpty();
        assertThat(repository.labelsForPath("agent/agent-1", "home/archive/a.md")).isEmpty();
        assertThat(repository.labelsForPath("agent/agent-1", "home/copy")).hasSize(1);
        assertThat(repository.labelsForPath("agent/agent-1", "home/copy/a.md")).hasSize(1);
    }

    @Test
    void annotationsDoNotLeakAcrossIdentities() {
        JdbcTemplate jdbc = jdbc();
        WorkspaceFileMetadataRepository repository = new WorkspaceFileMetadataRepository(jdbc);

        repository.addLabel("agent/agent-1", "file.txt", "note");
        repository.addLabel("agent/agent-2", "file.txt", "note");

        assertThat(repository.labelsForPath("agent/agent-1", "file.txt")).hasSize(1);
        assertThat(repository.labelsForPath("agent/agent-2", "file.txt")).hasSize(1);
        assertThat(repository.labelsForPath("agent/agent-3", "file.txt")).isEmpty();
    }

    @Test
    void warmObsoleteAssignmentsRemainUntouched() {
        JdbcTemplate jdbc = jdbc();
        jdbc.execute("create table workspace_file_label_assignments (id text primary key, payload text not null)");
        jdbc.update("insert into workspace_file_label_assignments (id, payload) values (?, ?)", "legacy-1", "unchanged");

        WorkspaceFileMetadataRepository repository = new WorkspaceFileMetadataRepository(jdbc);
        repository.addLabel("agent/agent-1", "new.txt", "note");

        assertThat(jdbc.queryForObject(
            "select payload from workspace_file_label_assignments where id = 'legacy-1'", String.class))
            .isEqualTo("unchanged");
        assertThat(repository.labelsForPath("agent/agent-1", "new.txt")).hasSize(1);
    }

    @Test
    void escapedSubtreeQueriesWork() {
        JdbcTemplate jdbc = jdbc();
        WorkspaceFileMetadataRepository repository = new WorkspaceFileMetadataRepository(jdbc);

        repository.addLabel("agent/agent-1", "home/test_notes/a.md", "note");
        repository.addLabel("agent/agent-1", "home/test%notes/b.md", "note");

        assertThat(repository.labelsForSubtree("agent/agent-1", "home/test_notes")).hasSize(1);
        assertThat(repository.labelsForSubtree("agent/agent-1", "home/test%notes")).hasSize(1);
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
    }

    private List<String> columns(JdbcTemplate jdbc, String table) {
        return jdbc.queryForList("select name from pragma_table_info('" + table + "')", String.class);
    }
}
