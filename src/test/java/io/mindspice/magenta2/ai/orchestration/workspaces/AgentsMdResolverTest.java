package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationTaskContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AgentsMdResolverTest {
    @TempDir
    Path tempDir;

    @Test
    void returnsEmptyLayersWhenNoAgentsFilesExist() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("root"));
        Path active = Files.createDirectories(root.resolve("src/main"));
        AgentsMdResolution result = new AgentsMdResolver().resolve(root, active);

        assertThat(result.boundRoot()).isEqualTo(root.toRealPath());
        assertThat(result.activePath()).isEqualTo(active.toRealPath());
        assertThat(result.layers()).isEmpty();
    }

    @Test
    void loadsRootOnlyLayer() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("root"));
        writeAgents(root, "root");

        AgentsMdResolution result = new AgentsMdResolver().resolve(root, root.resolve("src/App.java"));

        assertThat(result.layers()).hasSize(1);
        assertThat(result.layers().getFirst().relativeDirectory()).isEmpty();
        assertThat(result.layers().getFirst().content()).isEqualTo("root");
        assertThat(result.layers().getFirst().precedenceRank()).isZero();
    }

    @Test
    void loadsNestedOnlyLayerWithinBoundRoot() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("root"));
        Path nested = Files.createDirectories(root.resolve("packages/api"));
        writeAgents(nested, "nested");

        AgentsMdResolution result = new AgentsMdResolver().resolve(root, nested.resolve("src/Service.java"));

        assertThat(result.layers()).hasSize(1);
        assertThat(result.layers().getFirst().relativeDirectory()).isEqualTo("packages/api");
        assertThat(result.layers().getFirst().content()).isEqualTo("nested");
    }

    @Test
    void loadsRootAndNestedLayersInRootToLeafOrder() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("root"));
        Path nested = Files.createDirectories(root.resolve("packages/api"));
        writeAgents(root, "root");
        writeAgents(nested, "nested");

        AgentsMdResolution result = new AgentsMdResolver().resolve(root, nested.resolve("src/Service.java"));

        assertThat(result.layers()).hasSize(2);
        assertThat(result.layers().get(0).relativeDirectory()).isEmpty();
        assertThat(result.layers().get(0).precedenceRank()).isEqualTo(0);
        assertThat(result.layers().get(1).relativeDirectory()).isEqualTo("packages/api");
        assertThat(result.layers().get(1).precedenceRank()).isEqualTo(1);
    }

    @Test
    void siblingContextSwitchDropsPriorNestedLayer() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("root"));
        Path a = Files.createDirectories(root.resolve("a"));
        Path b = Files.createDirectories(root.resolve("b"));
        writeAgents(root, "root");
        writeAgents(a, "a-layer");
        writeAgents(b, "b-layer");
        AgentsMdResolver resolver = new AgentsMdResolver();

        AgentsMdResolution aResult = resolver.resolve(root, a.resolve("notes.txt"));
        AgentsMdResolution bResult = resolver.resolve(root, b.resolve("notes.txt"));

        assertThat(aResult.layers()).extracting(AgentsMdLayer::relativeDirectory)
            .containsExactly("", "a");
        assertThat(bResult.layers()).extracting(AgentsMdLayer::relativeDirectory)
            .containsExactly("", "b");
    }

    @Test
    void rejectsTraversalOutsideBoundRoot() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("root"));
        Path outside = Files.createDirectories(tempDir.resolve("outside"));

        assertThatThrownBy(() -> new AgentsMdResolver().resolve(root, outside.resolve("../outside/file.txt")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("escapes bound root");
    }

    @Test
    void rejectsSymlinkEscapeOutsideBoundRoot() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("root"));
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Path link = root.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | FileSystemException exception) {
            assumeTrue(false, "symlink support unavailable");
        }

        assertThatThrownBy(() -> new AgentsMdResolver().resolve(root, link.resolve("secret.md")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("escapes bound root");
    }

    @Test
    void resolvesDescriptorBoundRootWithNestedContext() throws Exception {
        Path agentRoot = Files.createDirectories(tempDir.resolve("workspace/agent/agent-1"));
        Path nestedDir = Files.createDirectories(agentRoot.resolve("nested"));
        writeAgents(agentRoot, "agent-root");
        writeAgents(nestedDir, "nested-layer");
        OrchestrationTaskContext context = new OrchestrationTaskContext("agent-1",
            "Agent One",
            null,
            null,
            null,
            null,
            null,
            "TASK_RUN",
            agentRoot.toString(),
            agentRoot.resolve("outputs").toString(),
            null,
            agentRoot.toString(),
            null);

        Optional<AgentsMdResolution> result = new AgentsMdResolver().resolveForContext(context, "nested/file.txt");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().boundRoot()).isEqualTo(agentRoot.toRealPath());
        assertThat(result.orElseThrow().layers()).extracting(AgentsMdLayer::relativeDirectory)
            .containsExactly("", "nested");
    }

    @Test
    void resolvesAgentEffectiveWorkspaceWhenNoOwnerRootIsProvided() throws Exception {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("workspace/agent/agent-1"));
        Path nested = Files.createDirectories(workspaceRoot.resolve("docs"));
        writeAgents(workspaceRoot, "workspace-root");
        writeAgents(nested, "docs-layer");
        OrchestrationTaskContext context = new OrchestrationTaskContext("agent-1",
            "Agent One",
            null,
            null,
            null,
            null,
            null,
            "TASK_RUN",
            workspaceRoot.toString(),
            workspaceRoot.resolve("runs/run-1/outputs").toString(),
            null,
            workspaceRoot.toString(),
            null);

        Optional<AgentsMdResolution> result = new AgentsMdResolver().resolveForContext(context, "docs/file.txt");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().boundRoot()).isEqualTo(workspaceRoot.toRealPath());
        assertThat(result.orElseThrow().layers()).extracting(AgentsMdLayer::relativeDirectory)
            .containsExactly("", "docs");
    }

    @Test
    void returnsEmptyWhenContextHasNoBoundRoot() throws Exception {
        Optional<AgentsMdResolution> result = new AgentsMdResolver().resolveForContext(OrchestrationTaskContext.EMPTY, null);

        assertThat(result).isEmpty();
    }

    private void writeAgents(Path directory, String content) throws Exception {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("AGENTS.md"), content);
    }
}
