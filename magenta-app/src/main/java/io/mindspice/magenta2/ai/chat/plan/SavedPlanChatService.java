package io.mindspice.magenta2.ai.chat.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class SavedPlanChatService {
    private static final String OPENING_TASK = "saved_plan_opening_questions";
    private static final String SEED_TASK = "synthesize_saved_plan_seed";
    private static final String CONTINUE_TASK = "continue_saved_plan_chat";
    private static final String RESUME_TASK = "saved_plan_resume_chat";
    private static final String DRAFT_RESUME_QUESTION = "Any details you want to provide before continuing?";
    private static final String CHANGE_REQUEST_QUESTION = "What do you need to change in this plan?";
    private static final String CONTROLLED_FOLLOW_UP =
        "What detail should Magenta clarify or refine before this saved plan is ready for approval?";
    private static final String READY_MESSAGE = "Saved plan draft is ready for approval.";

    private static final List<String> OPENING_QUESTIONS = List.of(
        "Does this saved plan need runtime inputs? Include field names, types, required flags, array flags, schema/examples, or say \"no inputs\".",
        "What is the goal?",
        "What are the high-level deliverables? Outputs are asked next.",
        "What specific structured outputs should this saved plan produce for workflow chaining or downstream use? Include field names, types, required flags, array flags, schema/examples, or say \"no outputs\"."
    );

    private final PlanService planService;
    private final PlanChatRepository repository;
    private final SavedPlanModelClient modelClient;

    public SavedPlanChatService(PlanService planService, PlanChatRepository repository) {
        this(planService, repository, null);
    }

    @Autowired
    public SavedPlanChatService(
        PlanService planService,
        PlanChatRepository repository,
        @Autowired(required = false) SavedPlanModelClient modelClient
    ) {
        this.planService = planService;
        this.repository = repository;
        this.modelClient = modelClient;
    }

    public SavedPlanChatState create() {
        return create("New Plan Chat");
    }

    public SavedPlanChatState create(String title) {
        String resolvedTitle = StringUtils.hasText(title) ? title.trim() : "Untitled Plan";
        PlanDefinition plan = planService.saveTask(new PlanDefinition(
            UUID.randomUUID().toString(), PlanKind.TASK_TEMPLATE, PlanStatus.DRAFT,
            resolvedTitle, null, null, null,
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), WorkTypeProfile.CODING_CENTRIC.name(),
            null, null, null, OPENING_TASK,
            OPENING_QUESTIONS, 0, 0, null, null, null, null
        ));
        repository.append(plan.id(), "assistant", plan.currentQuestion());
        return state(plan.id());
    }

    public SavedPlanChatState start(String planId, String instruction) {
        PlanDefinition plan = planService.getTask(planId);
        List<PlanChatMessage> messages = repository.findByPlanId(plan.id());
        if (plan.hasPendingQuestion() && StringUtils.hasText(instruction)) {
            repository.append(plan.id(), "user", instruction.trim());
            String modelMessage = userTurnMessage(plan, instruction.trim());
            PlanDefinition updated = planService.saveTask(plan
                .withPlanningTask(CONTINUE_TASK)
                .withPendingQuestions(List.of(), 0));
            runModelTurn(updated.id(), modelUserMessage(updated.id(), modelMessage));
            return state(updated.id());
        }
        if (plan.hasPendingQuestion()) {
            String question = plan.currentQuestion();
            if (messages.isEmpty() || !lastMessageIs(messages, "assistant", question)) {
                repository.append(plan.id(), "assistant", question);
            }
            return state(plan.id());
        }
        if (messages.isEmpty()) {
            String question = resumeQuestion(plan);
            PlanDefinition seeded = planService.saveTask(plan
                .withPlanningTask(RESUME_TASK)
                .withPendingQuestions(List.of(question), 0));
            repository.append(seeded.id(), "assistant", seeded.currentQuestion());
            return new SavedPlanChatState(planService.getTask(seeded.id()), repository.findByPlanId(seeded.id()), question);
        }
        if (StringUtils.hasText(instruction)) {
            repository.append(plan.id(), "user", instruction.trim());
            PlanDefinition updated = planService.saveTask(plan
                .withPlanningTask(CONTINUE_TASK)
                .withPendingQuestions(List.of(), 0));
            runModelTurn(updated.id(), modelUserMessage(updated.id(), userTurnMessage(updated, instruction.trim())));
            return state(updated.id());
        }
        String question = resumeQuestion(plan);
        if (!StringUtils.hasText(instruction) && lastMessageIs(messages, "assistant", question)) {
            return new SavedPlanChatState(plan, messages, question);
        }
        planService.saveTask(plan
            .withPlanningTask(RESUME_TASK)
            .withPendingQuestions(List.of(question), 0));
        repository.append(plan.id(), "assistant", question);
        return new SavedPlanChatState(planService.getTask(plan.id()), repository.findByPlanId(plan.id()), question);
    }

    public SavedPlanChatState answer(String planId, String answer) {
        PlanDefinition plan = planService.getTask(planId);
        if (RESUME_TASK.equals(plan.planningTask()) && plan.hasPendingQuestion()) {
            repository.append(plan.id(), "user", answer);
            String modelMessage = userTurnMessage(plan, answer);
            PlanDefinition updated = planService.saveTask(plan
                .withPendingQuestions(List.of(), 0)
                .withPlanningTask(CONTINUE_TASK));
            runModelTurn(updated.id(), modelUserMessage(updated.id(), modelMessage));
            return state(updated.id());
        }
        if (!plan.hasPendingQuestion()) {
            repository.append(plan.id(), "user", answer);
            PlanDefinition updated = planService.saveTask(plan.withPlanningTask(CONTINUE_TASK));
            runModelTurn(updated.id(), modelUserMessage(updated.id(), userTurnMessage(updated, answer)));
            return state(plan.id());
        }
        int index = plan.pendingQuestionIndex();
        repository.append(plan.id(), "user", answer);
        if (OPENING_TASK.equals(plan.planningTask()) && index + 1 < OPENING_QUESTIONS.size()) {
            int nextIndex = index + 1;
            PlanDefinition updated = planService.saveTask(plan.withPendingQuestions(OPENING_QUESTIONS, nextIndex));
            repository.append(updated.id(), "assistant", updated.currentQuestion());
            return state(updated.id());
        }
        if (OPENING_TASK.equals(plan.planningTask())) {
            PlanDefinition updated = planService.saveTask(plan
                .withPendingQuestions(List.of(), 0)
                .withPlanningTask(SEED_TASK));
            runModelTurn(updated.id(), modelUserMessage(updated.id(), seedUserMessage(updated.id())));
            return state(updated.id());
        } else {
            String modelMessage = userTurnMessage(plan, answer);
            PlanDefinition updated = planService.saveTask(plan
                .withPendingQuestions(List.of(), 0)
                .withPlanningTask(CONTINUE_TASK));
            runModelTurn(updated.id(), modelUserMessage(updated.id(), modelMessage));
            return state(updated.id());
        }
    }

    public SavedPlanChatState message(String planId, String message) {
        PlanDefinition plan = planService.getTask(planId);
        repository.append(plan.id(), "user", message);
        PlanDefinition updated = planService.saveTask(plan
            .withPlanningTask(CONTINUE_TASK)
            .withPendingQuestions(List.of(), 0));
        runModelTurn(updated.id(), modelUserMessage(updated.id(), userTurnMessage(updated, message)));
        return state(plan.id());
    }

    public void appendEditorSaveContext(PlanDefinition before, PlanDefinition after) {
        if (before == null || after == null || !StringUtils.hasText(after.id())) {
            return;
        }
        if (repository.findByPlanId(after.id()).isEmpty()) {
            return;
        }
        String diff = editorDiff(before, after);
        if (StringUtils.hasText(diff)) {
            repository.append(after.id(), "system", "Saved editor updates: " + diff);
        }
    }

    public SavedPlanChatState state(String planId) {
        PlanDefinition plan = planService.getTask(planId);
        List<PlanChatMessage> messages = repository.findByPlanId(planId);
        return new SavedPlanChatState(plan, messages, promptQuestion(plan, messages));
    }

    public void deleteMessages(String planId) {
        repository.deleteByPlanId(planId);
    }

    private String resumeQuestion(PlanDefinition plan) {
        return isFinalized(plan.status()) ? CHANGE_REQUEST_QUESTION : DRAFT_RESUME_QUESTION;
    }

    private boolean isFinalized(PlanStatus status) {
        if (status == null) {
            return false;
        }
        String name = status.name();
        return "APPROVED".equals(name) || "SAVED_TASK".equals(name) || "COMPLETED".equals(name);
    }

    private boolean lastMessageIs(List<PlanChatMessage> messages, String role, String text) {
        if (messages.isEmpty()) {
            return false;
        }
        PlanChatMessage last = messages.get(messages.size() - 1);
        return role.equalsIgnoreCase(last.role()) && text.equals(last.text());
    }

    private String promptQuestion(PlanDefinition plan, List<PlanChatMessage> messages) {
        if (plan.hasPendingQuestion()) {
            return plan.currentQuestion();
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            PlanChatMessage message = messages.get(i);
            if (!"assistant".equalsIgnoreCase(message.role())) {
                continue;
            }
            if (DRAFT_RESUME_QUESTION.equals(message.text())) {
                return DRAFT_RESUME_QUESTION;
            }
            if (CHANGE_REQUEST_QUESTION.equals(message.text())) {
                return CHANGE_REQUEST_QUESTION;
            }
            return null;
        }
        return null;
    }

    private void runModelTurn(String planId, String userMessage) {
        if (modelClient == null) {
            PlanDefinition plan = planService.saveTask(planService.getTask(planId)
                .withPendingQuestions(List.of(CONTROLLED_FOLLOW_UP), 0)
                .withPlanningTask("saved_plan_model_unavailable"));
            repository.append(plan.id(), "assistant", plan.currentQuestion());
            return;
        }
        PlanDefinition before = planService.getTask(planId);
        modelClient.runTurn(planId, before.planningModel(), savedPlanSystemPrompt(before), userMessage);
        enforceTerminalState(planId);
    }

    private void enforceTerminalState(String planId) {
        PlanDefinition plan = planService.getTask(planId);
        if (plan.status() == PlanStatus.READY_FOR_APPROVAL) {
            if (!lastMessageIs(repository.findByPlanId(plan.id()), "assistant", READY_MESSAGE)) {
                repository.append(plan.id(), "assistant", READY_MESSAGE);
            }
            return;
        }
        if (plan.hasPendingQuestion()) {
            repository.append(plan.id(), "assistant", plan.currentQuestion());
            return;
        }
        PlanDefinition updated = planService.saveTask(plan
            .withPlanningTask("saved_plan_controlled_follow_up")
            .withPendingQuestions(List.of(CONTROLLED_FOLLOW_UP), 0));
        repository.append(updated.id(), "assistant", updated.currentQuestion());
    }

    private String savedPlanSystemPrompt(PlanDefinition plan) {
        StringBuilder builder = new StringBuilder("""
            You are Magenta drafting a saved /plans reusable task definition.

            Scope:
            - This turn is scoped only to the saved plan id in tool context.
            - Do not use /api/chat conversation memory, ai_chat_memory, chat session metadata, or any conversation id.
            - Use only saved_plan_* tools for draft mutation.

            Opening answers and chat messages are seed context, not final field values. Do not directly copy opening answers into goal, deliverables, inputs, or outputs. Synthesize concise plan fields from them.

            Required workflow:
            - Use saved_plan_update_fields for concise title, summary, and goal when the intent is clear.
            - Use saved_plan_put_item for typed runtime inputs, named outputs, deliverables, assumptions, steps, notes, and validation criteria.
            - Runtime inputs and named outputs must use field names, type, array, required, description, and schema when applicable.
            - Continue follow-up questioning with saved_plan_ask_user_questions until the draft is clear enough.
            - When the saved plan has goal, outputs or deliverables, execution steps, and validation criteria complete enough for review, call saved_plan_ready_for_approval.

            Terminal-state contract:
            - End every turn by queuing one to five pending questions with saved_plan_ask_user_questions, or by calling saved_plan_ready_for_approval.
            - Do not silently end with only free-form text, summaries, or analysis.
            - Prefer one focused follow-up question when that is enough.

            Current saved plan draft:
            """.stripIndent());
        appendValue(builder, "Plan id", plan.id());
        appendValue(builder, "Status", plan.status().name());
        appendValue(builder, "Current planning task", plan.planningTask());
        appendValue(builder, "Title", plan.title());
        appendValue(builder, "Summary", plan.summary());
        appendValue(builder, "Goal", plan.goal());
        appendList(builder, "Deliverables", plan.deliverables());
        appendList(builder, "Inputs", plan.inputs().stream().map(this::fieldSummary).toList());
        appendList(builder, "Outputs", plan.outputs().stream().map(this::fieldSummary).toList());
        appendList(builder, "Assumptions", plan.assumptions());
        appendList(builder, "Steps", plan.steps().stream().map(step -> step.order() + ". " + step.text()).toList());
        appendList(builder, "Validation criteria", plan.validationCriteria());
        return builder.toString().trim();
    }

    private String seedUserMessage(String planId) {
        List<PlanChatMessage> messages = repository.findByPlanId(planId);
        List<String> answers = messages.stream()
            .filter(message -> "user".equalsIgnoreCase(message.role()))
            .map(PlanChatMessage::text)
            .limit(OPENING_QUESTIONS.size())
            .toList();
        StringBuilder builder = new StringBuilder("""
            Use these saved-plan opening answers as model seed context only. They are not final field values and must not be copied directly into draft fields.

            Labeled opening answers:
            """.stripIndent());
        for (int i = 0; i < OPENING_QUESTIONS.size(); i++) {
            builder.append("\n")
                .append(i + 1)
                .append(". Question: ")
                .append(OPENING_QUESTIONS.get(i))
                .append("\nAnswer: ")
                .append(i < answers.size() ? answers.get(i) : "(missing)")
                .append("\n");
        }
        builder.append("\nSynthesize concise saved-plan fields and ask the next focused follow-up question if anything is missing.");
        return builder.toString().trim();
    }

    private String modelUserMessage(String planId, String instruction) {
        StringBuilder builder = new StringBuilder(instruction == null ? "" : instruction.trim());
        List<PlanChatMessage> messages = repository.findByPlanId(planId);
        if (!messages.isEmpty()) {
            builder.append("\n\nRecent saved-plan chat transcript, including manual editor change notices:");
            int start = Math.max(0, messages.size() - 12);
            for (PlanChatMessage message : messages.subList(start, messages.size())) {
                builder.append("\n")
                    .append(message.role())
                    .append(": ")
                    .append(message.text());
            }
        }
        return builder.toString().trim();
    }

    private String userTurnMessage(PlanDefinition plan, String userMessage) {
        StringBuilder builder = new StringBuilder();
        if (StringUtils.hasText(plan.currentQuestion())) {
            builder.append("The user answered saved-plan follow-up question:\nQuestion: ")
                .append(plan.currentQuestion())
                .append("\nAnswer: ")
                .append(userMessage)
                .append("\n");
        } else {
            builder.append("The user sent this saved-plan chat message:\n")
                .append(userMessage)
                .append("\n");
        }
        builder.append("\nTreat this as seed context for synthesis, not as direct field-copy instructions.");
        return builder.toString().trim();
    }

    private void appendValue(StringBuilder builder, String label, String value) {
        if (StringUtils.hasText(value)) {
            builder.append(label).append(": ").append(value.trim()).append("\n");
        }
    }

    private void appendList(StringBuilder builder, String label, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        builder.append(label).append(":\n");
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                builder.append("- ").append(value.trim()).append("\n");
            }
        }
    }

    private String fieldSummary(PlanFieldDefinition field) {
        StringBuilder builder = new StringBuilder();
        builder.append(field.name()).append(" (").append(field.type().wireName());
        if (field.array()) {
            builder.append("[]");
        }
        builder.append(field.required() ? ", required" : ", optional").append(")");
        if (StringUtils.hasText(field.description())) {
            builder.append(": ").append(field.description());
        }
        if (StringUtils.hasText(field.schema())) {
            builder.append(" schema=").append(field.schema());
        }
        return builder.toString();
    }

    private String editorDiff(PlanDefinition before, PlanDefinition after) {
        List<String> changes = new ArrayList<>();
        addChange(changes, "title", before.title(), after.title());
        addChange(changes, "summary", before.summary(), after.summary());
        addChange(changes, "goal", before.goal(), after.goal());
        addChange(changes, "notes", before.notes(), after.notes());
        addChange(changes, "manager type", before.promptProfile(), after.promptProfile());
        addChange(changes, "planning model", before.planningModel(), after.planningModel());
        addChange(changes, "execution model", before.executionModel(), after.executionModel());
        addChange(changes, "planning task", before.planningTask(), after.planningTask());
        addChange(changes, "final message", before.finalMessage(), after.finalMessage());
        addChange(changes, "settings override JSON", before.settingsOverrideJson(), after.settingsOverrideJson());
        addChange(changes, "deliverables", summarizeList(before.deliverables()), summarizeList(after.deliverables()));
        addChange(changes, "inputs", summarizeFields(before.inputs()), summarizeFields(after.inputs()));
        addChange(changes, "outputs", summarizeFields(before.outputs()), summarizeFields(after.outputs()));
        addChange(changes, "assumptions", summarizeList(before.assumptions()), summarizeList(after.assumptions()));
        addChange(changes, "steps", summarizeSteps(before.steps()), summarizeSteps(after.steps()));
        addChange(changes, "validation criteria", summarizeList(before.validationCriteria()), summarizeList(after.validationCriteria()));
        addChange(changes, "pending questions", summarizeList(before.pendingQuestions()), summarizeList(after.pendingQuestions()));
        return String.join("; ", changes);
    }

    private void addChange(List<String> changes, String field, String before, String after) {
        String oldValue = normalize(before);
        String newValue = normalize(after);
        if (!oldValue.equals(newValue)) {
            changes.add(field + " changed from " + quote(oldValue) + " to " + quote(newValue));
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private String quote(String value) {
        if (!StringUtils.hasText(value)) {
            return "(blank)";
        }
        String trimmed = value.length() > 80 ? value.substring(0, 77) + "..." : value;
        return "\"" + trimmed + "\"";
    }

    private String summarizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return String.join(" | ", values);
    }

    private String summarizeFields(List<PlanFieldDefinition> fields) {
        if (fields == null || fields.isEmpty()) {
            return "";
        }
        return fields.stream().map(this::fieldSummary).toList().toString();
    }

    private String summarizeSteps(List<PlanStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return "";
        }
        return steps.stream()
            .map(step -> step.order() + ". " + step.text())
            .toList()
            .toString();
    }

    public record SavedPlanChatState(
        PlanDefinition plan,
        List<PlanChatMessage> messages,
        String promptQuestion
    ) {
    }
}
