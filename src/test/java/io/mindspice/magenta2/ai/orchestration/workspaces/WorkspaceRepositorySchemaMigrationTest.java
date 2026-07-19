package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import io.mindspice.magenta2.ai.chat.plan.PlanRepository;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationRuntimeRepository;
import io.mindspice.magenta2.ai.orchestration.workflow.InboxMessageToType;
import io.mindspice.magenta2.ai.orchestration.workflow.InboxMessageType;
import io.mindspice.magenta2.ai.orchestration.workflow.WorkflowRepository;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceRepositorySchemaMigrationTest {

    @Test
    void schemaSqlCreatesCurrentLeaseTableWithoutWorkspaceRoots() throws Exception {
        String schema = Files.readString(schemaPath());

        assertThat(schema).doesNotContain("create table if not exists workspace_roots");
        assertThat(schema).doesNotContain("references workspace_roots");
        assertThat(schema).contains("create table if not exists workspace_leases");
        assertThat(schema).contains("foreign key(workspace_id) references workspaces(id)");
    }

    @Test
    void schemaSqlCreatesCurrentPlanAndOutputArtifactShape() throws Exception {
        JdbcTemplate jdbc = jdbc();

        applySchema(jdbc);

        List<String> planRunColumns = columns(jdbc, "plan_runs");
        List<String> artifactColumns = columns(jdbc, "run_output_artifacts");

        assertThat(planRunColumns)
            .contains("run_display_name", "workspace_type", "workspace_key", "workspace_identity")
            .doesNotContain("workspace_id", "output_directory", "temp_workspace_path");
        assertThat(artifactColumns)
            .contains("agent_id", "job_id", "job_assignment_id", "job_run_id", "project_id",
                "workspace_type", "workspace_key", "workspace_identity", "run_type")
            .doesNotContain("workspace_id");
        assertThat(foreignKeyTargets(jdbc, "run_output_artifacts")).doesNotContain("plan_runs");
        assertThat(indexes(jdbc, "run_output_artifacts"))
            .contains(
                "idx_run_output_artifacts_run",
                "idx_run_output_artifacts_agent",
                "idx_run_output_artifacts_job",
                "idx_run_output_artifacts_project"
            );

        new PlanRepository(jdbc, new ObjectMapper());
        new WorkspaceRepository(jdbc);
        new OutputArtifactRepository(jdbc);

        assertThat(columns(jdbc, "plan_runs")).containsAll(planRunColumns);
        assertThat(columns(jdbc, "plan_runs")).contains("run_display_name");
        assertThat(columns(jdbc, "run_output_artifacts")).isEqualTo(artifactColumns);
        assertThat(indexes(jdbc, "run_output_artifacts"))
            .contains("idx_run_output_artifacts_workspace_identity");
        assertThat(foreignKeyTargets(jdbc, "run_output_artifacts")).doesNotContain("plan_runs");
    }

    @Test
    void schemaSqlCreatesWorkAreas() throws Exception {
        JdbcTemplate jdbc = jdbc();

        applySchema(jdbc);

        assertThat(columns(jdbc, "work_areas")).containsExactly(
            "id",
            "owner_type",
            "owner_id",
            "workspace_id",
            "root_relative_path",
            "area_relative_path",
            "display_name",
            "system_flag",
            "home_flag",
            "active_flag",
            "metadata_json",
            "created_at",
            "updated_at"
        );
        assertThat(indexes(jdbc, "work_areas")).contains("idx_work_areas_owner");
        assertThat(foreignKeyTargets(jdbc, "work_areas")).contains("workspaces");

        new WorkspaceRepository(jdbc);
        new WorkAreaRepository(jdbc);

        assertThat(columns(jdbc, "work_areas")).contains("area_relative_path", "active_flag");
    }

    @Test
    void schemaSqlCreatesProjectsWithNullableLegacyOwnerAgent() throws Exception {
        JdbcTemplate jdbc = jdbc();

        applySchema(jdbc);

        Integer required = jdbc.query(
            "select \"notnull\" from pragma_table_info('projects') where name = 'owner_agent_id'",
            rs -> rs.next() ? rs.getInt(1) : 1
        );
        assertThat(required).isZero();
    }

    @Test
    void schemaSqlCreatesCurrentInboxOwnershipShape() throws Exception {
        JdbcTemplate jdbc = jdbc();

        applySchema(jdbc);

        List<String> workflowInboxColumns = columns(jdbc, "inbox_messages");
        List<String> runtimeInboxColumns = columns(jdbc, "agent_inbox_messages");

        assertThat(workflowInboxColumns).containsExactly(
            "id",
            "to_type",
            "to_id",
            "from_id",
            "message_type",
            "body",
            "metadata_json",
            "response_json",
            "responded_at",
            "handled_at",
            "created_at",
            "updated_at"
        );
        assertThat(runtimeInboxColumns).containsExactly(
            "id",
            "to_agent_id",
            "from_id",
            "message_type",
            "body",
            "metadata_json",
            "read_flag",
            "handled_flag",
            "created_at",
            "updated_at"
        );
        assertThat(indexes(jdbc, "inbox_messages")).contains("idx_inbox_messages_to");
        assertThat(indexes(jdbc, "agent_inbox_messages")).contains("idx_agent_inbox_messages_to");

        new WorkflowRepository(jdbc, new ObjectMapper());
        new OrchestrationRuntimeRepository(jdbc, new ObjectMapper());

        assertThat(columns(jdbc, "inbox_messages")).isEqualTo(workflowInboxColumns);
        assertThat(columns(jdbc, "agent_inbox_messages")).isEqualTo(runtimeInboxColumns);
        assertThat(indexes(jdbc, "inbox_messages")).contains("idx_inbox_messages_to");
        assertThat(indexes(jdbc, "agent_inbox_messages")).contains("idx_agent_inbox_messages_to");
    }

    @Test
    void repositoriesAddRunDisplayNameColumnsCompatibly() throws Exception {
        JdbcTemplate jdbc = jdbc();
        ObjectMapper mapper = new ObjectMapper();

        applySchema(jdbc);

        assertThat(columns(jdbc, "work_assignments")).contains("run_display_name");
        assertThat(columns(jdbc, "plan_runs")).contains("run_display_name");
        assertThat(columns(jdbc, "workflow_runs")).contains("run_display_name");

        new OrchestrationRuntimeRepository(jdbc, mapper);
        new PlanRepository(jdbc, mapper);
        new WorkflowRepository(jdbc, mapper);

        assertThat(columns(jdbc, "work_assignments")).contains("run_display_name");
        assertThat(columns(jdbc, "plan_runs")).contains("run_display_name");
        assertThat(columns(jdbc, "workflow_runs")).contains("run_display_name");
    }

    @Test
    void schemaSqlDoesNotCreateOrphanJobWorkItemsTable() throws Exception {
        JdbcTemplate jdbc = jdbc();

        applySchema(jdbc);

        assertThat(tableExists(jdbc, "job_work_items")).isFalse();
        assertThat(tableExists(jdbc, "orchestration_job_items")).isTrue();
        assertThat(tableExists(jdbc, "job_definitions")).isTrue();
        assertThat(tableExists(jdbc, "job_runs")).isTrue();
        assertThat(columns(jdbc, "job_definitions"))
            .contains("workspace_type", "workspace_key", "workspace_identity")
            .doesNotContain("workspace_id", "persistent_workspace_enabled");
        assertThat(columns(jdbc, "job_runs"))
            .contains("job_assignment_id", "workspace_type", "workspace_key", "workspace_identity")
            .doesNotContain("workspace_id", "workspace_path", "output_dir");
    }

    @Test
    void workflowAndRuntimeInboxMessagesRemainReadableOnDistinctSurfaces() throws Exception {
        JdbcTemplate jdbc = jdbc();
        Instant created = Instant.parse("2026-05-18T12:00:00Z");
        ObjectMapper mapper = new ObjectMapper();

        applySchema(jdbc);
        WorkflowRepository workflowRepository = new WorkflowRepository(jdbc, mapper);
        OrchestrationRuntimeRepository runtimeRepository = new OrchestrationRuntimeRepository(jdbc, mapper);

        workflowRepository.saveInboxMessage(new io.mindspice.magenta2.ai.orchestration.workflow.InboxMessage(
            "workflow-user-message",
            InboxMessageToType.USER,
            null,
            "workflow-agent",
            InboxMessageType.APPROVAL,
            "Approve workflow run?",
            "{\"workflowRunId\":\"run-1\"}",
            null,
            null,
            null,
            created,
            created
        ));
        workflowRepository.saveInboxMessage(new io.mindspice.magenta2.ai.orchestration.workflow.InboxMessage(
            "workflow-agent-message",
            InboxMessageToType.AGENT,
            "agent-1",
            "workflow-agent",
            InboxMessageType.INFO,
            "Workflow node message",
            "{}",
            null,
            null,
            null,
            created.plusSeconds(1),
            created.plusSeconds(1)
        ));
        runtimeRepository.saveInboxMessage(new io.mindspice.magenta2.ai.orchestration.runtime.InboxMessage(
            "runtime-agent-message",
            "agent-1",
            "operator",
            "INFO",
            "Runtime direct-line message",
            java.util.Map.of("surface", "runtime"),
            false,
            false,
            created.plusSeconds(2),
            created.plusSeconds(2)
        ));

        assertThat(workflowRepository.findInboxByRecipient(InboxMessageToType.USER, null))
            .extracting(io.mindspice.magenta2.ai.orchestration.workflow.InboxMessage::id)
            .containsExactly("workflow-user-message");
        assertThat(workflowRepository.findInboxByRecipient(InboxMessageToType.AGENT, "agent-1"))
            .extracting(io.mindspice.magenta2.ai.orchestration.workflow.InboxMessage::id)
            .containsExactly("workflow-agent-message");
        assertThat(runtimeRepository.findInboxMessages("agent-1"))
            .extracting(io.mindspice.magenta2.ai.orchestration.runtime.InboxMessage::id)
            .containsExactly("runtime-agent-message");

        assertThat(workflowRepository.findInboxMessage("runtime-agent-message")).isEmpty();
        assertThat(runtimeRepository.findInboxMessage("workflow-user-message")).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from inbox_messages", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from agent_inbox_messages", Integer.class)).isEqualTo(1);
    }

    @Test
    void legacyWorkspaceRootsMigrationPreservesWarmLeaseRows() {
        JdbcTemplate jdbc = jdbc();
        String now = Instant.parse("2026-05-18T12:00:00Z").toString();
        String expires = Instant.parse("2026-05-18T13:00:00Z").toString();
        createLegacyWorkspaceTables(jdbc);
        insertLegacyWorkspaceRoot(jdbc, "ws-active", "PROJECT", "project-1", "projects/project-1", now);
        insertLegacyWorkspaceRoot(jdbc, "ws-requested", "PROJECT", "project-2", "projects/project-2", now);
        insertLegacyWorkspaceRoot(jdbc, "ws-released", "JOB", "job-1", "jobs/job-1", now);
        insertLegacyLease(jdbc, "lease-active", "ws-active", "ASSIGNMENT", "run-active", "WRITE", expires, 0, null, now);
        insertLegacyLease(jdbc, "lease-requested", "ws-requested", "ASSIGNMENT", "run-requested", "WRITE", expires, 1, null, now);
        insertLegacyLease(jdbc, "lease-released", "ws-released", "ASSIGNMENT", "run-released", "READ", expires, 0, now, now);

        WorkspaceRepository repository = new WorkspaceRepository(jdbc);

        assertThat(tableExists(jdbc, "workspace_roots")).isFalse();
        assertThat(foreignKeyTargets(jdbc, "workspace_leases")).contains("workspaces");
        assertThat(jdbc.queryForObject("select count(*) from workspace_leases", Integer.class)).isEqualTo(3);
        assertThat(repository.findLeaseById("lease-active").orElseThrow().releasedAt()).isNull();
        assertThat(repository.findLeaseById("lease-requested").orElseThrow().releaseRequested()).isTrue();
        assertThat(repository.findLeaseById("lease-released").orElseThrow().releasedAt()).isNotNull();
        assertThat(repository.findActiveWritableLease("ws-active").orElseThrow().holderId()).isEqualTo("run-active");
        Workspace migratedActive = repository.findById("ws-active").orElseThrow();
        Workspace migratedRequested = repository.findById("ws-requested").orElseThrow();
        Workspace migratedReleased = repository.findById("ws-released").orElseThrow();
        assertThat(migratedActive.ownerType()).isEqualTo(WorkspaceOwnerType.PROJECT);
        assertThat(migratedActive.ownerId()).isEqualTo("project-1");
        assertThat(migratedActive.rootRelativePath()).isEqualTo("projects/project-1");
        assertThat(migratedActive.displayName()).isEqualTo("ws-active");
        assertThat(migratedActive.metadataJson()).isEqualTo("{}");
        assertThat(migratedRequested.ownerType()).isEqualTo(WorkspaceOwnerType.PROJECT);
        assertThat(migratedRequested.ownerId()).isEqualTo("project-2");
        assertThat(migratedRequested.rootRelativePath()).isEqualTo("projects/project-2");
        assertThat(migratedReleased.ownerType()).isEqualTo(WorkspaceOwnerType.JOB);
        assertThat(migratedReleased.ownerId()).isEqualTo("job-1");
        assertThat(migratedReleased.rootRelativePath()).isEqualTo("jobs/job-1");
    }

    private Path schemaPath() throws URISyntaxException {
        return Path.of(getClass().getClassLoader().getResource("schema.sql").toURI());
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
    }

    private void applySchema(JdbcTemplate jdbc) throws Exception {
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
    }

    private void createLegacyWorkspaceTables(JdbcTemplate jdbc) {
        jdbc.execute("""
            create table workspace_roots (
                id text primary key,
                owner_type text not null,
                owner_id text not null,
                root_relative_path text not null,
                display_name text not null,
                metadata_json text,
                created_at text not null,
                updated_at text not null
            )
            """);
        jdbc.execute("""
            create table workspace_leases (
                id text primary key,
                workspace_id text not null,
                holder_type text not null,
                holder_id text not null,
                mode text not null,
                expires_at text,
                release_requested integer not null default 0,
                released_at text,
                created_at text not null,
                updated_at text not null,
                foreign key(workspace_id) references workspace_roots(id)
            )
            """);
        jdbc.execute("""
            create unique index idx_workspace_leases_active_write
                on workspace_leases(workspace_id)
                where mode = 'WRITE' and released_at is null
            """);
    }

    private void insertLegacyWorkspaceRoot(
        JdbcTemplate jdbc,
        String id,
        String ownerType,
        String ownerId,
        String rootRelativePath,
        String now
    ) {
        jdbc.update("""
            insert into workspace_roots (
                id, owner_type, owner_id, root_relative_path, display_name,
                metadata_json, created_at, updated_at
            )
            values (?, ?, ?, ?, ?, '{}', ?, ?)
            """, id, ownerType, ownerId, rootRelativePath, id, now, now);
    }

    private void insertLegacyLease(
        JdbcTemplate jdbc,
        String id,
        String workspaceId,
        String holderType,
        String holderId,
        String mode,
        String expiresAt,
        int releaseRequested,
        String releasedAt,
        String now
    ) {
        jdbc.update("""
            insert into workspace_leases (
                id, workspace_id, holder_type, holder_id, mode,
                expires_at, release_requested, released_at, created_at, updated_at
            )
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, id, workspaceId, holderType, holderId, mode, expiresAt, releaseRequested, releasedAt, now, now);
    }

    private boolean tableExists(JdbcTemplate jdbc, String table) {
        Integer count = jdbc.queryForObject(
            "select count(*) from sqlite_master where type = 'table' and name = ?",
            Integer.class,
            table
        );
        return count != null && count > 0;
    }

    private List<String> foreignKeyTargets(JdbcTemplate jdbc, String table) {
        return jdbc.query(
            "select \"table\" from pragma_foreign_key_list('" + table + "')",
            (rs, rowNum) -> rs.getString(1)
        );
    }

    private List<String> columns(JdbcTemplate jdbc, String table) {
        return jdbc.queryForList("select name from pragma_table_info('" + table + "')", String.class);
    }

    private List<String> indexes(JdbcTemplate jdbc, String table) {
        return jdbc.queryForList("select name from pragma_index_list('" + table + "')", String.class);
    }
}
