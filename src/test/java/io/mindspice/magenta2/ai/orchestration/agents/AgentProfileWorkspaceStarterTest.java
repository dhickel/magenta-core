package io.mindspice.magenta2.ai.orchestration.agents;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta2.ai.config.user.AgentConfig;
import io.mindspice.magenta2.ai.config.user.AiConfig;
import io.mindspice.magenta2.ai.config.user.EndpointType;
import io.mindspice.magenta2.ai.config.user.ModelConfig;
import io.mindspice.magenta2.ai.orchestration.workspaces.RootRelativePathService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceRepository;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceService;
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
    void createDoesNotGenerateStarterFileAndUserEditedFileIsPreserved() throws Exception {
        TestContext context = context();
        AgentProfileService service = context.service();

        service.create(profile("agent-1", "Agent One"));
        Path agentsFile = context.dataRoot().resolve("workspace/agent-1/AGENTS.md");
        // Phase 02: starter file generation is removed
        assertThat(Files.isRegularFile(agentsFile)).isFalse();

        // User-created file is preserved across updates
        String custom = "# Custom AGENTS\n\nKeep this exact content.\n";
        Files.writeString(agentsFile, custom);

        service.update("agent-1", profile("agent-1", "Agent One Updated"));
        service.enable("agent-1", true);

        assertThat(Files.readString(agentsFile)).isEqualTo(custom);
    }

    private TestContext context() throws Exception {
        JdbcTemplate workspaceJdbc = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
        WorkspaceRepository workspaceRepository = new WorkspaceRepository(workspaceJdbc);

        AiConfig aiConfig = aiConfig();
        WorkspaceDirectoryService directoryService = WorkspaceDirectoryServiceTestSupport.directoryService(tempDir);
        WorkspaceService workspaceService = new WorkspaceService(
            workspaceRepository,
            aiConfig,
            new RootRelativePathService(directoryService)
        );

        AgentProfileRepository profileRepository = new AgentProfileRepository(
            new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true)),
            new ObjectMapper()
        );

        AgentProfileService service = new AgentProfileService(
            profileRepository,
            aiConfig,
            null,
            provider(workspaceService),
            provider(directoryService),
            provider(workspaceRepository),
            null,
            null,
            null
        );
        return new TestContext(tempDir.toRealPath(), service);
    }

    private AgentProfile profile(String id, String name) {
        return new AgentProfile(
            id,
            name,
            AgentProfileStatus.ACTIVE,
            "main",
            "Prompt",
            List.of(),
            List.of("printf"),
            true,
            null,
            null
        );
    }

    private AiConfig aiConfig() {
        return new AiConfig(
            "legacy",
            "main",
            "main",
            "main",
            "main",
            10,
            tempDir,
            null,
            Map.of("main", new ModelConfig("main-remote", "http://localhost:11434", EndpointType.OLLAMA, 4096, 0, null)),
            Map.of("legacy", new AgentConfig("main", "Prompt", List.of(), List.of("*")))
        );
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public Stream<T> stream() {
                return value == null ? Stream.empty() : Stream.of(value);
            }
        };
    }

    private record TestContext(Path dataRoot, AgentProfileService service) {
    }
}
