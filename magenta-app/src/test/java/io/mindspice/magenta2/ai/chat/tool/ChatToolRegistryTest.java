package io.mindspice.magenta2.ai.chat.tool;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta2.ai.chat.tool.file.AgentFileToolConfiguration;
import io.mindspice.magenta2.ai.chat.tool.file.AgentFileToolService;
import io.mindspice.magenta2.ai.chat.tool.file.AgentFileTools;
import io.mindspice.magenta2.ai.chat.tool.shell.AgentShellToolConfiguration;
import io.mindspice.magenta2.ai.chat.tool.shell.AgentShellToolService;
import io.mindspice.magenta2.ai.chat.tool.shell.AgentShellTools;
import io.mindspice.magenta2.ai.chat.tool.skills.AgentSkillActivationTools;
import io.mindspice.magenta2.ai.chat.tool.skills.AgentSkillToolConfiguration;
import io.mindspice.magenta2.ai.chat.tool.web.AgentWebToolConfiguration;
import io.mindspice.magenta2.ai.chat.tool.web.AgentWebToolService;
import io.mindspice.magenta2.ai.chat.tool.web.AgentWebTools;
import io.mindspice.magenta2.ai.config.user.AgentConfig;
import io.mindspice.magenta2.ai.config.user.AiConfig;
import io.mindspice.magenta2.ai.config.user.WebSearchConfig;
import io.mindspice.magenta2.ai.skills.AgentSkillActivationResult;
import io.mindspice.magenta2.ai.skills.AgentSkillActivationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatToolRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesMethodToolProviderCallbacksByApprovedName() throws IOException {
        AgentFileToolService service = new AgentFileToolService(aiConfig());
        AgentFileTools tools = new AgentFileTools(service, new ObjectMapper());
        ToolCallbackProvider provider = new AgentFileToolConfiguration().agentFileToolCallbackProvider(tools);
        ChatToolRegistry registry = new ChatToolRegistry(List.of(), List.of(provider));

        assertThat(registry.resolveApprovedTools(List.of("file_read", "file_replace")))
            .extracting(callback -> callback.getToolDefinition().name())
            .containsExactly("file_read", "file_replace");
    }

    @Test
    void exposesFileToolsWithModelVisibleDescriptionsAndArgumentSchemas() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AgentFileToolService service = new AgentFileToolService(aiConfig());
        AgentFileTools tools = new AgentFileTools(service, objectMapper);
        ToolCallbackProvider provider = new AgentFileToolConfiguration().agentFileToolCallbackProvider(tools);
        Map<String, ToolCallback> callbacks = Arrays.stream(provider.getToolCallbacks())
            .collect(Collectors.toMap(callback -> callback.getToolDefinition().name(), Function.identity()));

        assertThat(callbacks.keySet())
            .containsExactlyInAnyOrder("file_list", "file_read", "file_search", "file_write", "file_append", "file_replace");

        assertTool(callbacks.get("file_list"), objectMapper, List.of(), "path", "recursive", "maxEntries", "glob");
        assertTool(callbacks.get("file_read"), objectMapper, List.of("path"), "path", "startLine", "maxLines");
        assertTool(
            callbacks.get("file_search"),
            objectMapper,
            List.of("query"),
            "path",
            "query",
            "regex",
            "caseSensitive",
            "contextLines",
            "maxMatches"
        );
        assertTool(callbacks.get("file_write"), objectMapper, List.of("path", "content"), "path", "content", "overwrite");
        assertTool(callbacks.get("file_append"), objectMapper, List.of("path", "content"), "path", "content", "create");
        assertTool(
            callbacks.get("file_replace"),
            objectMapper,
            List.of("path", "startAnchor", "replacement"),
            "path",
            "startAnchor",
            "endAnchor",
            "replacement"
        );

        assertThat(callbacks.get("file_list").getToolDefinition().description())
            .contains("List files and directories")
            .contains("Use this before reading");
        assertThat(callbacks.get("file_replace").getToolDefinition().description())
            .contains("lineNumber:hash anchors")
            .contains("avoid stale edits");
        assertThat(callbacks.get("file_append").getToolDefinition().description())
            .contains("Append UTF-8 text")
            .contains("instead of rewriting existing file content");
    }

    @Test
    void rejectsUnknownApprovedToolNames() {
        ChatToolRegistry registry = new ChatToolRegistry(List.of(), List.of());

        assertThatThrownBy(() -> registry.resolveApprovedTools(List.of("missing_tool")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("missing_tool");
    }

    @Test
    void wildcardApprovesAllRegisteredTools() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        AgentFileToolService fileService = new AgentFileToolService(aiConfig());
        AgentFileTools fileTools = new AgentFileTools(fileService, objectMapper);
        ToolCallbackProvider fileProvider = new AgentFileToolConfiguration().agentFileToolCallbackProvider(fileTools);
        AgentShellToolService shellService = new AgentShellToolService(aiConfig());
        AgentShellTools shellTools = new AgentShellTools(shellService, objectMapper);
        ToolCallbackProvider shellProvider = new AgentShellToolConfiguration().agentShellToolCallbackProvider(shellTools);
        AgentWebToolService webService = new AgentWebToolService(aiConfig(), objectMapper);
        AgentWebTools webTools = new AgentWebTools(webService, objectMapper);
        ToolCallbackProvider webProvider = new AgentWebToolConfiguration().agentWebToolCallbackProvider(webTools, aiConfig());
        ChatToolRegistry registry = new ChatToolRegistry(List.of(), List.of(fileProvider, shellProvider, webProvider));

        assertThat(registry.resolveApprovedTools(List.of("*")))
            .extracting(callback -> callback.getToolDefinition().name())
            .containsExactlyInAnyOrder(
                "file_list",
                "file_read",
                "file_search",
                "file_write",
                "file_append",
                "file_replace",
                "shell_exec",
                "web_search",
                "web_fetch"
            );
    }

    @Test
    void exposesShellToolWithModelVisibleDescriptionsAndArgumentSchema() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AgentShellToolService service = new AgentShellToolService(aiConfig());
        AgentShellTools tools = new AgentShellTools(service, objectMapper);
        ToolCallbackProvider provider = new AgentShellToolConfiguration().agentShellToolCallbackProvider(tools);
        Map<String, ToolCallback> callbacks = Arrays.stream(provider.getToolCallbacks())
            .collect(Collectors.toMap(callback -> callback.getToolDefinition().name(), Function.identity()));

        assertThat(callbacks.keySet()).containsExactly("shell_exec");
        assertTool(callbacks.get("shell_exec"), objectMapper, List.of("command"), "command", "workingDirectory", "timeoutSeconds");
        assertThat(callbacks.get("shell_exec").getToolDefinition().description())
            .contains("allowed Linux command line")
            .contains("command line");
    }

    @Test
    void exposesWebToolsWithModelVisibleDescriptionsAndArgumentSchemas() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AgentWebToolService service = new AgentWebToolService(aiConfig(), objectMapper);
        AgentWebTools tools = new AgentWebTools(service, objectMapper);
        ToolCallbackProvider provider = new AgentWebToolConfiguration().agentWebToolCallbackProvider(tools, aiConfig());
        Map<String, ToolCallback> callbacks = Arrays.stream(provider.getToolCallbacks())
            .collect(Collectors.toMap(callback -> callback.getToolDefinition().name(), Function.identity()));

        assertThat(callbacks.keySet()).containsExactlyInAnyOrder("web_search", "web_fetch");
        assertTool(callbacks.get("web_search"), objectMapper, List.of("query"), "query", "maxResults");
        assertTool(callbacks.get("web_fetch"), objectMapper, List.of("url"), "url", "maxCharacters");
        assertThat(callbacks.get("web_search").getToolDefinition().description())
            .contains("SearXNG")
            .contains("web_fetch");
        assertThat(callbacks.get("web_fetch").getToolDefinition().description())
            .contains("http or https URL")
            .contains("cite");
    }

    @Test
    void exposesActivateSkillToolWithSkillNameInput() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AgentSkillActivationService activationService = mock(AgentSkillActivationService.class);
        when(activationService.activateSkill(null, "pdf-processing"))
            .thenReturn(AgentSkillActivationResult.failure(null, "pdf-processing", "ok"));
        AgentSkillActivationTools tools = new AgentSkillActivationTools(activationService, objectMapper);
        ToolCallbackProvider provider = new AgentSkillToolConfiguration().agentSkillToolCallbackProvider(tools);
        Map<String, ToolCallback> callbacks = Arrays.stream(provider.getToolCallbacks())
            .collect(Collectors.toMap(callback -> callback.getToolDefinition().name(), Function.identity()));

        assertThat(callbacks.keySet()).containsExactly("activate_skill");
        assertTool(callbacks.get("activate_skill"), objectMapper, List.of("skillName"), "skillName");
        assertThat(callbacks.get("activate_skill").getToolDefinition().description())
            .contains("assigned Agent Skill")
            .contains("resource listing");
    }

    private AiConfig aiConfig() {
        return new AiConfig(
            "magenta",
            "magenta",
            10,
            tempDir,
            new WebSearchConfig(true, "searxng", "http://localhost:8080"),
            Map.of(),
            Map.of("magenta", new AgentConfig("model", "prompt", List.of(), List.of("*")))
        );
    }

    private void assertTool(
        ToolCallback callback,
        ObjectMapper objectMapper,
        List<String> requiredProperties,
        String... expectedProperties
    ) throws Exception {
        assertThat(callback).isNotNull();
        assertThat(callback.getToolDefinition().description()).isNotBlank();

        JsonNode schema = objectMapper.readTree(callback.getToolDefinition().inputSchema());
        JsonNode properties = schema.path("properties");
        assertThat(properties.fieldNames()).toIterable().containsExactlyInAnyOrder(expectedProperties);
        for (String property : expectedProperties) {
            assertThat(properties.path(property).path("description").asText())
                .as(callback.getToolDefinition().name() + "." + property + " description")
                .isNotBlank();
        }
        assertThat(StreamSupport.stream(schema.path("required").spliterator(), false).map(JsonNode::asText).toList())
            .containsExactlyInAnyOrderElementsOf(requiredProperties);
    }
}
