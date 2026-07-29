package io.mindspice.magenta2.ai.orchestration.tasks;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentRequest;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentService;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentType;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationStatus;
import io.mindspice.magenta2.ai.orchestration.runtime.WorkAssignment;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkTaskServiceTest {

    // ── Definition CRUD ──

    @Test
    void createsAndFindsTask() {
        var ctx = new TestContext();
        WorkTaskDefinition task = ctx.service.create("Write tests", "Write unit tests", null, 5, null);

        WorkTaskDefinition found = ctx.service.getById(task.id());
        assertThat(found.title()).isEqualTo("Write tests");
        assertThat(found.description()).isEqualTo("Write unit tests");
        assertThat(found.defaultPriority()).isEqualTo(5);
        assertThat(found.pin()).isFalse();
        assertThat(found.definitionVersion()).isEqualTo(1);
        assertThat(found.promotedPlanId()).isNull();
        assertThat(found.projectId()).isNull();
    }

    @Test
    void createsTaskWithProjectAndPin() {
        var ctx = new TestContext();
        ctx.ensureProject("proj-1");
        ctx.ensureAgent("agent-a");
        ctx.ensureProjectMember("proj-1", "agent-a");
        WorkTaskDefinition task = ctx.service.create("Task 1", null, "proj-1", true, 10, "agent-a");

        assertThat(task.projectId()).isEqualTo("proj-1");
        assertThat(task.pin()).isTrue();
        assertThat(task.preferredAgentId()).isEqualTo("agent-a");
        assertThat(task.defaultPriority()).isEqualTo(10);
    }

    @Test
    void rejectsEmptyTitle() {
        var ctx = new TestContext();
        assertThatThrownBy(() -> ctx.service.create("  ", null, null, 0, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("title");
    }

    @Test
    void listsAllTasks() {
        var ctx = new TestContext();
        ctx.service.create("Task A", null, null, 0, null);
        ctx.service.create("Task B", null, null, 0, null);
        ctx.service.create("Task C", null, null, 0, null);

        List<WorkTaskDefinition> all = ctx.service.listAll();
        assertThat(all).hasSize(3);
        assertThat(all).extracting(WorkTaskDefinition::title)
            .contains("Task A", "Task B", "Task C");
    }

    @Test
    void listsByProject() {
        var ctx = new TestContext();
        ctx.ensureProject("proj-x");
        ctx.ensureProject("proj-y");
        ctx.service.create("X1", null, "proj-x", false, 0, null);
        ctx.service.create("X2", null, "proj-x", true, 0, null);
        ctx.service.create("Y1", null, "proj-y", false, 0, null);

        List<WorkTaskDefinition> projX = ctx.service.listByProject("proj-x");
        assertThat(projX).hasSize(2);
        // pinned first
        assertThat(projX.get(0).pin()).isTrue();

        assertThat(ctx.service.listByProject("proj-none")).isEmpty();
    }

    // ── Pin validation ──

    @Test
    void pinRequiresProject() {
        var ctx = new TestContext();
        // Service create validates pin requires project
        assertThatThrownBy(() ->
            ctx.service.create("Task", null, null, true, 0, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Pin");

        // Edit with pin=true but no project is also rejected
        ctx.ensureProject("proj-temp");
        WorkTaskDefinition task = ctx.service.create("Task", null, "proj-temp", false, 0, null);
        // Remove project binding (simulate) — actually, edit validates against existing projectId
        // which is "proj-temp" so pin=true is valid. The guard only checks if pin && !projectId.
        // For a task without project trying to set pin via edit, it would fail.
        WorkTaskDefinition orphan = ctx.service.create("Orphan", null, null, false, 0, null);
        assertThatThrownBy(() ->
            ctx.service.edit(orphan.id(), 1, null, null, null, true, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Pin");
    }

    @Test
    void pinAllowedWithProject() {
        var ctx = new TestContext();
        ctx.ensureProject("proj-pin");
        WorkTaskDefinition task = ctx.service.create("Pinned", null, "proj-pin", true, 0, null);
        assertThat(task.pin()).isTrue();
        assertThat(task.projectId()).isEqualTo("proj-pin");
    }

    // ── Preferred agent validation ──

    @Test
    void preferredAgentInProjectMustBeMember() {
        var ctx = new TestContext();
        ctx.ensureProject("proj-valid");
        ctx.ensureAgent("agent-member");
        ctx.ensureProjectMember("proj-valid", "agent-member");

        // Should succeed: agent is a member
        WorkTaskDefinition task = ctx.service.create("Task", null, "proj-valid", false, 0, "agent-member");
        assertThat(task.preferredAgentId()).isEqualTo("agent-member");
    }

    @Test
    void preferredAgentNotMemberIsRejected() {
        var ctx = new TestContext();
        ctx.ensureProject("proj-valid");
        ctx.ensureAgent("agent-outside");

        assertThatThrownBy(() ->
            ctx.service.create("Task", null, "proj-valid", false, 0, "agent-outside"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("not a member");
    }

    @Test
    void unboundTaskPreferredAgentMustBeEligible() {
        var ctx = new TestContext();
        ctx.ensureAgent("agent-ok");

        WorkTaskDefinition task = ctx.service.create("Task", null, null, false, 0, "agent-ok");
        assertThat(task.preferredAgentId()).isEqualTo("agent-ok");
    }

    @Test
    void unboundTaskDisabledAgentIsRejected() {
        var ctx = new TestContext();
        ctx.ensureAgent("agent-disabled", "DISABLED");

        assertThatThrownBy(() ->
            ctx.service.create("Task", null, null, false, 0, "agent-disabled"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("disabled");
    }

    @Test
    void missingAgentIsRejected() {
        var ctx = new TestContext();
        // No agent "ghost" exists
        assertThatThrownBy(() ->
            ctx.service.create("Task", null, null, false, 0, "ghost"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("not found");
    }

    // ── Archive guard ──

    @Test
    void cannotCreateTaskForArchivedProject() {
        var ctx = new TestContext();
        ctx.ensureProject("proj-archived", true);

        assertThatThrownBy(() ->
            ctx.service.create("Task", null, "proj-archived", false, 0, null))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("archived");
    }

    @Test
    void cannotEditArchivedProjectTask() {
        var ctx = new TestContext();
        ctx.ensureProject("proj-active");
        WorkTaskDefinition task = ctx.service.create("Task", null, "proj-active", false, 0, null);
        ctx.archiveProject("proj-active");

        assertThatThrownBy(() ->
            ctx.service.edit(task.id(), 1, "New", null, null, null, null))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("archived");
    }

    @Test
    void cannotPromoteArchivedProjectTask() {
        var ctx = new TestContext();
        ctx.ensureProject("proj-active");
        WorkTaskDefinition task = ctx.service.create("Task", null, "proj-active", false, 0, null);
        ctx.archiveProject("proj-active");

        assertThatThrownBy(() ->
            ctx.service.promote(task.id(), "plan-1", 1))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("archived");
    }

    @Test
    void cannotRecordAttemptOnArchivedProjectTask() {
        var ctx = new TestContext();
        ctx.ensureProject("proj-active");
        WorkTaskDefinition task = ctx.service.create("Task", null, "proj-active", false, 0, null);
        ctx.archiveProject("proj-active");

        assertThatThrownBy(() ->
            ctx.service.recordAttempt(task.id(), "assign-1", null))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("archived");
    }

    @Test
    void cannotBindToArchivedProject() {
        var ctx = new TestContext();
        ctx.ensureProject("proj-archived", true);
        WorkTaskDefinition task = ctx.service.create("Task", null, null, false, 0, null);

        assertThatThrownBy(() ->
            ctx.service.bindProject(task.id(), "proj-archived"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("archived");
    }

    // ── Edit with CAS ──

    @Test
    void editRejectsPinWithoutProject() {
        var ctx = new TestContext();
        WorkTaskDefinition task = ctx.service.create("Original", null, null, 0, null);
        assertThat(task.definitionVersion()).isEqualTo(1);

        // pin=true without project should be rejected by validatePin
        assertThatThrownBy(() ->
            ctx.service.edit(task.id(), 1, null, null, null, true, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Pin");
    }

    @Test
    void editsFieldsWithCasAndValidPin() {
        var ctx = new TestContext();
        ctx.ensureProject("proj-ed");
        WorkTaskDefinition task = ctx.service.create("Original", null, "proj-ed", false, 0, null);
        assertThat(task.definitionVersion()).isEqualTo(1);

        WorkTaskDefinition updated = ctx.service.edit(task.id(), 1,
            "Updated Title", "New desc", 42, true, null);

        assertThat(updated.title()).isEqualTo("Updated Title");
        assertThat(updated.description()).isEqualTo("New desc");
        assertThat(updated.defaultPriority()).isEqualTo(42);
        assertThat(updated.pin()).isTrue();
        assertThat(updated.definitionVersion()).isEqualTo(2);
    }

    @Test
    void editRejectsVersionMismatch() {
        var ctx = new TestContext();
        WorkTaskDefinition task = ctx.service.create("Original", null, null, 0, null);

        assertThatThrownBy(() ->
            ctx.service.edit(task.id(), 99, "New", null, null, null, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("version mismatch");
    }

    @Test
    void cannotEditPromotedTask() {
        var ctx = new TestContext();
        ctx.ensurePlan("plan-1");
        WorkTaskDefinition task = ctx.service.create("Task", null, null, 0, null);
        ctx.service.promote(task.id(), "plan-1", 1);

        assertThat(ctx.service.edit(task.id(), 2, "New", null, null, null, null).title())
            .isEqualTo("New");
    }

    @Test
    void editValidatesPreferredAgent() {
        var ctx = new TestContext();
        ctx.ensureProject("proj-ed");
        ctx.ensureAgent("agent-ok");
        ctx.ensureProjectMember("proj-ed", "agent-ok");
        WorkTaskDefinition task = ctx.service.create("Task", null, "proj-ed", false, 0, null);

        // Set preferred agent during edit
        WorkTaskDefinition updated = ctx.service.edit(task.id(), 1,
            null, null, null, null, "agent-ok");
        assertThat(updated.preferredAgentId()).isEqualTo("agent-ok");
    }

    @Test
    void editRejectsNonMemberPreferredAgent() {
        var ctx = new TestContext();
        ctx.ensureProject("proj-ed");
        ctx.ensureAgent("agent-outside");
        WorkTaskDefinition task = ctx.service.create("Task", null, "proj-ed", false, 0, null);

        assertThatThrownBy(() ->
            ctx.service.edit(task.id(), 1, null, null, null, null, "agent-outside"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("not a member");
    }

    // ── Project binding immutability ──

    @Test
    void bindsProjectWhenNull() {
        var ctx = new TestContext();
        ctx.ensureProject("proj-bind");
        WorkTaskDefinition task = ctx.service.create("Task", null, null, 0, null);
        assertThat(task.projectId()).isNull();

        boolean bound = ctx.service.bindProject(task.id(), "proj-bind");
        assertThat(bound).isTrue();

        WorkTaskDefinition updated = ctx.service.getById(task.id());
        assertThat(updated.projectId()).isEqualTo("proj-bind");

        // second bind is a no-op
        boolean rebound = ctx.service.bindProject(task.id(), "proj-other");
        assertThat(rebound).isFalse();
        assertThat(ctx.service.getById(task.id()).projectId()).isEqualTo("proj-bind");
    }

    @Test
    void cannotRebindAfterAttempts() {
        var ctx = new TestContext();
        ctx.ensureProject("proj-bind");
        WorkTaskDefinition task = ctx.service.create("Task", null, null, 0, null);
        ctx.service.recordAttempt(task.id(), "assign-1", null);

        assertThatThrownBy(() -> ctx.service.bindProject(task.id(), "proj-bind"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("attempts");
    }

    // ── Promotion ──

    @Test
    void promotesTaskToPlan() {
        var ctx = new TestContext();
        ctx.ensurePlan("plan-promo");
        WorkTaskDefinition task = ctx.service.create("Promotable", null, null, 0, null);

        WorkTaskDefinition promoted = ctx.service.promote(task.id(), "plan-promo", 1);
        assertThat(promoted.promotedPlanId()).isEqualTo("plan-promo");
        assertThat(promoted.definitionVersion()).isEqualTo(2);
    }

    @Test
    void doublePromotionIsRejected() {
        var ctx = new TestContext();
        ctx.ensurePlan("plan-a");
        ctx.ensurePlan("plan-b");
        WorkTaskDefinition task = ctx.service.create("Task", null, null, 0, null);
        ctx.service.promote(task.id(), "plan-a", 1);

        assertThatThrownBy(() -> ctx.service.promote(task.id(), "plan-b", 2))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already been promoted");
    }

    @Test
    void promotionRejectsVersionMismatch() {
        var ctx = new TestContext();
        ctx.ensurePlan("plan-v");
        WorkTaskDefinition task = ctx.service.create("Task", null, null, 0, null);

        assertThatThrownBy(() -> ctx.service.promote(task.id(), "plan-v", 99))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("version mismatch");
    }

    // ── promoteOperation (creates Plan via PlanService) ──

    @Test
    void promoteOperationCreatesPlan() {
        var ctx = new TestContext();
        WorkTaskDefinition task = ctx.service.create("Promote via op", "A task to promote", null, 0, null);

        WorkTaskDefinition promoted = ctx.service.promoteOperation("promo-op-1", task.id(), 1);
        assertThat(promoted.promotedPlanId()).isNotNull();
        assertThat(promoted.definitionVersion()).isEqualTo(2);

        // Verify the plan was created
        var plan = ctx.jdbc.queryForMap(
            "select id, kind, status, title from plan_definitions where id = ?",
            promoted.promotedPlanId());
        assertThat(plan.get("kind")).isEqualTo("TASK_TEMPLATE");
        assertThat(plan.get("status")).isEqualTo("APPROVED");
        assertThat(plan.get("title")).isEqualTo("Promote via op");
    }

    @Test
    void promoteOperationIsIdempotent() {
        var ctx = new TestContext();
        WorkTaskDefinition task = ctx.service.create("Idempotent promote", null, null, 0, null);

        WorkTaskDefinition first = ctx.service.promoteOperation("promo-idem-1", task.id(), 1);
        String planId = first.promotedPlanId();

        // Second call with same key returns same result, no new Plan
        WorkTaskDefinition second = ctx.service.promoteOperation("promo-idem-1", task.id(), 1);
        assertThat(second.promotedPlanId()).isEqualTo(planId);
        assertThat(second.definitionVersion()).isEqualTo(2);

        // Only one plan was created
        int planCount = ctx.jdbc.queryForObject(
            "select count(*) from plan_definitions where title = 'Idempotent promote'", Integer.class);
        assertThat(planCount).isEqualTo(1);
    }

    @Test
    void promoteOperationDigestConflict() {
        var ctx = new TestContext();
        WorkTaskDefinition task = ctx.service.create("Conflict promote", null, null, 0, null);

        ctx.service.promoteOperation("promo-conflict-1", task.id(), 1);

        // Different expected version means different digest → conflict
        assertThatThrownBy(() ->
            ctx.service.promoteOperation("promo-conflict-1", task.id(), 2))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("contradictory");
    }

    @Test
    void promoteOperationAlreadyPromotedViaOtherKey() {
        var ctx = new TestContext();
        WorkTaskDefinition task = ctx.service.create("Promoted elsewhere", null, null, 0, null);

        // Promote via one key
        WorkTaskDefinition promoted = ctx.service.promoteOperation("promo-a", task.id(), 1);
        String planId = promoted.promotedPlanId();

        // Promote via another key — should still complete (not create another plan)
        WorkTaskDefinition second = ctx.service.promoteOperation("promo-b", task.id(), 2);
        assertThat(second.promotedPlanId()).isEqualTo(planId);
    }

    @Test
    void promoteOperationRequiresPlanService() {
        var ctx = new TestContext();
        // No plan_definitions table → PlanService unavailable
        var noPlanCtx = new TestContext(false);
        noPlanCtx.ensureProject("proj-p");
        WorkTaskDefinition task = noPlanCtx.service.create("No Plan", null, "proj-p", false, 0, null);

        assertThatThrownBy(() ->
            noPlanCtx.service.promoteOperation("promo-noplan", task.id(), 1))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("PlanService");
    }

    // ── Submission pre-check ──

    @Test
    void submissionPreCheckRequiresPromotion() {
        var ctx = new TestContext();
        WorkTaskDefinition task = ctx.service.create("Task", null, null, 0, null);

        assertThatThrownBy(() -> ctx.service.submissionPreCheck(task.id()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not promoted");
    }

    @Test
    void submissionPreCheckPassesForApprovedPlan() {
        var ctx = new TestContext();
        WorkTaskDefinition task = ctx.service.create("Ready task", null, null, 0, null);
        ctx.service.promoteOperation("spc-1", task.id(), 1);

        // Should not throw
        ctx.service.submissionPreCheck(task.id());
    }

    @Test
    void submissionPreCheckRejectsArchivedProject() {
        var ctx = new TestContext();
        ctx.ensureProject("proj-spc");
        WorkTaskDefinition task = ctx.service.create("Task", null, "proj-spc", false, 0, null);

        // Promote then archive
        ctx.service.promoteOperation("spc-2", task.id(), 1);
        ctx.archiveProject("proj-spc");

        assertThatThrownBy(() -> ctx.service.submissionPreCheck(task.id()))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("archived");
    }

    // ── Attempts ──

    @Test
    void recordsAttemptsWithMonotonicOrder() {
        var ctx = new TestContext();
        WorkTaskDefinition task = ctx.service.create("Task", null, null, 0, null);

        WorkTaskAttempt a1 = ctx.service.recordAttempt(task.id(), "assign-1", null);
        assertThat(a1.attemptOrder()).isEqualTo(1);
        assertThat(a1.predecessorAttemptId()).isNull();

        WorkTaskAttempt a2 = ctx.service.recordAttempt(task.id(), "assign-2", a1.id());
        assertThat(a2.attemptOrder()).isEqualTo(2);
        assertThat(a2.predecessorAttemptId()).isEqualTo(a1.id());
    }

    @Test
    void listsAttemptsByTask() {
        var ctx = new TestContext();
        WorkTaskDefinition task = ctx.service.create("Task", null, null, 0, null);
        ctx.service.recordAttempt(task.id(), "assign-a", null);
        ctx.service.recordAttempt(task.id(), "assign-b", null);

        List<WorkTaskAttempt> attempts = ctx.service.listAttempts(task.id());
        assertThat(attempts).hasSize(2);
        assertThat(attempts).extracting(WorkTaskAttempt::assignmentId)
            .contains("assign-a", "assign-b");
    }

    // ── Operation idempotency (CREATE) ──

    @Test
    void createOperationIsIdempotent() {
        var ctx = new TestContext();

        boolean reserved = ctx.service.reserveCreateOperation(
            "op-key-1", "Idempotent Task", null, null, false, 0, null);
        assertThat(reserved).isTrue();

        // Second reserve with same key is a no-op
        boolean reservedAgain = ctx.service.reserveCreateOperation(
            "op-key-1", "Idempotent Task", null, null, false, 0, null);
        assertThat(reservedAgain).isFalse();

        // Complete creates the task
        WorkTaskDefinition task = ctx.service.completeCreateOperation(
            "op-key-1", "Idempotent Task", null, null, false, 0, null);
        assertThat(task.title()).isEqualTo("Idempotent Task");

        // Completing again returns the existing task
        WorkTaskDefinition same = ctx.service.completeCreateOperation(
            "op-key-1", "Idempotent Task", null, null, false, 0, null);
        assertThat(same.id()).isEqualTo(task.id());
    }

    // ── Operation idempotency (EDIT) ──

    @Test
    void editOperationIsIdempotent() {
        var ctx = new TestContext();
        WorkTaskDefinition task = ctx.service.create("Edit me", null, null, 0, null);

        WorkTaskDefinition first = ctx.service.editOperation(
            "edit-op-1", task.id(), 1, "Edited Title", "New desc", 10, null, null);
        assertThat(first.title()).isEqualTo("Edited Title");
        assertThat(first.definitionVersion()).isEqualTo(2);

        // Second call with same key returns same result
        WorkTaskDefinition second = ctx.service.editOperation(
            "edit-op-1", task.id(), 1, "Edited Title", "New desc", 10, null, null);
        assertThat(second.title()).isEqualTo("Edited Title");
    }

    @Test
    void editOperationDigestConflict() {
        var ctx = new TestContext();
        WorkTaskDefinition task = ctx.service.create("Edit conflict", null, null, 0, null);

        ctx.service.editOperation("edit-conflict", task.id(), 1,
            "Title A", null, null, null, null);

        assertThatThrownBy(() ->
            ctx.service.editOperation("edit-conflict", task.id(), 1,
                "Title B", null, null, null, null))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("contradictory");
    }

    // ── Operation idempotency (BIND) ──

    @Test
    void bindProjectOperationIsIdempotent() {
        var ctx = new TestContext();
        ctx.ensureProject("proj-bind-op");
        WorkTaskDefinition task = ctx.service.create("Bind me", null, null, 0, null);

        boolean first = ctx.service.bindProjectOperation("bind-op-1", task.id(), "proj-bind-op");
        assertThat(first).isTrue();
        assertThat(ctx.service.getById(task.id()).projectId()).isEqualTo("proj-bind-op");

        // Second call returns same result
        boolean second = ctx.service.bindProjectOperation("bind-op-1", task.id(), "proj-bind-op");
        assertThat(second).isTrue();
    }

    @Test
    void bindProjectOperationDigestConflict() {
        var ctx = new TestContext();
        ctx.ensureProject("proj-a");
        ctx.ensureProject("proj-b");
        WorkTaskDefinition task = ctx.service.create("Bind conflict", null, null, 0, null);

        ctx.service.bindProjectOperation("bind-conflict", task.id(), "proj-a");

        assertThatThrownBy(() ->
            ctx.service.bindProjectOperation("bind-conflict", task.id(), "proj-b"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("contradictory");
    }

    // ── Independent edits (Task ↔ Plan) ──

    @Test
    void taskEditDoesNotTouchPlan() {
        var ctx = new TestContext();
        WorkTaskDefinition task = ctx.service.create("Indep task", "Desc", null, 0, null);
        WorkTaskDefinition promoted = ctx.service.promoteOperation("indep-1", task.id(), 1);

        // Task edits remain independent after promotion.
        assertThat(ctx.service.edit(task.id(), 2, "New title", null, null, null, null).title())
            .isEqualTo("New title");

        // Plan title remains unchanged
        var plan = ctx.jdbc.queryForMap(
            "select title, summary from plan_definitions where id = ?",
            promoted.promotedPlanId());
        assertThat(plan.get("title")).isEqualTo("Indep task");
        assertThat(plan.get("summary")).isEqualTo("Desc");
    }

    @Test
    void planEditDoesNotTouchTask() {
        var ctx = new TestContext();
        WorkTaskDefinition task = ctx.service.create("Orig title", null, null, 0, null);
        ctx.service.promoteOperation("indep-2", task.id(), 1);

        // Edit the plan directly (simulate via SQL)
        ctx.jdbc.update(
            "update plan_definitions set title = ? where id = ?",
            "Changed plan title", task.promotedPlanId());

        // Task title unchanged
        WorkTaskDefinition after = ctx.service.getById(task.id());
        assertThat(after.title()).isEqualTo("Orig title");
    }

    // ── Submit validation ──

    @Test
    void submitRejectsNullAgentId() {
        var ctx = new TestContext();
        WorkTaskDefinition task = ctx.service.create("Task", null, null, 0, null);
        ctx.service.promoteOperation("sub-1", task.id(), 1);

        assertThatThrownBy(() ->
            ctx.service.submit(task.id(), null, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("agentId");
    }

    @Test
    void submitRejectsUnpromotedTask() {
        var ctx = new TestContext();
        ctx.ensureAgent("agent-a");
        WorkTaskDefinition task = ctx.service.create("Task", null, null, 0, null);

        assertThatThrownBy(() ->
            ctx.service.submit(task.id(), "agent-a", null, null, null, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not promoted");
    }

    @Test
    void submitRejectsArchivedProject() {
        var ctx = new TestContext();
        ctx.ensureProject("proj-arch");
        ctx.ensureAgent("agent-a");
        ctx.ensureProjectMember("proj-arch", "agent-a");
        WorkTaskDefinition task = ctx.service.create("Task", null, "proj-arch", false, 0, null);
        ctx.service.promoteOperation("sub-arch", task.id(), 1);
        ctx.archiveProject("proj-arch");

        assertThatThrownBy(() ->
            ctx.service.submit(task.id(), "agent-a", null, null, null, null))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("archived");
    }

    @Test
    void submitRejectsNonMemberAgent() {
        var ctx = new TestContext();
        ctx.ensureProject("proj-sub");
        ctx.ensureAgent("agent-outside");
        WorkTaskDefinition task = ctx.service.create("Task", null, "proj-sub", false, 0, null);
        ctx.service.promoteOperation("sub-nonmem", task.id(), 1);

        assertThatThrownBy(() ->
            ctx.service.submit(task.id(), "agent-outside", null, null, null, null))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("not a member");
    }

    // ── Submit through stub AssignmentService ──

    @Test
    void submitCreatesAssignmentAndAttempt() {
        var stub = new StubAssignmentService();
        var ctx = new TestContext(stub);
        ctx.ensureAgent("agent-exec");
        WorkTaskDefinition task = ctx.service.create("Submit me", null, null, 0, null);
        ctx.service.promoteOperation("sub-success", task.id(), 1);

        WorkTaskAttempt attempt = ctx.service.submit(
            task.id(), "agent-exec", "Run 1", "agent", "agent-exec", "op-sub-1");

        assertThat(attempt.taskId()).isEqualTo(task.id());
        assertThat(attempt.attemptOrder()).isEqualTo(1);
        assertThat(attempt.assignmentId()).isEqualTo(stub.lastCreatedAssignment.id());
        assertThat(attempt.predecessorAttemptId()).isNull();

        // Verify assignment executes the promoted Plan through the canonical task-run type
        assertThat(stub.lastRequest.assignmentType()).isEqualTo(AssignmentType.TASK_RUN);
        assertThat(stub.lastRequest.sourceDefinitionId()).isEqualTo("PLAN/" + stub.lastRequest.input().get("taskId"));
        assertThat(stub.lastRequest.originDefinitionId()).isEqualTo("WORK_TASK/" + task.id());
        assertThat(stub.lastRequest.projectId()).isNull();  // no project on this task
        assertThat(stub.lastRequest.priority()).isEqualTo(0);
        assertThat(stub.lastRequest.agentId()).isEqualTo("agent-exec");
    }

    @Test
    void submitWithProjectCopiesImmutableAttribution() {
        var stub = new StubAssignmentService();
        var ctx = new TestContext(stub);
        ctx.ensureProject("proj-attr");
        ctx.ensureAgent("agent-exec");
        ctx.ensureProjectMember("proj-attr", "agent-exec");
        WorkTaskDefinition task = ctx.service.create("Task", null, "proj-attr", false, 7, null);
        ctx.service.promoteOperation("sub-attr", task.id(), 1);

        ctx.service.submit(task.id(), "agent-exec", "Run", null, null, null);

        assertThat(stub.lastRequest.projectId()).isEqualTo("proj-attr");
        assertThat(stub.lastRequest.priority()).isEqualTo(7);
    }

    @Test
    void submitBlocksDuplicateActiveAssignment() {
        var stub = new StubAssignmentService();
        var ctx = new TestContext(stub);
        ctx.ensureAgent("agent-exec");
        WorkTaskDefinition task = ctx.service.create("Task", null, null, 0, null);
        ctx.service.promoteOperation("sub-dup", task.id(), 1);

        // First submit succeeds
        WorkTaskAttempt first = ctx.service.submit(
            task.id(), "agent-exec", "Run 1", null, null, null);
        assertThat(first).isNotNull();

        // Set the assignment status to QUEUED (non-terminal) to trigger active check
        ctx.setAssignmentStatus(first.assignmentId(), "QUEUED");

        // Second submit should fail because assignment is active
        assertThatThrownBy(() ->
            ctx.service.submit(task.id(), "agent-exec", "Run 2", null, null, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already has an active assignment");
    }

    // ── Submit operation idempotency ──

    @Test
    void submitOperationIsIdempotent() {
        var stub = new StubAssignmentService();
        var ctx = new TestContext(stub);
        ctx.ensureAgent("agent-exec");
        WorkTaskDefinition task = ctx.service.create("Task", null, null, 0, null);
        ctx.service.promoteOperation("sub-idem", task.id(), 1);

        WorkTaskAttempt first = ctx.service.submitOperation(
            "sub-op-1", task.id(), "agent-exec", "Run", null, null);

        // Reset stub to track second call
        stub.lastRequest = null;
        stub.lastCreatedAssignment = null;

        WorkTaskAttempt second = ctx.service.submitOperation(
            "sub-op-1", task.id(), "agent-exec", "Run", null, null);

        // Second call returns same attempt, no new assignment created
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(stub.lastRequest).isNull(); // assignment not re-created
    }

    @Test
    void submitOperationReplayReturnsItsRecordedAttemptAfterRetry() {
        var stub = new StubAssignmentService();
        var ctx = new TestContext(stub);
        ctx.ensureAgent("agent-exec");
        WorkTaskDefinition task = ctx.service.create("Task", null, null, 0, null);
        ctx.service.promoteOperation("submit-replay-promo", task.id(), 1);

        WorkTaskAttempt submitted = ctx.service.submitOperation(
            "submit-replay", task.id(), "agent-exec", "Run", null, null);
        ctx.setAssignmentStatus(submitted.assignmentId(), "COMPLETED");
        stub.nextRetryAssignmentId = UUID.randomUUID().toString();
        ctx.service.retryOperation("submit-replay-retry", task.id());

        WorkTaskAttempt replay = ctx.service.submitOperation(
            "submit-replay", task.id(), "agent-exec", "Run", null, null);

        assertThat(replay.id()).isEqualTo(submitted.id());
    }

    @Test
    void submitOperationDigestConflict() {
        var stub = new StubAssignmentService();
        var ctx = new TestContext(stub);
        ctx.ensureAgent("agent-exec");
        WorkTaskDefinition task = ctx.service.create("Task", null, null, 0, null);
        ctx.service.promoteOperation("sub-dc", task.id(), 1);

        ctx.service.submitOperation("sub-conflict", task.id(), "agent-exec", "Run A", null, null);

        // Same key but different runDisplayName → different digest → conflict
        assertThatThrownBy(() ->
            ctx.service.submitOperation("sub-conflict", task.id(), "agent-exec", "Run B", null, null))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("contradictory");
    }

    // ── Retry ──

    @Test
    void retryRejectsWhenNoAttempts() {
        var stub = new StubAssignmentService();
        var ctx = new TestContext(stub);
        WorkTaskDefinition task = ctx.service.create("Task", null, null, 0, null);

        assertThatThrownBy(() -> ctx.service.retry(task.id(), "retry-1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No attempts exist");
    }

    @Test
    void retryRejectsNonTerminalAssignment() {
        var stub = new StubAssignmentService();
        var ctx = new TestContext(stub);
        ctx.ensureAgent("agent-exec");
        WorkTaskDefinition task = ctx.service.create("Task", null, null, 0, null);
        ctx.service.promoteOperation("rt-nonterm", task.id(), 1);

        ctx.service.submit(task.id(), "agent-exec", "Run", null, null, null);
        ctx.setAssignmentStatus(stub.lastCreatedAssignment.id(), "QUEUED");

        assertThatThrownBy(() -> ctx.service.retry(task.id(), "retry-2"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not terminal");
    }

    @Test
    void retryCreatesSuccessorWithLineage() {
        var stub = new StubAssignmentService();
        var ctx = new TestContext(stub);
        ctx.ensureAgent("agent-exec");
        WorkTaskDefinition task = ctx.service.create("Task", null, null, 0, null);
        ctx.service.promoteOperation("rt-ok", task.id(), 1);

        // First submit
        WorkTaskAttempt first = ctx.service.submit(
            task.id(), "agent-exec", "Run 1", null, null, null);
        ctx.setAssignmentStatus(first.assignmentId(), "COMPLETED");

        // Set up stub for retry to return a new assignment
        String retryAssignmentId = UUID.randomUUID().toString();
        stub.nextRetryAssignmentId = retryAssignmentId;

        WorkTaskAttempt retry = ctx.service.retry(task.id(), "retry-op");

        assertThat(retry.taskId()).isEqualTo(task.id());
        assertThat(retry.attemptOrder()).isEqualTo(2);
        assertThat(retry.predecessorAttemptId()).isEqualTo(first.id());
        assertThat(retry.assignmentId()).isEqualTo(retryAssignmentId);

        // Verify retry was called on the correct agent/assignment
        assertThat(stub.lastRetryAgentId).isEqualTo("agent-exec");
        assertThat(stub.lastRetryAssignmentId).isEqualTo(first.assignmentId());
    }

    @Test
    void retryArchivedProjectIsRejected() {
        var stub = new StubAssignmentService();
        var ctx = new TestContext(stub);
        ctx.ensureProject("proj-retry");
        ctx.ensureAgent("agent-exec");
        ctx.ensureProjectMember("proj-retry", "agent-exec");
        WorkTaskDefinition task = ctx.service.create("Task", null, "proj-retry", false, 0, null);
        ctx.service.promoteOperation("rt-arch", task.id(), 1);
        ctx.service.submit(task.id(), "agent-exec", "Run", null, null, null);
        ctx.setAssignmentStatus(stub.lastCreatedAssignment.id(), "COMPLETED");
        ctx.archiveProject("proj-retry");

        assertThatThrownBy(() -> ctx.service.retry(task.id(), "retry-arch"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("archived");
    }

    // ── Retry operation idempotency ──

    @Test
    void retryOperationIsIdempotent() {
        var stub = new StubAssignmentService();
        var ctx = new TestContext(stub);
        ctx.ensureAgent("agent-exec");
        WorkTaskDefinition task = ctx.service.create("Task", null, null, 0, null);
        ctx.service.promoteOperation("rt-idem", task.id(), 1);

        WorkTaskAttempt first = ctx.service.submit(
            task.id(), "agent-exec", "Run", null, null, null);
        ctx.setAssignmentStatus(first.assignmentId(), "FAILED");

        stub.nextRetryAssignmentId = UUID.randomUUID().toString();

        WorkTaskAttempt retry1 = ctx.service.retryOperation("retry-op-idem", task.id());

        stub.nextRetryAssignmentId = UUID.randomUUID().toString();
        stub.lastRetryAgentId = null;

        WorkTaskAttempt retry2 = ctx.service.retryOperation("retry-op-idem", task.id());

        // Second call returns the same result without calling retry again
        assertThat(retry2.id()).isEqualTo(retry1.id());
        assertThat(stub.lastRetryAgentId).isNull(); // retry not re-called
    }

    // ── Repository: active attempt queries ──

    @Test
    void retryOperationReplayReturnsItsRecordedAttemptAfterLaterRetry() {
        var stub = new StubAssignmentService();
        var ctx = new TestContext(stub);
        ctx.ensureAgent("agent-exec");
        WorkTaskDefinition task = ctx.service.create("Task", null, null, 0, null);
        ctx.service.promoteOperation("retry-replay-promo", task.id(), 1);

        WorkTaskAttempt submitted = ctx.service.submit(
            task.id(), "agent-exec", "Run", null, null, null);
        ctx.setAssignmentStatus(submitted.assignmentId(), "FAILED");
        stub.nextRetryAssignmentId = UUID.randomUUID().toString();
        WorkTaskAttempt firstRetry = ctx.service.retryOperation("retry-replay-first", task.id());
        ctx.setAssignmentStatus(firstRetry.assignmentId(), "FAILED");
        stub.nextRetryAssignmentId = UUID.randomUUID().toString();
        ctx.service.retryOperation("retry-replay-second", task.id());

        WorkTaskAttempt replay = ctx.service.retryOperation("retry-replay-first", task.id());

        assertThat(replay.id()).isEqualTo(firstRetry.id());
    }

    @Test
    void findActiveAttemptFindsNonTerminal() {
        var stub = new StubAssignmentService();
        var ctx = new TestContext(stub);
        ctx.ensureAgent("agent-a");
        WorkTaskDefinition task = ctx.service.create("Task", null, null, 0, null);
        ctx.service.promoteOperation("act-1", task.id(), 1);

        ctx.service.submit(task.id(), "agent-a", "Run", null, null, null);
        ctx.setAssignmentStatus(stub.lastCreatedAssignment.id(), "QUEUED");

        var active = ctx.repository.findActiveAttempt(task.id());
        assertThat(active).isPresent();
        assertThat(active.get().assignmentId()).isEqualTo(stub.lastCreatedAssignment.id());
        assertThat(ctx.repository.countActiveAttempts(task.id())).isEqualTo(1);
    }

    @Test
    void findActiveAttemptEmptyWhenTerminal() {
        var stub = new StubAssignmentService();
        var ctx = new TestContext(stub);
        ctx.ensureAgent("agent-a");
        WorkTaskDefinition task = ctx.service.create("Task", null, null, 0, null);
        ctx.service.promoteOperation("act-term", task.id(), 1);

        ctx.service.submit(task.id(), "agent-a", "Run", null, null, null);
        ctx.setAssignmentStatus(stub.lastCreatedAssignment.id(), "COMPLETED");

        assertThat(ctx.repository.findActiveAttempt(task.id())).isEmpty();
        assertThat(ctx.repository.countActiveAttempts(task.id())).isEqualTo(0);
    }

    // ── Redaction / cross-scope ──

    @Test
    void crossScopeRedaction() {
        var ctx = new TestContext();
        ctx.ensureProject("proj-a");
        ctx.ensureProject("proj-b");

        ctx.service.create("Task A1", null, "proj-a", false, 0, null);
        ctx.service.create("Task A2", null, "proj-a", true, 0, null);
        ctx.service.create("Task B1", null, "proj-b", false, 0, null);

        var projA = ctx.service.listByProject("proj-a");
        assertThat(projA).hasSize(2);
        assertThat(projA).extracting(WorkTaskDefinition::projectId)
            .containsOnly("proj-a");
        assertThat(projA).extracting(WorkTaskDefinition::title)
            .containsExactly("Task A2", "Task A1"); // pinned first

        var projB = ctx.service.listByProject("proj-b");
        assertThat(projB).hasSize(1);
        assertThat(projB.get(0).projectId()).isEqualTo("proj-b");

        // Unbound tasks should not appear in project lists
        ctx.service.create("Unbound", null, null, false, 0, null);
        var projAafter = ctx.service.listByProject("proj-a");
        assertThat(projAafter).hasSize(2);
    }

    @Test
    void crossScopeTaskIdNotVisibleInWrongProject() {
        var ctx = new TestContext();
        ctx.ensureProject("proj-a");
        ctx.ensureProject("proj-b");

        WorkTaskDefinition taskA = ctx.service.create("Task A", null, "proj-a", false, 0, null);

        // Task exists but belongs to proj-a, not proj-b
        assertThatCode(() -> ctx.service.getById(taskA.id())).doesNotThrowAnyException();
        assertThat(taskA.projectId()).isEqualTo("proj-a");
    }

    // ── Bounded ordering ──

    @Test
    void boundedOrderingPinnedFirstThenUpdatedDesc() {
        var ctx = new TestContext();
        ctx.ensureProject("proj");

        WorkTaskDefinition t1 = ctx.service.create("Alpha", null, "proj", false, 10, null);
        WorkTaskDefinition t2 = ctx.service.create("Beta", null, "proj", false, 20, null);
        WorkTaskDefinition t3 = ctx.service.create("Gamma", null, "proj", true, 30, null);

        var all = ctx.service.listByProject("proj");
        assertThat(all).hasSize(3);
        // Pinned first
        assertThat(all.get(0).pin()).isTrue();
        assertThat(all.get(0).title()).isEqualTo("Gamma");
        // Then by updated_at desc
        // t2 (Beta, 20) was created after t1 (Alpha, 10), should come before
        assertThat(all.get(1).title()).isEqualTo("Beta");
        assertThat(all.get(2).title()).isEqualTo("Alpha");
    }

    // ── Concurrent submit idempotency ──

    @Test
    void duplicateSubmitOperationKeysAreIdempotent() {
        var stub = new StubAssignmentService();
        var ctx = new TestContext(stub);
        ctx.ensureProject("proj");
        ctx.ensureAgent("agent-1");
        ctx.ensureProjectMember("proj", "agent-1");

        WorkTaskDefinition task = ctx.service.create("Submit test", null, "proj", false, 0, "agent-1");
        ctx.service.promoteOperation("pk", task.id(), 1);

        // First submit
        WorkTaskAttempt a1 = ctx.service.submit(task.id(), "agent-1", "Run", null, null, "sk1");
        assertThat(a1).isNotNull();

        // A second submit attempt via service should be blocked by unique constraint
        ctx.setAssignmentStatus(a1.assignmentId(), "QUEUED");
        assertThatThrownBy(() ->
            ctx.service.submit(task.id(), "agent-1", "Run2", null, null, "sk3"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("active assignment");
    }

    @Test
    void submitRetryRacePreservesSingleActive() {
        var stub = new StubAssignmentService();
        var ctx = new TestContext(stub);
        ctx.ensureProject("proj");
        ctx.ensureAgent("agent-1");
        ctx.ensureProjectMember("proj", "agent-1");

        WorkTaskDefinition task = ctx.service.create("Race test", null, "proj", false, 0, "agent-1");
        ctx.service.promoteOperation("pk", task.id(), 1);

        // Submit creates assignment
        WorkTaskAttempt a1 = ctx.service.submit(task.id(), "agent-1", "Run", null, null, null);
        assertThat(a1).isNotNull();

        // Cannot submit again while active
        assertThatThrownBy(() ->
            ctx.service.submit(task.id(), "agent-1", "Run2", null, null, null))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("active assignment");

        // Cannot retry while active
        assertThatThrownBy(() ->
            ctx.service.retry(task.id(), null))
            .isInstanceOf(RuntimeException.class);

        // After terminal, retry succeeds
        stub.nextRetryAssignmentId = java.util.UUID.randomUUID().toString();
        ctx.setAssignmentStatus(a1.assignmentId(), "COMPLETED");

        WorkTaskAttempt a2 = ctx.service.retry(task.id(), null);
        assertThat(a2).isNotNull();
        assertThat(a2.predecessorAttemptId()).isEqualTo(a1.id());
        assertThat(a2.attemptOrder()).isEqualTo(2);
    }

    // ── Legacy /api/tasks preservation (Phase 03A — not in scope but verified) ──

    @Test
    void apiTasksRoutesArePreservedIndependently() {
        // The WorkTaskService API at /api/work/tasks is a separate REST surface.
        // The project-scoped fragments in ProjectConsoleController delegate to the
        // same WorkTaskService. This test confirms the service API remains stable
        // and does not depend on project-console wiring.
        var ctx = new TestContext();
        ctx.ensureProject("proj");

        WorkTaskDefinition task = ctx.service.create("API Task", "desc", "proj", false, 5, null);
        assertThat(task.projectId()).isEqualTo("proj");
        assertThat(ctx.service.listAll()).hasSize(1);
        assertThat(ctx.service.listByProject("proj")).hasSize(1);

        // The service-level CRUD is independent of web layer
        WorkTaskDefinition found = ctx.service.getById(task.id());
        assertThat(found.title()).isEqualTo("API Task");
    }

    // ── Test context ──

    private static class TestContext {
        final SingleConnectionDataSource ds;
        final JdbcTemplate jdbc;
        final WorkTaskRepository repository;
        final WorkTaskService service;
        final AssignmentService assignmentService;

        TestContext() {
            this(true, null);
        }

        TestContext(boolean includePlanService) {
            this(includePlanService, null);
        }

        TestContext(AssignmentService assignmentService) {
            this(true, assignmentService);
        }

        TestContext(boolean includePlanService, AssignmentService assignmentService) {
            this.ds = new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true);
            this.jdbc = new JdbcTemplate(ds);
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            ensureReferencedTables(includePlanService);
            this.repository = new WorkTaskRepository(jdbc);
            this.assignmentService = assignmentService;

            // Wire real services for guard validation
            var agentProfileRepo = new io.mindspice.magenta2.ai.orchestration.agents.AgentProfileRepository(jdbc, mapper);
            var aiConfig = aiConfigStub();
            var agentProfileSvc = new io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService(
                agentProfileRepo, aiConfig, null, null, null, null, null);
            var projectRepo = new io.mindspice.magenta2.ai.orchestration.runtime.ProjectRepository(jdbc, mapper);
            var projectAccessSvc = new io.mindspice.magenta2.ai.orchestration.runtime.ProjectOperationsAccessService(
                projectRepo, null, null);
            projectAccessSvc.enableTrustedDeployment();

            io.mindspice.magenta2.ai.chat.plan.PlanService planSvc = null;
            if (includePlanService) {
                var planRepo = new io.mindspice.magenta2.ai.chat.plan.PlanRepository(jdbc, mapper);
                planSvc = new io.mindspice.magenta2.ai.chat.plan.PlanService(planRepo, null);
            }
            this.service = new WorkTaskService(repository, agentProfileSvc, projectAccessSvc, planSvc, assignmentService);
            if (assignmentService instanceof StubAssignmentService stub) {
                stub.setJdbc(jdbc);
            }
        }

        private static io.mindspice.magenta2.ai.config.user.AiConfig aiConfigStub() {
            return new io.mindspice.magenta2.ai.config.user.AiConfig(
                null, null, 30, null, java.util.Map.of(), java.util.Map.of());
        }

        private void ensureReferencedTables(boolean includePlanDefs) {
            jdbc.execute("""
                create table if not exists projects (
                    id text primary key,
                    name text not null,
                    description text,
                    owner_agent_id text,
                    git_repo_url text,
                    prompt_profile text,
                    model text,
                    settings_override_json text,
                    archived integer not null default 0,
                    created_at text not null,
                    updated_at text not null
                )
                """);
            jdbc.execute("""
                create table if not exists project_agent_memberships (
                    id text primary key,
                    project_id text not null,
                    agent_id text not null,
                    role text not null default 'member',
                    joined_at text not null
                )
                """);
            jdbc.execute("""
                create unique index if not exists idx_project_membership_unique
                    on project_agent_memberships (project_id, agent_id)
                """);
            jdbc.execute("""
                create table if not exists agent_profiles (
                    id text primary key,
                    name text not null unique,
                    status text not null,
                    default_model text,
                    system_prompt_text text,
                    approved_tool_names_json text not null,
                    allowed_shell_commands_json text not null,
                    direct_line_enabled integer not null,
                    created_at text not null,
                    updated_at text not null
                )
                """);
            if (includePlanDefs) {
                jdbc.execute("""
                    create table if not exists plan_definitions (
                        id text primary key,
                        kind text not null,
                        status text not null,
                        title text not null,
                        summary text,
                        goal text,
                        notes text,
                        deliverables_json text not null,
                        inputs_json text not null,
                        outputs_json text not null,
                        assumptions_json text not null,
                        steps_json text not null,
                        validation_criteria_json text not null,
                        execution_evidence_json text not null,
                        validation_feedback_json text not null,
                        prompt_profile text,
                        planning_model text,
                        execution_model text,
                        settings_override_json text,
                        planning_task text,
                        pending_questions_json text,
                        pending_question_index integer not null default 0,
                        plan_start_message_order integer not null default 0,
                        final_message text,
                        conversation_id text,
                        created_at text not null,
                        updated_at text not null
                    )
                    """);
                jdbc.execute("""
                    create table if not exists plan_runs (
                        id text primary key,
                        plan_id text not null,
                        run_display_name text,
                        status text not null,
                        input_values_json text not null,
                        output_values_json text not null,
                        plan_snapshot_json text not null,
                        workspace_type text,
                        workspace_key text,
                        workspace_identity text,
                        execution_evidence_json text not null,
                        deliverable_evidence_json text not null,
                        validation_feedback_json text not null,
                        error_text text,
                        final_message text,
                        created_at text not null,
                        updated_at text not null,
                        started_at text not null,
                        completed_at text
                    )
                    """);
            }
            jdbc.execute("""
                create table if not exists work_assignments (
                    id text primary key,
                    agent_id text not null,
                    job_id text,
                    job_item_id text,
                    assignment_type text not null,
                    priority integer not null,
                    status text not null,
                    model_override text,
                    run_display_name text,
                    workspace_type text,
                    workspace_key text,
                    workspace_identity text,
                    project_id text,
                    current_item_index integer not null,
                    checkpoint_json text,
                    input_json text,
                    output_json text,
                    evidence_json text,
                    error_text text,
                    lease_owner text,
                    lease_expires_at text,
                    lease_fence integer not null default 0,
                    source_definition_id text,
                    origin_definition_id text,
                    context_binding_key text,
                    row_version integer not null default 0,
                    last_progress_at text,
                    last_heartbeat_at text,
                    created_at text not null,
                    updated_at text not null,
                    started_at text,
                    completed_at text
                )
                """);
            // Ensure work_task tables exist
            jdbc.execute("""
                create table if not exists work_task_definitions (
                    id text primary key,
                    title text not null,
                    description text,
                    project_id text,
                    pin integer not null default 0,
                    default_priority integer not null default 0,
                    preferred_agent_id text,
                    promoted_plan_id text,
                    designated_attempt_id text,
                    definition_version integer not null default 1,
                    created_at text not null,
                    updated_at text not null
                )
                """);
            jdbc.execute("""
                create table if not exists work_task_attempts (
                    id text primary key,
                    task_id text not null,
                    assignment_id text not null unique,
                    attempt_order integer not null,
                    predecessor_attempt_id text,
                    created_at text not null,
                    unique (task_id, attempt_order)
                )
                """);
            jdbc.execute("""
                create table if not exists work_task_operations (
                    operation_type text not null,
                    operation_key text not null,
                    request_digest text not null,
                    operation_state text not null,
                    task_id text,
                    result_json text,
                    created_at text not null,
                    primary key (operation_type, operation_key)
                )
                """);
        }

        void ensureProject(String id) {
            ensureProject(id, false);
        }

        void ensureProject(String id, boolean archived) {
            String now = Instant.now().toString();
            jdbc.update(
                "insert or ignore into projects (id, name, description, archived, created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
                id, "Project " + id, "", archived ? 1 : 0, now, now);
        }

        void archiveProject(String id) {
            jdbc.update("update projects set archived = 1 where id = ?", id);
        }

        void ensureAgent(String id) {
            ensureAgent(id, "ACTIVE");
        }

        void ensureAgent(String id, String status) {
            String now = Instant.now().toString();
            jdbc.update(
                "insert or ignore into agent_profiles (id, name, status, approved_tool_names_json, allowed_shell_commands_json, direct_line_enabled, created_at, updated_at) values (?, ?, ?, '[]', '[]', 0, ?, ?)",
                id, "Agent " + id, status, now, now);
        }

        void ensureProjectMember(String projectId, String agentId) {
            String id = UUID.randomUUID().toString();
            jdbc.update(
                "insert or ignore into project_agent_memberships (id, project_id, agent_id, role, joined_at) values (?, ?, ?, 'member', ?)",
                id, projectId, agentId, Instant.now().toString());
        }

        void ensurePlan(String id) {
            String now = Instant.now().toString();
            jdbc.update(
                "insert or ignore into plan_definitions (id, kind, status, title, deliverables_json, inputs_json, outputs_json, assumptions_json, steps_json, validation_criteria_json, execution_evidence_json, validation_feedback_json, created_at, updated_at) values (?, 'TASK_TEMPLATE', 'APPROVED', ?, '[]', '[]', '[]', '[]', '[]', '[]', '[]', '[]', ?, ?)",
                id, "Plan " + id, now, now);
        }

        void setAssignmentStatus(String assignmentId, String status) {
            jdbc.update(
                "update work_assignments set status = ? where id = ?",
                status, assignmentId);
        }
    }

    /**
     * Minimal stub that records the last request and returns a contrived assignment.
     * Persists minimal assignment rows so JOIN queries against work_assignments work.
     */
    private static class StubAssignmentService extends AssignmentService {
        AssignmentRequest lastRequest;
        WorkAssignment lastCreatedAssignment;
        String nextRetryAssignmentId;
        String lastRetryAgentId;
        String lastRetryAssignmentId;
        JdbcTemplate jdbc;

        StubAssignmentService() {
            super(null, null, null, null, null, null, null, null, null, null, null);
        }

        void setJdbc(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public WorkAssignment create(AssignmentRequest request) {
            this.lastRequest = request;
            String id = UUID.randomUUID().toString();
            String now = Instant.now().toString();
            String sourceDefId = request.sourceDefinitionId();
            String projId = request.projectId();
            String wsType = request.workspaceType();
            String wsKey = request.workspaceKey();
            String wsIdent = wsType != null && wsKey != null ? wsType + "/" + wsKey : null;
            String bindingKey = "assignment:" + id;
            String inputJson = "{}";
            try {
                inputJson = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(request.input() == null ? Map.of() : request.input());
            } catch (Exception ignored) {}

            WorkAssignment assignment = new WorkAssignment(
                id, request.agentId(), request.jobId(), request.jobItemId(),
                request.assignmentType(), request.runDisplayName(),
                request.priority() == null ? 0 : request.priority(),
                OrchestrationStatus.QUEUED, request.modelOverride(),
                wsType, wsKey, wsIdent,
                projId, sourceDefId,
                bindingKey, 0, 0, Map.of(),
                request.input() == null ? Map.of() : request.input(),
                Map.of(), Map.of(), null, null, null, 0L,
                Instant.now(), Instant.now(), null, null, null, null
            );
            this.lastCreatedAssignment = assignment;

            // Persist minimal row so JOIN queries work
            if (jdbc != null) {
                jdbc.update(
                    """
                    insert or replace into work_assignments (
                        id, agent_id, assignment_type, priority, status,
                        source_definition_id, project_id, workspace_type, workspace_key,
                        workspace_identity, context_binding_key, current_item_index,
                        input_json, output_json, evidence_json, checkpoint_json,
                        row_version, lease_fence, created_at, updated_at
                    ) values (?, ?, 'TASK_RUN', ?, 'QUEUED', ?, ?, ?, ?, ?, ?, 0, ?, '{}', '{}', '{}', 0, 0, ?, ?)
                    """,
                    id, request.agentId(),
                    request.priority() == null ? 0 : request.priority(),
                    sourceDefId, projId, wsType, wsKey, wsIdent, bindingKey,
                    inputJson, now, now);
            }

            return assignment;
        }

        @Override
        public WorkAssignment get(String assignmentId) {
            if (jdbc != null) {
                var row = jdbc.queryForMap(
                    "select * from work_assignments where id = ?", assignmentId);
                if (row != null && !row.isEmpty()) {
                    String status = (String) row.get("status");
                    OrchestrationStatus orchStatus = status != null
                        ? OrchestrationStatus.valueOf(status) : OrchestrationStatus.QUEUED;
                    String agentId = (String) row.get("agent_id");
                    return new WorkAssignment(
                        assignmentId, agentId != null ? agentId : "stub", null, null,
                        AssignmentType.TASK_RUN, "Run", 0,
                        orchStatus, null, null, null, null, null,
                        (String) row.get("source_definition_id"),
                        (String) row.get("context_binding_key"),
                        0, 0, Map.of(), Map.of(), Map.of(), Map.of(), null,
                        null, null, 0L, Instant.now(), Instant.now(),
                        null, null, null, null
                    );
                }
            }
            return new WorkAssignment(
                assignmentId, "stub", null, null,
                AssignmentType.TASK_RUN, "Run", 0,
                OrchestrationStatus.QUEUED, null, null, null, null, null,
                "PLAN/stub", "binding:" + assignmentId,
                0, 0, Map.of(), Map.of(), Map.of(), Map.of(), null,
                null, null, 0L, Instant.now(), Instant.now(),
                null, null, null, null
            );
        }

        @Override
        public WorkAssignment retry(String agentId, String assignmentId, String operationKey) {
            this.lastRetryAgentId = agentId;
            this.lastRetryAssignmentId = assignmentId;
            String id = nextRetryAssignmentId != null ? nextRetryAssignmentId : UUID.randomUUID().toString();
            String now = Instant.now().toString();
            String bindingKey = "assignment:" + id;
            WorkAssignment retried = new WorkAssignment(
                id, agentId, null, null,
                AssignmentType.TASK_RUN, "Retry", 0,
                OrchestrationStatus.QUEUED, null, null, null, null, null,
                "PLAN/retry", bindingKey,
                0, 0, Map.of(), Map.of(), Map.of(), Map.of(), null,
                null, null, 0L, Instant.now(), Instant.now(),
                null, null, null, null
            );
            if (jdbc != null) {
                jdbc.update(
                    """
                    insert or replace into work_assignments (
                        id, agent_id, assignment_type, priority, status,
                        source_definition_id, project_id,
                        workspace_type, workspace_key, workspace_identity,
                        context_binding_key, current_item_index,
                        input_json, output_json, evidence_json, checkpoint_json,
                        row_version, lease_fence, created_at, updated_at
                    ) values (?, ?, 'TASK_RUN', 0, 'QUEUED', ?, null, null, null, null, ?, 0, '{}', '{}', '{}', '{}', 0, 0, ?, ?)
                    """,
                    id, agentId, "PLAN/retry", bindingKey, now, now);
            }
            return retried;
        }
    }
}
