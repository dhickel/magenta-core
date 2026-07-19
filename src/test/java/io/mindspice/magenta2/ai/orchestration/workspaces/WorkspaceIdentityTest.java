package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceIdentityTest {

    @TempDir
    Path tempDir;

    @Test
    void kindTokensAreLowercaseAgentChatUser() {
        assertThat(WorkspaceKind.values())
            .containsExactly(WorkspaceKind.AGENT, WorkspaceKind.CHAT, WorkspaceKind.USER);
        assertThat(WorkspaceKind.AGENT.token()).isEqualTo("agent");
        assertThat(WorkspaceKind.CHAT.token()).isEqualTo("chat");
        assertThat(WorkspaceKind.USER.token()).isEqualTo("user");
    }

    @Test
    void tokensSerializeAndParseCaseNormalizedWithoutAcceptingWhitespace() throws Exception {
        var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(objectMapper.writeValueAsString(WorkspaceKind.AGENT)).isEqualTo("\"agent\"");
        assertThat(objectMapper.readValue("\"CHAT\"", WorkspaceKind.class)).isEqualTo(WorkspaceKind.CHAT);
        assertThat(WorkspaceKind.fromToken("agent")).isEqualTo(WorkspaceKind.AGENT);
        assertThat(WorkspaceKind.fromToken("AGENT")).isEqualTo(WorkspaceKind.AGENT);
        assertThat(WorkspaceKind.fromToken("Agent")).isEqualTo(WorkspaceKind.AGENT);
        assertThatThrownBy(() -> WorkspaceKind.fromToken("  agent  "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromTokenRejectsBlankAliasesOwnerTypesAndProjects() {
        for (String invalid : new String[] {"", "   ", "project", "avatar", "workspace", "owner", "AGENTS"}) {
            assertThatThrownBy(() -> WorkspaceKind.fromToken(invalid))
                .as("invalid token %s", invalid)
                .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> WorkspaceKind.fromToken(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void descriptorsHaveExactIdentityAndNormalizedAbsoluteRoots() throws Exception {
        WorkspaceDirectoryService service = WorkspaceDirectoryServiceTestSupport.directoryService(tempDir);
        WorkspaceDescriptor agent = service.resolveForRead(WorkspaceKind.AGENT, "agent-1");
        WorkspaceDescriptor chat = service.resolveForRead(WorkspaceKind.CHAT, "conv-1");
        WorkspaceDescriptor user = service.createUserWorkspace("alice");

        assertThat(agent.identity()).isEqualTo("agent/agent-1");
        assertThat(chat.identity()).isEqualTo("chat/conv-1");
        assertThat(user.identity()).isEqualTo("user/alice");
        assertThat(agent.root().isAbsolute()).isTrue();
        assertThat(chat.root().isAbsolute()).isTrue();
        assertThat(user.root().isAbsolute()).isTrue();
        assertThat(agent.root()).isEqualTo(agent.root().normalize());
        assertThat(chat.root()).isEqualTo(chat.root().normalize());
        assertThat(user.root()).isEqualTo(user.root().normalize());
    }

    @Test
    void descriptorHasValueEquality() throws Exception {
        WorkspaceDirectoryService service = WorkspaceDirectoryServiceTestSupport.directoryService(tempDir);
        WorkspaceDescriptor first = service.resolveForRead(WorkspaceKind.AGENT, "agent-1");
        WorkspaceDescriptor same = service.resolveForRead(WorkspaceKind.AGENT, "agent-1");
        WorkspaceDescriptor other = service.resolveForRead(WorkspaceKind.AGENT, "agent-2");

        assertThat(first).isEqualTo(same);
        assertThat(first).isNotEqualTo(other);
        assertThat(first.hashCode()).isEqualTo(same.hashCode());
    }

    @Test
    void descriptorConstructionIsPackageControlled() throws Exception {
        var ctor = WorkspaceDescriptor.class.getDeclaredConstructor(
                WorkspaceKind.class, String.class, Path.class);
        assertThat(java.lang.reflect.Modifier.isPublic(ctor.getModifiers())).isFalse();
        assertThat(java.lang.reflect.Modifier.isFinal(
                WorkspaceDescriptor.class.getModifiers())).isTrue();
    }

    @Test
    void toStringContainsIdentityAndRoot() throws Exception {
        WorkspaceDescriptor descriptor = WorkspaceDirectoryServiceTestSupport.directoryService(tempDir)
                .resolveForRead(WorkspaceKind.CHAT, "conv-1");
        assertThat(descriptor.toString())
            .contains("chat/conv-1")
            .contains(descriptor.root().toString());
    }
}
