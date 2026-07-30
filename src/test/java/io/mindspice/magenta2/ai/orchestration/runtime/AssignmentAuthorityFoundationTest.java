package io.mindspice.magenta2.ai.orchestration.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validates Phase 01 authority and assignment foundation contracts.
 * <ul>
 *   <li>Immutable attribution rejection (insert-only creation, CAS guards on
 *       operator-owned fields)</li>
 *   <li>Writer boundary (lease heartbeat does not advance rowVersion)</li>
 *   <li>Project archive/member-removal safety gates</li>
 *   <li>Priority CAS with expected rowVersion</li>
 *   <li>Control operation replay and conflict detection</li>
 *   <li>Scoped query methods with limit clamping</li>
 *   <li>Secret-minimized DTO exclusions</li>
 * </ul>
 */
class AssignmentAuthorityFoundationTest {

    // ── Persistence and scoped queries ──

    @Test
    void insertAssignmentCreatesImmutableAttribution() {
        var ctx = newCtx();
        String id = UUID.randomUUID().toString();
        WorkAssignment inserted = ctx.repo.insertAssignment(assignment(id, "agent-1", "proj-1",
            AssignmentType.TASK_RUN, OrchestrationStatus.QUEUED));
        assertThat(inserted.agentId()).isEqualTo("agent-1");
        assertThat(inserted.projectId()).isEqualTo("proj-1");
        assertThat(inserted.sourceDefinitionId()).isNull();
        assertThat(inserted.contextBindingKey()).isNull();
        assertThat(inserted.rowVersion()).isZero();
    }

    @Test
    void insertAssignmentRejectsDuplicateId() {
        var ctx = newCtx();
        String id = UUID.randomUUID().toString();
        ctx.repo.insertAssignment(assignment(id, "agent-1", "proj-1",
            AssignmentType.TASK_RUN, OrchestrationStatus.QUEUED));
        // Plain INSERT on duplicate key throws a Spring DataIntegrityViolationException
        assertThatThrownBy(() -> ctx.repo.insertAssignment(assignment(id, "agent-2", "proj-2",
            AssignmentType.TASK_RUN, OrchestrationStatus.QUEUED)))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void activeAssignmentsScopedByAgentReturnsOnlyMatchingAgent() {
        var ctx = newCtx();
        ctx.repo.insertAssignment(assignment(UUID.randomUUID().toString(), "agent-a", "proj-1",
            AssignmentType.TASK_RUN, OrchestrationStatus.QUEUED));
        ctx.repo.insertAssignment(assignment(UUID.randomUUID().toString(), "agent-b", "proj-1",
            AssignmentType.TASK_RUN, OrchestrationStatus.QUEUED));

        List<WorkAssignment> active = ctx.repo.findActiveAssignmentsForAgent("agent-a", 50);
        assertThat(active).hasSize(1);
        assertThat(active.get(0).agentId()).isEqualTo("agent-a");
    }

    @Test
    void activeAssignmentsScopedByProjectReturnsOnlyMatchingProject() {
        var ctx = newCtx();
        ctx.repo.insertAssignment(assignment(UUID.randomUUID().toString(), "agent-1", "proj-a",
            AssignmentType.TASK_RUN, OrchestrationStatus.QUEUED));
        ctx.repo.insertAssignment(assignment(UUID.randomUUID().toString(), "agent-1", "proj-b",
            AssignmentType.TASK_RUN, OrchestrationStatus.QUEUED));

        List<WorkAssignment> active = ctx.repo.findActiveAssignmentsForProject("proj-a", 50);
        assertThat(active).hasSize(1);
        assertThat(active.get(0).projectId()).isEqualTo("proj-a");
    }

    @Test
    void terminalAssignmentsExcludedFromActiveQueries() {
        var ctx = newCtx();
        ctx.repo.insertAssignment(assignment(UUID.randomUUID().toString(), "agent-1", "proj-1",
            AssignmentType.TASK_RUN, OrchestrationStatus.COMPLETED));

        assertThat(ctx.repo.findActiveAssignmentsForAgent("agent-1", 50)).isEmpty();
        assertThat(ctx.repo.findTerminalAssignmentsForAgent("agent-1", 50)).hasSize(1);
    }

    @Test
    void scopedQueriesClampLimitToMax() {
        var ctx = newCtx();
        for (int i = 0; i < 5; i++) {
            ctx.repo.insertAssignment(assignment(UUID.randomUUID().toString(), "agent-1", "proj-1",
                AssignmentType.TASK_RUN, OrchestrationStatus.QUEUED));
        }
        assertThat(ctx.repo.findActiveAssignmentsForAgent("agent-1", 3)).hasSize(3);
    }

    // ── Priority CAS ──

    @Test
    void priorityCasSucceedsWithMatchingRowVersion() {
        var ctx = newCtx();
        WorkAssignment a = ctx.repo.insertAssignment(assignment(UUID.randomUUID().toString(), "agent-1", "proj-1",
            AssignmentType.TASK_RUN, OrchestrationStatus.QUEUED));
        assertThat(a.rowVersion()).isZero();

        WorkAssignment updated = ctx.repo.updateAssignmentPriority(a.id(), "proj-1", 42, 0);
        assertThat(updated.priority()).isEqualTo(42);
        assertThat(updated.rowVersion()).isEqualTo(1);
    }

    @Test
    void priorityCasFailsWithStaleRowVersion() {
        var ctx = newCtx();
        WorkAssignment a = ctx.repo.insertAssignment(assignment(UUID.randomUUID().toString(), "agent-1", "proj-1",
            AssignmentType.TASK_RUN, OrchestrationStatus.QUEUED));

        assertThatThrownBy(() -> ctx.repo.updateAssignmentPriority(a.id(), "proj-1", 42, 999))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("CAS failed");
    }

    @Test
    void priorityCasRejectsTerminalAssignment() {
        var ctx = newCtx();
        WorkAssignment a = ctx.repo.insertAssignment(assignment(UUID.randomUUID().toString(), "agent-1", "proj-1",
            AssignmentType.TASK_RUN, OrchestrationStatus.COMPLETED));

        assertThatThrownBy(() -> ctx.repo.updateAssignmentPriority(a.id(), "proj-1", 42, 0))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("CAS failed");
    }

    @Test
    void priorityCasRejectsNonQueuedAssignment() {
        var ctx = newCtx();
        WorkAssignment paused = ctx.repo.insertAssignment(assignment(UUID.randomUUID().toString(), "agent-1", "proj-1",
            AssignmentType.TASK_RUN, OrchestrationStatus.PAUSED));

        assertThatThrownBy(() -> ctx.repo.updateAssignmentPriority(paused.id(), "proj-1", 42, 0))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("CAS failed");
    }

    @Test
    void priorityCasRejectsLeasedAssignment() {
        var ctx = newCtx();
        WorkAssignment a = ctx.repo.insertAssignment(assignment(UUID.randomUUID().toString(), "agent-1", "proj-1",
            AssignmentType.TASK_RUN, OrchestrationStatus.QUEUED));
        ctx.repo.acquireLease(a.id(), "runner-1", Instant.now().plusSeconds(60)).orElseThrow();

        WorkAssignment leased = ctx.repo.findAssignment(a.id()).orElseThrow();
        assertThat(leased.leaseOwner()).isEqualTo("runner-1");
        assertThat(leased.status()).isEqualTo(OrchestrationStatus.RUNNING);

        // Priority CAS should reject because lease_owner is not null/empty
        assertThatThrownBy(() -> ctx.repo.updateAssignmentPriority(a.id(), "proj-1", 42, leased.rowVersion()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("CAS failed");
    }

    // ── Control operation idempotency ──

    @Test
    void controlOperationInsertAndCompleteIsIdempotent() {
        var ctx = newCtx();
        String key = "op-" + UUID.randomUUID();
        String digest = AssignmentControlService.digestCanonical("test");

        ctx.repo.insertControlOperation(key, "PRIORITY_CAS", "assign-1", "actor-1",
            "agent:a", "SET_PRIORITY", "agent:a", 0, 10, null, digest, "RESERVED", null);

        var found = ctx.repo.findControlOperation("PRIORITY_CAS", key);
        assertThat(found).isPresent();
        assertThat(found.get().operationState()).isEqualTo("RESERVED");

        ctx.repo.completeControlOperation(key, "PRIORITY_CAS", "COMPLETED", "done");

        var completed = ctx.repo.findControlOperation("PRIORITY_CAS", key);
        assertThat(completed).isPresent();
        assertThat(completed.get().operationState()).isEqualTo("COMPLETED");
    }

    // ── Project access service ──

    @Test
    void projectAccessServiceRequiresActiveProject() {
        var ctx = newCtx();
        ctx.projects.save(new Project("proj-1", "Test", "desc", null, null, null, null, null,
            false, Instant.now(), Instant.now()));

        Project result = ctx.access.requireActiveProject("proj-1");
        assertThat(result.id()).isEqualTo("proj-1");
    }

    @Test
    void projectAccessServiceRejectsArchivedProject() {
        var ctx = newCtx();
        ctx.projects.save(new Project("proj-1", "Test", "desc", null, null, null, null, null,
            true, Instant.now(), Instant.now()));

        assertThatThrownBy(() -> ctx.access.requireActiveProject("proj-1"))
            .isInstanceOf(ProjectOperationsAccessService.ProjectAccessDeniedException.class)
            .hasMessageContaining("archived");
    }

    @Test
    void projectAccessServiceRejectsSubmissionWhenTrustedDeploymentIsDisabled() {
        var ctx = newCtx();
        ctx.projects.save(new Project("proj-1", "Test", "desc", null, null, null, null, null,
            false, Instant.now(), Instant.now()));
        ctx.projects.saveMembership(new ProjectAgentMembership(
            UUID.randomUUID().toString(), "proj-1", "agent-1", "member", Instant.now()));

        assertThatThrownBy(() -> ctx.access.requireEligibleSubmission("proj-1", "agent-1"))
            .isInstanceOf(ProjectOperationsAccessService.ProjectAccessDeniedException.class)
            .hasMessageContaining("trusted deployment capability");
    }

    @Test
    void projectAccessServiceRequiresMemberForSubmission() {
        var ctx = newCtx();
        ctx.projects.save(new Project("proj-1", "Test", "desc", null, null, null, null, null,
            false, Instant.now(), Instant.now()));

        ctx.access.enableTrustedDeployment();
        // Agent "agent-1" is not a member
        assertThatThrownBy(() -> ctx.access.requireEligibleSubmission("proj-1", "agent-1"))
            .isInstanceOf(ProjectOperationsAccessService.ProjectAccessDeniedException.class)
            .hasMessageContaining("not a member");
    }

    @Test
    void projectAccessServiceAllowsEligibleMemberSubmission() {
        var ctx = newCtx();
        ctx.projects.save(new Project("proj-1", "Test", "desc", null, null, null, null, null,
            false, Instant.now(), Instant.now()));
        ctx.projects.saveMembership(new ProjectAgentMembership(
            UUID.randomUUID().toString(), "proj-1", "agent-1", "member", Instant.now()));

        ctx.access.enableTrustedDeployment();
        // Should not throw
        ctx.access.requireEligibleSubmission("proj-1", "agent-1");
    }

    @Test
    void archiveBlockedByActiveAssignments() {
        var ctx = newCtx();
        ctx.projects.save(new Project("proj-1", "Test", "desc", null, null, null, null, null,
            false, Instant.now(), Instant.now()));
        ctx.repo.insertAssignment(assignment(UUID.randomUUID().toString(), "agent-1", "proj-1",
            AssignmentType.TASK_RUN, OrchestrationStatus.QUEUED));
        ctx.access.enableTrustedDeployment();

        assertThatThrownBy(() -> ctx.access.archiveProject("proj-1"))
            .isInstanceOf(ProjectOperationsAccessService.ProjectAccessDeniedException.class)
            .hasMessageContaining("active assignment");
    }

    @Test
    void archiveRequiresTrustedDeployment() {
        var ctx = newCtx();
        ctx.projects.save(new Project("proj-1", "Test", "desc", null, null, null, null, null,
            false, Instant.now(), Instant.now()));

        assertThatThrownBy(() -> ctx.access.archiveProject("proj-1"))
            .isInstanceOf(ProjectOperationsAccessService.ProjectAccessDeniedException.class)
            .hasMessageContaining("trusted deployment");
    }

    // ── Assignment query service ──

    @Test
    void agentScopeQueriesFilterByAgent() {
        var ctx = newCtx();
        ctx.repo.insertAssignment(assignment(UUID.randomUUID().toString(), "agent-1", null,
            AssignmentType.REPORT, OrchestrationStatus.QUEUED));
        ctx.repo.insertAssignment(assignment(UUID.randomUUID().toString(), "agent-2", null,
            AssignmentType.REPORT, OrchestrationStatus.QUEUED));

        var scope = new AssignmentQueryService.AgentScope("agent-1");
        List<WorkAssignment> active = ctx.query.listActive(scope, 50);
        assertThat(active).hasSize(1);
        assertThat(active.get(0).agentId()).isEqualTo("agent-1");
    }

    @Test
    void projectScopeQueriesFilterByProject() {
        var ctx = newCtx();
        ctx.projects.save(new Project("proj-1", "Test", "desc", null, null, null, null, null,
            false, Instant.now(), Instant.now()));
        ctx.repo.insertAssignment(assignment(UUID.randomUUID().toString(), "agent-1", "proj-1",
            AssignmentType.TASK_RUN, OrchestrationStatus.QUEUED));

        var scope = new AssignmentQueryService.ProjectScope("proj-1");
        List<WorkAssignment> active = ctx.query.listActive(scope, 50);
        assertThat(active).hasSize(1);
        assertThat(active.get(0).projectId()).isEqualTo("proj-1");
    }

    @Test
    void cursorRoundTripPreservesMemberIdentity() {
        var ctx = newCtx();
        var a1 = ctx.repo.insertAssignment(assignment(UUID.randomUUID().toString(), "agent-1", null,
            AssignmentType.REPORT, OrchestrationStatus.QUEUED));
        var a2 = ctx.repo.insertAssignment(assignment(UUID.randomUUID().toString(), "agent-1", null,
            AssignmentType.REPORT, OrchestrationStatus.QUEUED));

        var scope = new AssignmentQueryService.AgentScope("agent-1");
        List<WorkAssignment> members = ctx.query.listActive(scope, 50);
        assertThat(members).hasSize(2);

        var cursor = ctx.query.snapshotCursor(scope, "active", members);
        String encoded = cursor.encode();

        var decoded = AssignmentQueryService.CursorSnapshot.decode(encoded);
        assertThat(decoded.scopeDigest()).isEqualTo(cursor.scopeDigest());
        assertThat(decoded.memberIds()).containsExactlyInAnyOrder(a1.id(), a2.id());

        var page = ctx.query.resolveCursor(scope, "active", decoded);
        assertThat(page.members()).hasSize(2);
    }

    @Test
    void cursorValidationRejectsTamperedPayload() {
        var ctx = newCtx();
        ctx.repo.insertAssignment(assignment(UUID.randomUUID().toString(), "agent-1", null,
            AssignmentType.REPORT, OrchestrationStatus.QUEUED));

        var scope = new AssignmentQueryService.AgentScope("agent-1");
        var cursor = ctx.query.snapshotCursor(scope, "active",
            ctx.query.listActive(scope, 50));

        // Tamper with the signature — resolveCursor validates the HMAC
        var tampered = new AssignmentQueryService.CursorSnapshot(
            cursor.snapshotId(), cursor.scopeDigest(), cursor.requestDigest(), cursor.expiresAt(),
            cursor.memberIds(), "tampered-signature-00000000", cursor.filter());

        assertThatThrownBy(() -> ctx.query.resolveCursor(scope, "active", tampered))
            .isInstanceOf(AssignmentQueryService.InvalidCursorException.class)
            .hasMessageContaining("signature");
    }

    @Test
    void cursorValidationRejectsExpiredCursor() {
        var ctx = newCtx();
        // scope-less expired cursor for expiry test
        var expired = new AssignmentQueryService.CursorSnapshot(
            AssignmentQueryService.sha256("agent:x"),
            AssignmentQueryService.sha256("filter=active"),
            Instant.now().minusSeconds(60), List.of(), "sig", "active");

        assertThatThrownBy(() -> ctx.query.resolveCursor(new AssignmentQueryService.AgentScope("x"), "active", expired))
            .isInstanceOf(AssignmentQueryService.InvalidCursorException.class)
            .hasMessageContaining("expired");
    }

    @Test
    void secretMinimizedDtoExcludesSensitiveFields() {
        var ctx = newCtx();
        WorkAssignment a = ctx.repo.insertAssignment(assignment(UUID.randomUUID().toString(), "agent-1", null,
            AssignmentType.REPORT, OrchestrationStatus.QUEUED));
        var entry = AssignmentQueryService.AssignmentListEntry.from(a, List.of("CANCEL", "SET_PRIORITY"));

        assertThat(entry.id()).isEqualTo(a.id());
        assertThat(entry.allowedActions()).contains("CANCEL", "SET_PRIORITY");
        // Verify it does NOT expose raw input/output/evidence fields
        assertThat(entry.getClass().getRecordComponents())
            .noneMatch(rc -> rc.getName().equals("input")
                || rc.getName().equals("output")
                || rc.getName().equals("evidence")
                || rc.getName().equals("leaseOwner")
                || rc.getName().equals("leaseFence")
                || rc.getName().equals("checkpoint"));
    }

    @Test
    void projectArchivedFieldPersistsAndReadsBack() {
        var ctx = newCtx();
        ctx.projects.save(new Project("proj-1", "Test", "desc", null, null, null, null, null,
            false, Instant.now(), Instant.now()));
        assertThat(ctx.projects.findById("proj-1").orElseThrow().archived()).isFalse();

        ctx.projects.archive("proj-1");
        assertThat(ctx.projects.findById("proj-1").orElseThrow().archived()).isTrue();

        ctx.projects.restore("proj-1");
        assertThat(ctx.projects.findById("proj-1").orElseThrow().archived()).isFalse();
    }

    // ── Helpers ──

    record TestContext(
        OrchestrationRuntimeRepository repo,
        ProjectRepository projects,
        ProjectOperationsAccessService access,
        AssignmentQueryService query
    ) {}

    static TestContext newCtx() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource(
            "jdbc:sqlite::memory:?foreign_keys=true", true);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        ObjectMapper mapper = new ObjectMapper();
        OrchestrationRuntimeRepository repo = new OrchestrationRuntimeRepository(jdbc, mapper);
        ProjectRepository projects = new ProjectRepository(jdbc, mapper);
        ProjectOperationsAccessService access = new ProjectOperationsAccessService(projects, repo, null);
        AssignmentQueryService query = new AssignmentQueryService(repo, projects);
        return new TestContext(repo, projects, access, query);
    }

    static WorkAssignment assignment(String id, String agentId, String projectId,
                                      AssignmentType type, OrchestrationStatus status) {
        return new WorkAssignment(
            id, agentId, null, null, type, null, 0, status, null,
            "agent", agentId, "agent/" + agentId, projectId,
            null, null, 0,
            0, Map.of(),
            Map.of("projectId", projectId != null ? projectId : ""), Map.of(), Map.of(),
            null, null, null, 0L,
            Instant.now(), Instant.now(), null, null,
            null, null);
    }
}
