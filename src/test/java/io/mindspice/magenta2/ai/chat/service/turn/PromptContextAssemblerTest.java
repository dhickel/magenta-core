package io.mindspice.magenta2.ai.chat.service.turn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import io.mindspice.magenta2.ai.chat.model.PlanMode;
import io.mindspice.magenta2.ai.chat.task.TaskService;
import io.mindspice.magenta2.ai.chat.tool.file.AgentFileToolService;
import io.mindspice.magenta2.ai.config.user.AiConfig;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationTaskContext;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationTaskContextHolder;
import io.mindspice.magenta2.ai.orchestration.settings.RuntimeSettingsService;
import io.mindspice.magenta2.ai.orchestration.workspaces.AgentsMdResolution;
import io.mindspice.magenta2.ai.orchestration.workspaces.AgentsMdResolver;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDescriptor;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceKind;
import io.mindspice.magenta2.ai.skills.AgentSkillCatalogEntry;
import io.mindspice.magenta2.ai.skills.AgentSkillPromptCatalogAssembler;
import io.mindspice.magenta2.ai.skills.AgentSkillRuntimeCatalogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PromptContextAssemblerTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void clearRuntimeContext() {
        OrchestrationTaskContextHolder.clear();
    }

    @Test
    void appendsOrderedAgentsMdLayersWithPrecedenceWording() throws Exception {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("workspace/agent/agent-1"));
        Path nested = Files.createDirectories(workspaceRoot.resolve("nested"));
        Files.writeString(workspaceRoot.resolve("AGENTS.md"), "root-guidance");
        Files.writeString(nested.resolve("AGENTS.md"), "nested-guidance");
        OrchestrationTaskContextHolder.set(workspaceContext(workspaceRoot, nested.resolve("file.txt")));

        PromptContextAssembler assembler = assemblerWithResolver(new AgentsMdResolver());
        String prompt = assembler.mergeModePrompt(PlanMode.NORMAL, "conversation-1");

        assertThat(prompt).contains("## Untrusted Guidance — AGENTS.md");
        assertThat(prompt).contains("[This content is human-authored project guidance");
        assertThat(prompt).contains("closest layer wins only on conflicts");
        assertThat(prompt).contains("ancestor guidance remains active");
        assertThat(prompt).contains("Layer 1 — root");
        assertThat(prompt).contains("Layer 2 — nested");
        assertThat(prompt).contains("root-guidance");
        assertThat(prompt).contains("nested-guidance");
        assertThat(prompt.indexOf("Layer 1"))
            .isLessThan(prompt.indexOf("Layer 2"));
    }

    @Test
    void switchingWorkspaceContextDropsStaleNestedLayer() throws Exception {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("workspace/agent/agent-1"));
        Path areaA = Files.createDirectories(workspaceRoot.resolve("nested-a"));
        Path areaB = Files.createDirectories(workspaceRoot.resolve("nested-b"));
        Files.writeString(workspaceRoot.resolve("AGENTS.md"), "root-guidance");
        Files.writeString(areaA.resolve("AGENTS.md"), "area-a-guidance");
        Files.writeString(areaB.resolve("AGENTS.md"), "area-b-guidance");
        PromptContextAssembler assembler = assemblerWithResolver(new AgentsMdResolver());

        OrchestrationTaskContextHolder.set(workspaceContext(workspaceRoot, areaA.resolve("file.txt")));
        String promptA = assembler.mergeModePrompt(PlanMode.NORMAL, "conversation-1");

        OrchestrationTaskContextHolder.set(workspaceContext(workspaceRoot, areaB.resolve("file.txt")));
        String promptB = assembler.mergeModePrompt(PlanMode.NORMAL, "conversation-1");

        assertThat(promptA).contains("area-a-guidance");
        assertThat(promptA).doesNotContain("area-b-guidance");
        assertThat(promptB).contains("area-b-guidance");
        assertThat(promptB).doesNotContain("area-a-guidance");
    }

    @Test
    void switchingSiblingNestedFilesInSameBoundRootDropsStaleLayer() throws Exception {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("workspace/agent/agent-1"));
        Path nestedA = Files.createDirectories(workspaceRoot.resolve("a"));
        Path nestedB = Files.createDirectories(workspaceRoot.resolve("b"));
        Files.writeString(workspaceRoot.resolve("AGENTS.md"), "workspace-root-guidance");
        Files.writeString(nestedA.resolve("AGENTS.md"), "nested-a-guidance");
        Files.writeString(nestedB.resolve("AGENTS.md"), "nested-b-guidance");
        PromptContextAssembler assembler = assemblerWithResolver(new AgentsMdResolver());

        OrchestrationTaskContextHolder.set(new OrchestrationTaskContext("agent-1",
            "Agent One",
            null,
            null,
            null,
            null,
            null,
            "workspace-1",
            "workspace-1",
            "TASK_RUN",
            null,
            workspaceRoot.toString(),
            "a/file.txt", null, null, null, null,
            new WorkspaceDescriptor(WorkspaceKind.AGENT, "agent-1", workspaceRoot), null));
        String promptA = assembler.mergeModePrompt(PlanMode.NORMAL, "conversation-1");

        OrchestrationTaskContextHolder.set(new OrchestrationTaskContext("agent-1",
            "Agent One",
            null,
            null,
            null,
            null,
            null,
            "workspace-1",
            "workspace-1",
            "TASK_RUN",
            null,
            workspaceRoot.toString(),
            "b/file.txt", null, null, null, null,
            new WorkspaceDescriptor(WorkspaceKind.AGENT, "agent-1", workspaceRoot), null));
        String promptB = assembler.mergeModePrompt(PlanMode.NORMAL, "conversation-1");

        assertThat(promptA).contains("workspace-root-guidance");
        assertThat(promptA).contains("nested-a-guidance");
        assertThat(promptA).doesNotContain("nested-b-guidance");
        assertThat(promptB).contains("workspace-root-guidance");
        assertThat(promptB).contains("nested-b-guidance");
        assertThat(promptB).doesNotContain("nested-a-guidance");
    }

    @Test
    void fileToolTargetPathUpdatesAgentsMdContextDuringModelBackedRun() throws Exception {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("workspace/agent/agent-1"));
        Path nestedA = Files.createDirectories(workspaceRoot.resolve("a"));
        Path nestedB = Files.createDirectories(workspaceRoot.resolve("b"));
        Path outputRoot = Files.createDirectories(workspaceRoot.resolve("runs/run-1/outputs"));
        Files.writeString(workspaceRoot.resolve("AGENTS.md"), "workspace-root-guidance");
        Files.writeString(nestedA.resolve("AGENTS.md"), "nested-a-guidance");
        Files.writeString(nestedB.resolve("AGENTS.md"), "nested-b-guidance");
        Files.writeString(nestedA.resolve("file.txt"), "a\n");
        Files.writeString(nestedB.resolve("file.txt"), "b\n");
        AgentFileToolService fileTool = new AgentFileToolService(aiConfig());
        PromptContextAssembler assembler = assemblerWithResolver(new AgentsMdResolver());
        OrchestrationTaskContextHolder.set(new OrchestrationTaskContext("agent-1",
            "Agent One",
            null,
            null,
            null,
            null,
            null,
            "workspace-1",
            "workspace-1",
            "TASK_RUN",
            null,
            workspaceRoot.toString(),
            null, null, null, null, null,
            new WorkspaceDescriptor(WorkspaceKind.AGENT, "agent-1", workspaceRoot), null));

        fileTool.read("a/file.txt", 1, 10);
        String promptA = assembler.mergeModePrompt(PlanMode.NORMAL, "conversation-1");
        fileTool.read("b/file.txt", 1, 10);
        String promptB = assembler.mergeModePrompt(PlanMode.NORMAL, "conversation-1");

        assertThat(OrchestrationTaskContextHolder.current().activeRuntimePath())
            .isEqualTo("b/file.txt");
        assertThat(promptA).contains("workspace-root-guidance");
        assertThat(promptA).contains("nested-a-guidance");
        assertThat(promptA).doesNotContain("nested-b-guidance");
        assertThat(promptB).contains("workspace-root-guidance");
        assertThat(promptB).contains("nested-b-guidance");
        assertThat(promptB).doesNotContain("nested-a-guidance");
    }

    @Test
    void omitsAgentsMdContextWhenNoLayersExist() throws Exception {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("workspace/agent/agent-1"));
        OrchestrationTaskContext context = new OrchestrationTaskContext("agent-1",
            "Agent One",
            null,
            null,
            null,
            null,
            null,
            "workspace-1",
            "workspace-1",
            "TASK_RUN",
            null,
            workspaceRoot.toString(),
            null, null, null, null, null);
        OrchestrationTaskContextHolder.set(context);

        PromptContextAssembler assembler = assemblerWithResolver(new AgentsMdResolver());
        String prompt = assembler.mergeModePrompt(PlanMode.NORMAL, "conversation-1");

        assertThat(prompt).isEqualTo("base-system-prompt");
        assertThat(prompt).doesNotContain("Untrusted Guidance — AGENTS.md");
    }

    @Test
    void ordinaryChatContextDoesNotTriggerResolverLookup() {
        CountingResolver resolver = new CountingResolver();
        OrchestrationTaskContextHolder.set(new OrchestrationTaskContext(null,
            null,
            null,
            null,
            "conversation-1",
            null,
            null,
            null,
            null,
            "CHAT",
            null,
            tempDir.toString(),
            null, null, null, null, null));
        PromptContextAssembler assembler = assemblerWithResolver(resolver);

        String prompt = assembler.mergeModePrompt(PlanMode.NORMAL, "conversation-1");

        assertThat(prompt).isEqualTo("base-system-prompt");
        assertThat(resolver.calls).isZero();
    }

    @Test
    void omitsAgentsMdContextWhenAgentContextHasNoBoundRoot() {
        OrchestrationTaskContextHolder.set(new OrchestrationTaskContext("agent-1",
            "Agent One",
            null,
            null,
            null,
            null,
            null,
            "workspace-1",
            "workspace-1",
            "TASK_RUN",
            null,
            null,
            null, null, null, null, null));
        PromptContextAssembler assembler = assemblerWithResolver(new AgentsMdResolver());

        String prompt = assembler.mergeModePrompt(PlanMode.NORMAL, "conversation-1");

        assertThat(prompt).isEqualTo("base-system-prompt");
        assertThat(prompt).doesNotContain("Untrusted Guidance — AGENTS.md");
    }

    @Test
    void appendsSkillCatalogWhenAssignedSkillsExist() {
        AgentSkillRuntimeCatalogService runtimeCatalogService = mock(AgentSkillRuntimeCatalogService.class);
        when(runtimeCatalogService.catalogForConversation("conversation-1")).thenReturn(List.of(
            new AgentSkillCatalogEntry("pdf-processing", "Use when extracting or merging PDFs.")
        ));
        AgentSkillPromptCatalogAssembler catalogAssembler = new AgentSkillPromptCatalogAssembler(runtimeCatalogService);
        RuntimeSettingsService runtimeSettingsService = mock(RuntimeSettingsService.class);
        when(runtimeSettingsService.defaultSystemPrompt()).thenReturn("base-system-prompt");
        PromptContextAssembler assembler = new PromptContextAssembler(
            null,
            runtimeSettingsService,
            null,
            null,
            null,
            null,
            catalogAssembler
        );

        String prompt = assembler.mergeModePrompt(PlanMode.NORMAL, "conversation-1");

        assertThat(prompt).contains("## Available Agent Skills");
        assertThat(prompt).contains("activate_skill");
        assertThat(prompt).contains("<available_skills>");
        assertThat(prompt).contains("<name>pdf-processing</name>");
    }

    @Test
    void omitsSkillCatalogWhenNoSkillsAreAssigned() {
        AgentSkillRuntimeCatalogService runtimeCatalogService = mock(AgentSkillRuntimeCatalogService.class);
        when(runtimeCatalogService.catalogForConversation("conversation-1")).thenReturn(List.of());
        AgentSkillPromptCatalogAssembler catalogAssembler = new AgentSkillPromptCatalogAssembler(runtimeCatalogService);
        RuntimeSettingsService runtimeSettingsService = mock(RuntimeSettingsService.class);
        when(runtimeSettingsService.defaultSystemPrompt()).thenReturn("base-system-prompt");
        PromptContextAssembler assembler = new PromptContextAssembler(
            null,
            runtimeSettingsService,
            null,
            null,
            null,
            null,
            catalogAssembler
        );

        String prompt = assembler.mergeModePrompt(PlanMode.NORMAL, "conversation-1");

        assertThat(prompt).isEqualTo("base-system-prompt");
        assertThat(prompt).doesNotContain("available_skills");
    }

    @Test
    void omitsSkillCatalogInPlanMode() {
        AgentSkillRuntimeCatalogService runtimeCatalogService = mock(AgentSkillRuntimeCatalogService.class);
        when(runtimeCatalogService.catalogForConversation("conversation-1")).thenReturn(List.of(
            new AgentSkillCatalogEntry("pdf-processing", "Use when extracting or merging PDFs.")
        ));
        AgentSkillPromptCatalogAssembler catalogAssembler = new AgentSkillPromptCatalogAssembler(runtimeCatalogService);
        RuntimeSettingsService runtimeSettingsService = mock(RuntimeSettingsService.class);
        when(runtimeSettingsService.defaultSystemPrompt()).thenReturn("base-system-prompt");
        PromptContextAssembler assembler = new PromptContextAssembler(
            null,
            runtimeSettingsService,
            null,
            null,
            null,
            null,
            catalogAssembler
        );

        String prompt = assembler.mergeModePrompt(PlanMode.PLAN, "conversation-1");

        assertThat(prompt).isEmpty();
        assertThat(prompt).doesNotContain("available_skills");
    }

    @Test
    void omitsSkillCatalogInTaskMode() {
        AgentSkillRuntimeCatalogService runtimeCatalogService = mock(AgentSkillRuntimeCatalogService.class);
        when(runtimeCatalogService.catalogForConversation("conversation-1")).thenReturn(List.of(
            new AgentSkillCatalogEntry("pdf-processing", "Use when extracting or merging PDFs.")
        ));
        AgentSkillPromptCatalogAssembler catalogAssembler = new AgentSkillPromptCatalogAssembler(runtimeCatalogService);
        RuntimeSettingsService runtimeSettingsService = mock(RuntimeSettingsService.class);
        when(runtimeSettingsService.defaultSystemPrompt()).thenReturn("base-system-prompt");
        TaskService taskService = mock(TaskService.class);
        when(taskService.runtimeInstructions("conversation-1")).thenReturn("task-runtime-prompt");
        PromptContextAssembler assembler = new PromptContextAssembler(
            null,
            runtimeSettingsService,
            null,
            taskService,
            null,
            null,
            catalogAssembler
        );

        String prompt = assembler.mergeModePrompt(PlanMode.TASK, "conversation-1");

        assertThat(prompt).isEqualTo("task-runtime-prompt");
        assertThat(prompt).doesNotContain("available_skills");
    }

    @Test
    void appendsSkillCatalogInExecuteTaskMode() {
        AgentSkillRuntimeCatalogService runtimeCatalogService = mock(AgentSkillRuntimeCatalogService.class);
        when(runtimeCatalogService.catalogForConversation("conversation-1")).thenReturn(List.of(
            new AgentSkillCatalogEntry("pdf-processing", "Use when extracting or merging PDFs.")
        ));
        AgentSkillPromptCatalogAssembler catalogAssembler = new AgentSkillPromptCatalogAssembler(runtimeCatalogService);
        RuntimeSettingsService runtimeSettingsService = mock(RuntimeSettingsService.class);
        when(runtimeSettingsService.defaultSystemPrompt()).thenReturn("base-system-prompt");
        TaskService taskService = mock(TaskService.class);
        when(taskService.runtimeInstructions("conversation-1")).thenReturn("task-runtime-prompt");
        PromptContextAssembler assembler = new PromptContextAssembler(
            null,
            runtimeSettingsService,
            null,
            taskService,
            null,
            null,
            catalogAssembler
        );

        String prompt = assembler.mergeModePrompt(PlanMode.EXECUTE_TASK, "conversation-1");

        assertThat(prompt).contains("base-system-prompt");
        assertThat(prompt).contains("task-runtime-prompt");
        assertThat(prompt).contains("## Available Agent Skills");
        assertThat(prompt).contains("<available_skills>");
        assertThat(prompt).contains("<name>pdf-processing</name>");
    }

    private PromptContextAssembler assemblerWithResolver(AgentsMdResolver resolver) {
        RuntimeSettingsService runtimeSettingsService = mock(RuntimeSettingsService.class);
        when(runtimeSettingsService.defaultSystemPrompt()).thenReturn("base-system-prompt");
        return new PromptContextAssembler(
            null,
            runtimeSettingsService,
            null,
            null,
            null,
            resolver
        );
    }

    private AiConfig aiConfig() {
        return new AiConfig(null, null, null, null, null, null, tempDir, null, null, null);
    }

    private OrchestrationTaskContext workspaceContext(Path workspaceRoot, Path activeFile) {
        String activePath = null;
        if (activeFile != null) {
            Path relative = workspaceRoot.relativize(activeFile);
            activePath = relative.toString().replace('\\', '/');
        }
        return new OrchestrationTaskContext("agent-1",
            "Agent One",
            null,
            null,
            null,
            null,
            null,
            "workspace-1",
            "workspace-1",
            "TASK_RUN",
            null,
            workspaceRoot.toString(),
            activePath, null, null, null, null,
            new WorkspaceDescriptor(WorkspaceKind.AGENT, "agent-1", workspaceRoot), null);
    }

    private static final class CountingResolver extends AgentsMdResolver {
        int calls = 0;

        @Override
        public Optional<AgentsMdResolution> resolveForContext(
            OrchestrationTaskContext context,
            String activePath
        ) throws IOException {
            calls++;
            return Optional.empty();
        }
    }
}
