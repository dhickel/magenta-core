package io.mindspice.magenta2.ai.orchestration.runtime;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import static org.assertj.core.api.Assertions.assertThat;

class OrchestrationRuntimeFencingTest {
    private SingleConnectionDataSource dataSource;
    private OrchestrationRuntimeRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true);
        dataSource.setSuppressClose(true);
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        repository = new OrchestrationRuntimeRepository(
                new org.springframework.jdbc.core.JdbcTemplate(dataSource), new ObjectMapper());
        repository.insertAssignment(queuedAssignment("assignment-1"));
    }

    @Test
    void assignmentFenceIsMonotonicAndEveryWorkerMutationRejectsStaleCapability() {
        WorkAssignment first = repository.acquireLeaseFenced(
                "assignment-1", "worker-a", Instant.now().plusSeconds(60)).orElseThrow();
        assertThat(first.leaseFence()).isPositive();
        assertThat(repository.extendRunningLeaseFenced(
                first.id(), "worker-a", first.leaseFence(), Instant.now().plusSeconds(60))).isEqualTo(1);
        assertThat(repository.extendRunningLeaseFenced(
                first.id(), "worker-a", first.leaseFence() + 1, Instant.now().plusSeconds(60))).isZero();
        assertThat(repository.checkpointAssignmentFenced(
                first.id(), "worker-a", first.leaseFence() + 1,
                OrchestrationStatus.RUNNING, 1, Map.of("stale", true),
                Map.of(), Map.of(), null, null)).isEmpty();
        assertThat(repository.completeAssignmentFenced(
                first.id(), "worker-a", first.leaseFence() + 1)).isEmpty();
        assertThat(repository.releaseAssignmentFenced(
                first.id(), "worker-a", first.leaseFence() + 1)).isFalse();

        assertThat(repository.releaseAssignmentFenced(
                first.id(), "worker-a", first.leaseFence())).isTrue();
        WorkAssignment second = repository.acquireLeaseFenced(
                "assignment-1", "worker-b", Instant.now().plusSeconds(60)).orElseThrow();
        assertThat(second.leaseFence()).isGreaterThan(first.leaseFence());
        assertThat(repository.saveAssignmentIfLeaseOwner(
                copyWithCheckpoint(first, Map.of("stale", true)), "worker-a")).isEmpty();
        assertThat(repository.findAssignment("assignment-1").orElseThrow().checkpoint())
                .doesNotContainKey("stale");
        assertThat(repository.cancelAssignmentFenced(
                second.id(), "worker-b", second.leaseFence())).isPresent();
    }

    private WorkAssignment queuedAssignment(String id) {
        return new WorkAssignment(
                id, "agent-1", null, null, AssignmentType.TASK_RUN, "Run", 0,
                OrchestrationStatus.QUEUED, null,
                "agent", "agent-1", "agent/agent-1", null,
                0, Map.of(), Map.of("taskId", "task-1"), Map.of(), Map.of(),
                null, null, null, 0,
                null, null, null, null, null, null);
    }

    private WorkAssignment copyWithCheckpoint(WorkAssignment assignment, Map<String, Object> checkpoint) {
        return new WorkAssignment(
                assignment.id(), assignment.agentId(), assignment.jobId(), assignment.jobItemId(),
                assignment.assignmentType(), assignment.runDisplayName(), assignment.priority(),
                OrchestrationStatus.RUNNING, assignment.modelOverride(), assignment.workspaceType(),
                assignment.workspaceKey(), assignment.workspaceIdentity(), assignment.projectId(),
                assignment.currentItemIndex(), checkpoint, assignment.input(), assignment.output(),
                assignment.evidence(), assignment.errorText(), assignment.leaseOwner(),
                assignment.leaseExpiresAt(), assignment.leaseFence(), assignment.createdAt(),
                assignment.updatedAt(), assignment.startedAt(), assignment.completedAt(),
                assignment.lastProgressAt(), assignment.lastHeartbeatAt());
    }
}
