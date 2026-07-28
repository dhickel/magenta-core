package io.mindspice.magenta2.ai.orchestration.agents;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta2.ai.config.user.AiConfig;
import io.mindspice.magenta2.ai.config.user.EndpointType;
import io.mindspice.magenta2.ai.config.user.ModelConfig;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class AgentProfileWorkspaceStarterTest {
    @TempDir
    Path tempDir;

    @Test
    void lifecycleIsLazyAndHardDeleteRemovesOnlyCanonicalAgentRoot() throws Exception {
        AiConfig config = new AiConfig(null, "main", "main", "main", "main", 10, tempDir, null,
            Map.of("main", new ModelConfig("main", "http://localhost", EndpointType.OLLAMA, 4096, 0, null)),
            Map.of());
        var repository = new AgentProfileRepository(
            new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true)),
            new ObjectMapper());
        WorkspaceDirectoryService directories = WorkspaceDirectoryServiceTestSupport.directoryService(tempDir);
        var service = new AgentProfileService(repository, config, null, provider(directories), null, null, null);

        service.create(profile());
        Path canonicalRoot = tempDir.resolve("workspace/agent/agent-1");
        assertThat(canonicalRoot).doesNotExist();

        service.enable("agent-1", true);
        service.get("agent-1");
        service.disable("agent-1");
        assertThat(canonicalRoot).doesNotExist();

        Files.createDirectories(canonicalRoot);
        Files.writeString(canonicalRoot.resolve("user.txt"), "keep until hard delete");
        Path legacy = Files.createDirectories(tempDir.resolve("workspace/agent-1"));
        Files.writeString(legacy.resolve("legacy.txt"), "untouched");

        service.hardDelete("agent-1", "DELETE agent-1");

        assertThat(canonicalRoot).doesNotExist();
        assertThat(legacy.resolve("legacy.txt")).hasContent("untouched");
    }

    private AgentProfile profile() {
        return new AgentProfile("agent-1", "Agent One", AgentProfileStatus.ACTIVE, "main", "Prompt",
            List.of(), List.of("printf"), true, null, null);
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override public T getObject() { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
            @Override public Stream<T> stream() { return Stream.of(value); }
        };
    }
}
