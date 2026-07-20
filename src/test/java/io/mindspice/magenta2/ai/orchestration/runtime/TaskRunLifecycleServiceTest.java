package io.mindspice.magenta2.ai.orchestration.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta2.ai.orchestration.workspaces.TaskIdentity;
import io.mindspice.magenta2.ai.orchestration.workspaces.TaskPathKey;
import io.mindspice.magenta2.ai.orchestration.workspaces.TaskSourceKind;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskRunLifecycleServiceTest {
    @TempDir Path tempDir;

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private WorkspaceDirectoryService directories;
    private TaskRunLifecycleService service;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true);
        dataSource.setSuppressClose(true);
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        directories = WorkspaceDirectoryServiceTestSupport.directoryService(
                tempDir,
                WorkspaceDirectoryServiceTestSupport.agentProfileRepositoryWith("agent-1"),
                WorkspaceDirectoryServiceTestSupport.chatExistenceRepositoryWith("chat-1"));
        service = new TaskRunLifecycleService(jdbc, new ObjectMapper(), directories);
    }

    @Test
    void reservationIsScopedIdempotentSecretFreeAndPreservesNativeStatus() {
        LifecycleReservationRequest first = reservation("same-key", "run-1", "input-a");
        LifecycleResult reserved = tx(() -> service.reserve(first));

        assertThat(reserved.taskRunId()).isEqualTo("run-1");
        assertThat(reserved.nativeStatus()).isNull();
        assertThat(Files.exists(tempDir.resolve("workspace/agent/agent-1/tasks"))).isFalse();

        insertNativePlanRun("run-1", "COMPLETED");
        LifecycleResult replayed = tx(() -> service.reserve(
                reservation("same-key", "caller-generated-run-is-ignored", "input-a")));
        assertThat(replayed.taskRunId()).isEqualTo("run-1");
        assertThat(replayed.nativeStatus()).isEqualTo("COMPLETED");
        assertThat(replayed.toString()).doesNotContain(tempDir.toString(), "fence", "owner");

        int rowsBeforeConflict = lifecycleRowCount();
        assertThatThrownBy(() -> tx(() -> service.reserve(
                reservation("same-key", "run-2", "different-input"))))
                .isInstanceOf(TaskRunLifecycleService.OperationConflictException.class);
        assertThat(lifecycleRowCount()).isEqualTo(rowsBeforeConflict);

        LifecycleResult resumedWithSameKey = tx(() -> service.resume(
                new LifecycleResumeRequest("same-key", "run-1", "resume-input")));
        assertThat(resumedWithSameKey.attemptNumber()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from task_run_idempotent_ops where operation_key = 'same-key'",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void resumeAndLineageUseStableIdentityAndGlobalMonotonicWriterFences() {
        tx(() -> service.reserve(reservation("reserve", "run-1", "input")));
        var identity = new TaskIdentity(TaskSourceKind.TASK, "task-1");
        var descriptor = directories.resolveForRead(WorkspaceKind.AGENT, "agent-1");
        var location = directories.resolveTaskRunForRead(
                descriptor, identity, TaskPathKey.from(identity), "run-1");

        var firstClaim = tx(() -> service.acquireWriterClaim(location, 1, "worker-a"));
        assertThat(service.verifyWriterClaimCurrent(firstClaim)).isTrue();
        tx(() -> { service.completeAttempt(firstClaim); return null; });
        assertThat(service.verifyWriterClaimCurrent(firstClaim)).isFalse();

        LifecycleResult resumed = tx(() -> service.resume(
                new LifecycleResumeRequest("resume", "run-1", "approval")));
        assertThat(resumed.taskRunId()).isEqualTo("run-1");
        assertThat(resumed.attemptNumber()).isEqualTo(2);
        var secondClaim = tx(() -> service.acquireWriterClaim(location, 2, "worker-b"));
        assertThat(secondClaim.fenceGeneration()).isGreaterThan(firstClaim.fenceGeneration());
        assertThatThrownBy(() -> tx(() -> {
            service.releaseWriterClaim(firstClaim);
            return null;
        })).isInstanceOf(TaskRunLifecycleService.StaleWriterException.class);
        tx(() -> { service.completeAttempt(secondClaim); return null; });

        LifecycleResult retry = tx(() -> service.retry(new LifecycleLineageRequest(
                "retry", "run-1", RunLineageMode.RETRY_OF,
                "run-2", "run-2", null, "retry-input")));
        LifecycleResult replay = tx(() -> service.replay(new LifecycleLineageRequest(
                "replay", "run-1", RunLineageMode.REPLAY_OF,
                "run-3", "run-3", null, "replay-input")));
        assertThat(retry.taskRunId()).isNotEqualTo("run-1");
        assertThat(retry.lineageParentTaskRunId()).isEqualTo("run-1");
        assertThat(retry.lineageMode()).isEqualTo(RunLineageMode.RETRY_OF);
        assertThat(replay.lineageMode()).isEqualTo(RunLineageMode.REPLAY_OF);
        assertThat(service.resolveLocation("run-2").rootRelativePath())
                .doesNotContain("run-1/run-2")
                .endsWith("/run-2");
    }

    @Test
    void childLinksUseDistinctSiblingRunsAndTypedKeys() {
        tx(() -> service.reserve(reservation("parent", "run-parent", "parent-input")));
        LifecycleResult child = tx(() -> service.reserveChild(
            "run-parent", "WORKFLOW_CHILD", "run-parent:node-a:1",
            "workflow-child:run-parent:node-a:1", TaskSourceKind.TASK,
            "task-child", "run-child", "child-input"));

        assertThat(child.taskRunId()).isEqualTo("run-child");
        assertThat(service.provisionLocation(child.taskRunId()).rootRelativePath())
            .endsWith("/tasks/" + TaskPathKey.from(new TaskIdentity(TaskSourceKind.TASK, "task-child")).serialized()
                + "/run-child")
            .doesNotContain("run-parent/run-child");
        assertThat(jdbc.queryForObject("""
                select link_type || ':' || link_key from task_run_links
                where child_task_run_id = 'run-child'
                """, String.class)).isEqualTo("WORKFLOW_CHILD:run-parent:node-a:1");
    }

    @Test
    void chatIsRejectedBeforeAnyLifecycleMutation() {
        LifecycleReservationRequest chat = new LifecycleReservationRequest(
                "chat-op", TaskSourceKind.TASK, "task-1", WorkspaceKind.CHAT, "chat-1",
                "run-chat", "run-chat", null, null, null, "input");
        assertThatThrownBy(() -> tx(() -> service.reserve(chat)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CHAT");
        assertThat(lifecycleRowCount()).isZero();
    }

    private LifecycleReservationRequest reservation(String operationKey, String runId, String inputDigest) {
        return LifecycleReservationRequest.reuseNative(operationKey, TaskSourceKind.TASK, "task-1",
                WorkspaceKind.AGENT, "agent-1", runId, null, inputDigest);
    }

    private void insertNativePlanRun(String runId, String status) {
        String now = Instant.now().toString();
        jdbc.update("""
                insert into plan_definitions(
                    id, kind, status, title, deliverables_json, inputs_json, outputs_json,
                    assumptions_json, steps_json, validation_criteria_json,
                    execution_evidence_json, validation_feedback_json,
                    pending_questions_json, pending_question_index, plan_start_message_order,
                    created_at, updated_at)
                values ('task-1', 'TASK_TEMPLATE', 'APPROVED', 'Task', '[]', '[]', '[]',
                        '[]', '[]', '[]', '[]', '[]', '[]', 0, 0, ?, ?)
                """, now, now);
        jdbc.update("""
                insert into plan_runs(
                    id, plan_id, status, input_values_json, output_values_json, plan_snapshot_json,
                    execution_evidence_json, validation_feedback_json, deliverable_evidence_json,
                    created_at, updated_at)
                values (?, 'task-1', ?, '{}', '{}', '{}', '[]', '[]', '[]', ?, ?)
                """, runId, status, now, now);
    }

    private int lifecycleRowCount() {
        return jdbc.queryForObject("select count(*) from task_run_locations", Integer.class)
                + jdbc.queryForObject("select count(*) from task_run_attempts", Integer.class)
                + jdbc.queryForObject("select count(*) from task_run_idempotent_ops", Integer.class);
    }

    private <T> T tx(java.util.concurrent.Callable<T> action) {
        return transactions.execute(status -> {
            try {
                return action.call();
            } catch (RuntimeException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }
}
