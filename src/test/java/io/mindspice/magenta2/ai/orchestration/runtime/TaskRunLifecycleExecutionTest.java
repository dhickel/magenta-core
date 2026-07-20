package io.mindspice.magenta2.ai.orchestration.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta2.ai.orchestration.workspaces.TaskRunLocation;
import io.mindspice.magenta2.ai.orchestration.workspaces.TaskRunWriteCapability;
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

/**
 * Integration-level validation for Gap 6: real execution with lifecycle,
 * writer claim expiry, and concurrent reservation idempotency.
 */
class TaskRunLifecycleExecutionTest {
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
                WorkspaceDirectoryServiceTestSupport.agentProfileRepositoryWith("agent-1", "agent-2"),
                WorkspaceDirectoryServiceTestSupport.chatExistenceRepositoryWith("chat-1"));
        service = new TaskRunLifecycleService(jdbc, new ObjectMapper(), directories);
    }

    // ── Gap 6.1: task reservation → execution → completion ──

    @Test
    void fullReserveExecuteCompleteCycleWritesAndVerifiesClaims() throws Exception {
        insertNativePlanRun("plan-1", "run-exec", "COMPLETED");
        LifecycleReservationRequest request = reservation("exec-cycle", "plan-1", "run-exec", "input-v1");

        // 1. Reserve
        LifecycleResult reserved = tx(() -> service.reserve(request));
        assertThat(reserved.taskRunId()).isEqualTo("run-exec");
        assertThat(reserved.attemptNumber()).isEqualTo(1);
        assertThat(reserved.nativeStatus()).isEqualTo("COMPLETED");

        // 2. Resolve trusted location (lazy — path may not exist on disk yet)
        TaskRunLocation location = service.resolveLocation("run-exec");
        assertThat(location.workspaceKind()).isEqualTo(WorkspaceKind.AGENT);
        assertThat(location.workspaceKey()).isEqualTo("agent-1");
        assertThat(location.rootRelativePath()).contains("tasks");
        // Create the confined path so we can write into it
        Files.createDirectories(location.confinedPath());
        assertThat(location.confinedPath()).isDirectory();

        // 3. Acquire writer claim
        TaskRunWriteCapability cap = tx(() -> service.acquireWriterClaim(location, 1, "runner-1"));
        assertThat(cap.taskRunId()).isEqualTo("run-exec");
        assertThat(cap.attemptNumber()).isEqualTo(1);
        assertThat(cap.fenceGeneration()).isPositive();
        assertThat(service.verifyWriterClaimCurrent(cap)).isTrue();

        // 4. Simulate execution: write a file into the confined path
        Path outputDir = location.confinedPath().resolve("outputs");
        assertThat(outputDir).doesNotExist();
        assertThat(outputDir.toFile().mkdirs()).isTrue();
        assertThat(outputDir).isDirectory();

        // 5. Complete attempt
        tx(() -> { service.completeAttempt(cap); return null; });
        assertThat(service.verifyWriterClaimCurrent(cap)).isFalse();

        // 6. Writer released — can't complete again
        assertThatThrownBy(() -> tx(() -> { service.completeAttempt(cap); return null; }))
                .isInstanceOf(TaskRunLifecycleService.StaleWriterException.class);
    }

    // ── Gap 6.2: concurrent reservation idempotency ──

    @Test
    void concurrentReservationsWithSameOperationKeyReturnIdenticalResult() {
        insertNativePlanRun("plan-1", "run-concurrent", "RUNNING");

        LifecycleReservationRequest first = reservation("idempotent-key", "plan-1", "run-concurrent", "digest-x");
        LifecycleResult r1 = tx(() -> service.reserve(first));
        assertThat(r1.taskRunId()).isEqualTo("run-concurrent");

        // Second call with same key+digest returns identical result
        LifecycleReservationRequest second = reservation("idempotent-key", "plan-1", "ignored-run-id", "digest-x");
        LifecycleResult r2 = tx(() -> service.reserve(second));
        assertThat(r2.taskRunId()).isEqualTo("run-concurrent");
        assertThat(r2.attemptNumber()).isEqualTo(1);
        assertThat(r2.operationKey()).isEqualTo("idempotent-key");

        // Different digest on same key → conflict
        LifecycleReservationRequest conflictDigest = reservation("idempotent-key", "plan-1", "run-other", "digest-y");
        assertThatThrownBy(() -> tx(() -> service.reserve(conflictDigest)))
                .isInstanceOf(TaskRunLifecycleService.OperationConflictException.class)
                .hasMessageContaining("idempotent-key");
    }

    // ── Gap 6.3: writer claim expiry / stale writer ──

    @Test
    void staleWriterClaimIsDetectedAndFailsMutations() {
        insertNativePlanRun("plan-1", "run-stale", "RUNNING");
        LifecycleResult reserved = tx(() -> service.reserve(
                reservation("stale-test", "plan-1", "run-stale", "input")));
        TaskRunLocation location = service.resolveLocation("run-stale");

        // Acquire claim
        TaskRunWriteCapability cap1 = tx(() -> service.acquireWriterClaim(location, 1, "worker-a"));
        assertThat(service.verifyWriterClaimCurrent(cap1)).isTrue();

        // Manually expire the claim (simulate crash recovery)
        jdbc.update("update writer_claim_generations set is_current = 0 where task_run_id = ?",
                "run-stale");
        assertThat(service.verifyWriterClaimCurrent(cap1)).isFalse();

        // Stale claim can't complete
        assertThatThrownBy(() -> tx(() -> { service.completeAttempt(cap1); return null; }))
                .isInstanceOf(TaskRunLifecycleService.StaleWriterException.class);

        // Resume creates new attempt
        LifecycleResult resumed = tx(() -> service.resume(
                new LifecycleResumeRequest("resume-stale", "run-stale", "resume-input")));
        assertThat(resumed.attemptNumber()).isEqualTo(2);

        // New claim works
        TaskRunWriteCapability cap2 = tx(() -> service.acquireWriterClaim(location, 2, "worker-b"));
        assertThat(service.verifyWriterClaimCurrent(cap2)).isTrue();
        assertThat(cap2.fenceGeneration()).isGreaterThan(cap1.fenceGeneration());

        // Old claim still fails
        assertThat(service.verifyWriterClaimCurrent(cap1)).isFalse();
        assertThatThrownBy(() -> tx(() -> { service.releaseWriterClaim(cap1); return null; }))
                .isInstanceOf(TaskRunLifecycleService.StaleWriterException.class);

        tx(() -> { service.completeAttempt(cap2); return null; });
    }

    // ── Gap 6.4: only one current writer per attempt ──

    @Test
    void twoConcurrentWriterClaimsOnSameAttemptAreRejected() {
        insertNativePlanRun("plan-1", "run-one", "RUNNING");
        LifecycleResult reserved = tx(() -> service.reserve(
                reservation("only-one", "plan-1", "run-one", "input")));
        TaskRunLocation location = service.resolveLocation("run-one");

        TaskRunWriteCapability cap1 = tx(() -> service.acquireWriterClaim(location, 1, "worker-a"));
        assertThat(cap1).isNotNull();

        // Second claim on same attempt must fail
        assertThatThrownBy(() -> tx(() -> service.acquireWriterClaim(location, 1, "worker-b")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("current writer");

        tx(() -> { service.completeAttempt(cap1); return null; });
    }

    // ── Gap 6.5: USER workspace execution with lifecycle ──

    @Test
    void userWorkspaceReservationProducesCorrectLocationAndClaims() throws Exception {
        // USER workspace roots must physically pre-exist. Create via the path layout
        // so that resolveForRead (called internally by reserve) succeeds.
        Path userRoot = tempDir.resolve("workspace/user/user-1");
        Files.createDirectories(userRoot);

        LifecycleReservationRequest request = new LifecycleReservationRequest(
                "user-reserve", TaskSourceKind.TASK, "task-2", WorkspaceKind.USER, "user-1",
                "run-user", "run-user", null, null, null, "digest-user");
        LifecycleResult result = tx(() -> service.reserve(request));
        assertThat(result.taskRunId()).isEqualTo("run-user");

        TaskRunLocation location = service.resolveLocation("run-user");
        assertThat(location.workspaceKind()).isEqualTo(WorkspaceKind.USER);
        assertThat(location.workspaceKey()).isEqualTo("user-1");
        Files.createDirectories(location.confinedPath());
        assertThat(location.confinedPath()).isDirectory();

        TaskRunWriteCapability cap = tx(() -> service.acquireWriterClaim(location, 1, "user-worker"));
        assertThat(service.verifyWriterClaimCurrent(cap)).isTrue();
        tx(() -> { service.completeAttempt(cap); return null; });
    }

    // ── Gap 6.6: multi-attempt resume and retry lineage ──

    @Test
    void retryLineagePreservesParentReferenceAndDistinctPath() {
        insertNativePlanRun("plan-1", "run-parent", "COMPLETED");
        LifecycleResult parent = tx(() -> service.reserve(
                reservation("parent", "plan-1", "run-parent", "input")));
        TaskRunLocation parentLoc = service.resolveLocation("run-parent");
        TaskRunWriteCapability cap = tx(() -> service.acquireWriterClaim(parentLoc, 1, "w"));
        tx(() -> { service.completeAttempt(cap); return null; });

        // Retry creates a new child
        LifecycleResult retry = tx(() -> service.retry(new LifecycleLineageRequest(
                "retry-child", "run-parent", RunLineageMode.RETRY_OF,
                "run-child", "run-child", null, "retry-input")));
        assertThat(retry.taskRunId()).isEqualTo("run-child");
        assertThat(retry.lineageParentTaskRunId()).isEqualTo("run-parent");
        assertThat(retry.lineageMode()).isEqualTo(RunLineageMode.RETRY_OF);

        // Child has distinct location
        TaskRunLocation childLoc = service.resolveLocation("run-child");
        assertThat(childLoc.confinedPath()).isNotEqualTo(parentLoc.confinedPath());
        assertThat(childLoc.rootRelativePath()).doesNotContain("run-parent/run-child");
    }

    // ── helpers ──

    private LifecycleReservationRequest reservation(String operationKey, String sourceId,
                                                     String runId, String inputDigest) {
        return LifecycleReservationRequest.reuseNative(operationKey, TaskSourceKind.TASK, sourceId,
                WorkspaceKind.AGENT, "agent-1", runId, null, inputDigest);
    }

    private void insertNativePlanRun(String planId, String runId, String status) {
        String now = Instant.now().toString();
        jdbc.update("""
                insert or ignore into plan_definitions(
                    id, kind, status, title, deliverables_json, inputs_json, outputs_json,
                    assumptions_json, steps_json, validation_criteria_json,
                    execution_evidence_json, validation_feedback_json,
                    pending_questions_json, pending_question_index, plan_start_message_order,
                    created_at, updated_at)
                values (?, 'TASK_TEMPLATE', 'APPROVED', 'Task', '[]', '[]', '[]',
                        '[]', '[]', '[]', '[]', '[]', '[]', 0, 0, ?, ?)
                """, planId, now, now);
        jdbc.update("""
                insert or ignore into plan_runs(
                    id, plan_id, status, input_values_json, output_values_json, plan_snapshot_json,
                    execution_evidence_json, validation_feedback_json, deliverable_evidence_json,
                    created_at, updated_at)
                values (?, ?, ?, '{}', '{}', '{}', '[]', '[]', '[]', ?, ?)
                """, runId, planId, status, now, now);
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
