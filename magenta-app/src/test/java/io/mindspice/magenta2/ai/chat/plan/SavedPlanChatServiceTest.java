package io.mindspice.magenta2.ai.chat.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta2.ai.chat.repository.ChatMemoryRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class SavedPlanChatServiceTest {
    @Test
    void createSeedsNamedTaskTemplateDraft() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        PlanRepository planRepository = new PlanRepository(jdbcTemplate, new ObjectMapper());
        PlanService planService = new PlanService(
            planRepository,
            new ChatMemoryRepository(jdbcTemplate, new ObjectMapper())
        );
        SavedPlanChatService service = new SavedPlanChatService(planService, new PlanChatRepository(jdbcTemplate), null);

        SavedPlanChatService.SavedPlanChatState state = service.create("Named chat draft");

        assertThat(state.plan().kind()).isEqualTo(PlanKind.TASK_TEMPLATE);
        assertThat(state.plan().title()).isEqualTo("Named chat draft");
        assertThat(state.plan().planningTask()).isEqualTo("saved_plan_opening_questions");
        assertThat(state.promptQuestion()).contains("runtime inputs");
    }

    @Test
    void savedPlanChatStoresOpeningAnswersAsSeedContextWithoutDirectFieldCopies() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        PlanRepository planRepository = new PlanRepository(jdbcTemplate, new ObjectMapper());
        PlanService planService = new PlanService(
            planRepository,
            new ChatMemoryRepository(jdbcTemplate, new ObjectMapper())
        );
        CapturingModelClient modelClient = new CapturingModelClient();
        SavedPlanChatService service = new SavedPlanChatService(
            planService, new PlanChatRepository(jdbcTemplate), modelClient);

        SavedPlanChatService.SavedPlanChatState state = service.create();
        String planId = state.plan().id();

        assertThat(state.promptQuestion()).contains("runtime inputs");
        state = service.answer(planId, "week_start: string required\nsource_file: file_path optional");
        assertThat(state.promptQuestion()).isEqualTo("What is the goal?");
        state = service.answer(planId, "Prepare a weekly operations report.");
        assertThat(state.promptQuestion()).contains("high-level deliverables");
        state = service.answer(planId, "Markdown report\nUpdated management summary");
        assertThat(state.promptQuestion()).contains("structured outputs");
        state = service.answer(planId, "report_markdown: string required\nmetrics_json: json required");

        PlanDefinition plan = planService.getTask(planId);
        assertThat(plan.kind()).isEqualTo(PlanKind.TASK_TEMPLATE);
        assertThat(plan.title()).isEqualTo("New Plan Chat");
        assertThat(plan.goal()).isNull();
        assertThat(plan.inputs()).isEmpty();
        assertThat(plan.outputs()).isEmpty();
        assertThat(plan.deliverables()).isEmpty();
        assertThat(service.state(planId).messages()).hasSizeGreaterThanOrEqualTo(8);
        assertThat(modelClient.calls).hasSize(1);
        assertThat(modelClient.calls.getFirst().systemPrompt())
            .contains("Opening answers and chat messages are seed context, not final field values")
            .contains("Current planning task: synthesize_saved_plan_seed");
        assertThat(modelClient.calls.getFirst().userMessage())
            .contains("Labeled opening answers")
            .contains("Question: What is the goal?")
            .contains("Answer: Prepare a weekly operations report.")
            .contains("not final field values")
            .contains("must not be copied directly");
    }

    @Test
    void startWhileOpeningQuestionsArePendingKeepsCurrentScriptedQuestion() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        PlanRepository planRepository = new PlanRepository(jdbcTemplate, new ObjectMapper());
        PlanService planService = new PlanService(
            planRepository,
            new ChatMemoryRepository(jdbcTemplate, new ObjectMapper())
        );
        PlanChatRepository chatRepository = new PlanChatRepository(jdbcTemplate);
        SavedPlanChatService service = new SavedPlanChatService(planService, chatRepository, null);

        SavedPlanChatService.SavedPlanChatState state = service.create("Tabbed draft");
        String planId = state.plan().id();

        state = service.start(planId, null);
        assertThat(state.promptQuestion()).contains("runtime inputs");
        assertThat(chatRepository.findByPlanId(planId))
            .extracting(PlanChatMessage::text)
            .containsExactly(state.promptQuestion());

        state = service.answer(planId, "no inputs");
        assertThat(state.promptQuestion()).isEqualTo("What is the goal?");

        state = service.start(planId, null);
        assertThat(state.promptQuestion()).isEqualTo("What is the goal?");
        assertThat(chatRepository.findByPlanId(planId).getLast().text()).isEqualTo("What is the goal?");
    }

    @Test
    void startWithExistingDraftHistoryAppendsInstructionAndResumeQuestion() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        PlanRepository planRepository = new PlanRepository(jdbcTemplate, new ObjectMapper());
        PlanService planService = new PlanService(
            planRepository,
            new ChatMemoryRepository(jdbcTemplate, new ObjectMapper())
        );
        CapturingModelClient modelClient = new CapturingModelClient();
        SavedPlanChatService service = new SavedPlanChatService(
            planService, new PlanChatRepository(jdbcTemplate), modelClient);

        SavedPlanChatService.SavedPlanChatState state = service.create();
        String planId = state.plan().id();
        service.answer(planId, "no inputs");
        service.answer(planId, "Prepare weekly report");
        service.answer(planId, "Markdown summary");
        service.answer(planId, "no outputs");

        SavedPlanChatService.SavedPlanChatState resumed = service.start(
            planId,
            "goal updated; outputs updated"
        );

        List<PlanChatMessage> messages = resumed.messages();
        assertThat(messages.get(messages.size() - 2).role()).isEqualTo("user");
        assertThat(messages.get(messages.size() - 2).text())
            .isEqualTo("goal updated; outputs updated");
        assertThat(messages.getLast().role()).isEqualTo("assistant");
        assertThat(messages.getLast().text())
            .isEqualTo("What detail should Magenta clarify or refine before this saved plan is ready for approval?");
        assertThat(modelClient.calls).hasSize(2);
        assertThat(modelClient.calls.getLast().userMessage()).contains("goal updated; outputs updated");
    }

    @Test
    void answeringResumeQuestionDoesNotReuseOpeningInputParser() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        PlanRepository planRepository = new PlanRepository(jdbcTemplate, new ObjectMapper());
        PlanService planService = new PlanService(
            planRepository,
            new ChatMemoryRepository(jdbcTemplate, new ObjectMapper())
        );
        CapturingModelClient modelClient = new CapturingModelClient();
        SavedPlanChatService service = new SavedPlanChatService(
            planService, new PlanChatRepository(jdbcTemplate), modelClient);

        PlanDefinition draft = planService.saveTask(new PlanDefinition(
            "existing-plan", PlanKind.TASK_TEMPLATE, PlanStatus.DRAFT,
            "Draft", null, null, null,
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), WorkTypeProfile.CODING_CENTRIC.name(),
            null, null, null, null,
            List.of(), 0, 0, null, null, null, null
        ));
        SavedPlanChatService.SavedPlanChatState state = service.start(draft.id(), null);

        state = service.answer(draft.id(), "Focus this on API validation, not UI work.");

        PlanDefinition updated = planService.getTask(draft.id());
        assertThat(updated.inputs()).isEmpty();
        assertThat(updated.hasPendingQuestion()).isTrue();
        assertThat(state.promptQuestion()).isEqualTo("What detail should Magenta clarify or refine before this saved plan is ready for approval?");
        assertThat(state.messages().getLast().text()).isEqualTo("What detail should Magenta clarify or refine before this saved plan is ready for approval?");
        assertThat(modelClient.calls).hasSize(1);
        assertThat(modelClient.calls.getFirst().userMessage())
            .contains("Question: Any details you want to provide before continuing?")
            .contains("Answer: Focus this on API validation, not UI work.");
    }

    @Test
    void startWithApprovedHistoryUsesChangeRequestQuestion() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        PlanRepository planRepository = new PlanRepository(jdbcTemplate, new ObjectMapper());
        PlanService planService = new PlanService(
            planRepository,
            new ChatMemoryRepository(jdbcTemplate, new ObjectMapper())
        );
        SavedPlanChatService service = new SavedPlanChatService(planService, new PlanChatRepository(jdbcTemplate), null);

        PlanDefinition approved = planService.saveTask(new PlanDefinition(
            "approved-plan", PlanKind.TASK_TEMPLATE, PlanStatus.APPROVED,
            "Approved", null, null, null,
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), WorkTypeProfile.CODING_CENTRIC.name(),
            null, null, null, null,
            List.of(), 0, 0, null, null, null, null
        ));
        service.start(approved.id(), null);
        planService.saveTask(planService.getTask(approved.id()).withStatus(PlanStatus.APPROVED));

        SavedPlanChatService.SavedPlanChatState state = service.start(approved.id(), null);

        assertThat(state.promptQuestion()).isEqualTo("What do you need to change in this plan?");
        assertThat(state.messages().getLast().text()).isEqualTo("What do you need to change in this plan?");
        assertThat(service.state(approved.id()).promptQuestion()).isEqualTo("What do you need to change in this plan?");
    }

    @Test
    void appendEditorSaveContextWritesConciseDiffWhenHistoryExists() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        PlanRepository planRepository = new PlanRepository(jdbcTemplate, new ObjectMapper());
        PlanService planService = new PlanService(
            planRepository,
            new ChatMemoryRepository(jdbcTemplate, new ObjectMapper())
        );
        PlanChatRepository chatRepository = new PlanChatRepository(jdbcTemplate);
        CapturingModelClient modelClient = new CapturingModelClient();
        SavedPlanChatService service = new SavedPlanChatService(planService, chatRepository, modelClient);

        SavedPlanChatService.SavedPlanChatState state = service.create("Context plan");
        PlanDefinition before = state.plan();
        PlanDefinition after = before.withGoal("Updated goal")
            .withSummary("Updated summary")
            .withDeliverables(List.of("Updated deliverable"));

        service.appendEditorSaveContext(before, after);

        List<PlanChatMessage> messages = chatRepository.findByPlanId(before.id());
        assertThat(messages.getLast().role()).isEqualTo("system");
        assertThat(messages.getLast().text())
            .contains("Saved editor updates:")
            .contains("goal changed")
            .contains("summary changed")
            .contains("deliverables changed");
        assertThat(service.state(before.id()).promptQuestion()).contains("runtime inputs");

        service.start(before.id(), "Use the edited goal and summary.");

        assertThat(modelClient.calls).hasSize(1);
        assertThat(modelClient.calls.getFirst().userMessage())
            .contains("Recent saved-plan chat transcript")
            .contains("Saved editor updates:")
            .contains("goal changed")
            .contains("summary changed")
            .contains("deliverables changed");
    }

    @Test
    void savedPlanReadyForApprovalAcceptsDeliverableOnlyDraft() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        PlanRepository planRepository = new PlanRepository(jdbcTemplate, new ObjectMapper());
        PlanService planService = new PlanService(
            planRepository,
            new ChatMemoryRepository(jdbcTemplate, new ObjectMapper())
        );
        PlanDefinition draft = planService.saveTask(new PlanDefinition(
            "deliverable-only-plan", PlanKind.TASK_TEMPLATE, PlanStatus.DRAFT,
            "Deliverable Only", null, "Prepare a written report.", null,
            List.of("Written report"), List.of(), List.of(), List.of(),
            List.of(new PlanStep(1, "Draft and review the report.")),
            List.of("Report content has been reviewed."),
            List.of(), List.of(), WorkTypeProfile.CODING_CENTRIC.name(),
            null, null, null, "approval_readiness",
            List.of(), 0, 0, null, null, null, null
        ));

        PlanDefinition ready = planService.markSavedTaskReadyForApproval(draft.id());

        assertThat(ready.status()).isEqualTo(PlanStatus.READY_FOR_APPROVAL);
    }

    @Test
    void startDraftWithoutHistorySeedsDraftResumeQuestionDeterministically() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        PlanRepository planRepository = new PlanRepository(jdbcTemplate, new ObjectMapper());
        PlanService planService = new PlanService(
            planRepository,
            new ChatMemoryRepository(jdbcTemplate, new ObjectMapper())
        );
        SavedPlanChatService service = new SavedPlanChatService(planService, new PlanChatRepository(jdbcTemplate), null);

        PlanDefinition draft = planService.saveTask(new PlanDefinition(
            "existing-plan", PlanKind.TASK_TEMPLATE, PlanStatus.DRAFT,
            "Draft", null, null, null,
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), WorkTypeProfile.CODING_CENTRIC.name(),
            null, null, null, null,
            List.of(), 0, 0, null, null, null, null
        ));

        SavedPlanChatService.SavedPlanChatState state = service.start(draft.id(), null);

        assertThat(state.promptQuestion()).isEqualTo("Any details you want to provide before continuing?");
        assertThat(state.messages()).hasSize(1);
        assertThat(state.messages().getFirst().text()).isEqualTo("Any details you want to provide before continuing?");
    }

    private JdbcTemplate jdbcTemplate() {
        var dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        return new JdbcTemplate(dataSource);
    }

    private static class CapturingModelClient implements SavedPlanModelClient {
        private final List<ModelCall> calls = new java.util.ArrayList<>();

        @Override
        public void runTurn(String planId, String model, String systemPrompt, String userMessage) {
            calls.add(new ModelCall(planId, model, systemPrompt, userMessage));
        }
    }

    private record ModelCall(String planId, String model, String systemPrompt, String userMessage) {
    }
}
