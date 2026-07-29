package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import io.mindspice.magenta2.ai.chat.repository.ChatMemoryRepository;
import io.mindspice.magenta2.ai.chat.repository.ChatSessionMetadataRepository;
import io.mindspice.magenta2.ai.config.user.AiConfig;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileRepository;
import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationRepository.MaterializationKey;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
/**
 * Provides explicit test collaborators for constructing
 * {@link WorkspaceDirectoryService} in tests, avoiding the
 * need for Spring wiring or optional providers.
 */
public final class WorkspaceDirectoryServiceTestSupport {

    private WorkspaceDirectoryServiceTestSupport() {
    }

    public static AiConfig aiConfig(Path dataRoot) {
        return new AiConfig(null, null, null, null, null,
                null, dataRoot, null, Map.of(), Map.of());
    }

    public static AgentProfileRepository agentProfileRepository(boolean allExist) {
        return new AgentProfileRepository(
                new JdbcTemplate(new SingleConnectionDataSource(
                        "jdbc:sqlite::memory:?foreign_keys=true", true)),
                new com.fasterxml.jackson.databind.ObjectMapper()) {
            @Override
            public boolean existsById(String id) {
                return allExist;
            }
        };
    }

    public static AgentProfileRepository agentProfileRepositoryWith(String... existingIds) {
        return new AgentProfileRepository(
                new JdbcTemplate(new SingleConnectionDataSource(
                        "jdbc:sqlite::memory:?foreign_keys=true", true)),
                new com.fasterxml.jackson.databind.ObjectMapper()) {
            @Override
            public boolean existsById(String id) {
                for (String existing : existingIds) {
                    if (existing.equals(id)) return true;
                }
                return false;
            }
        };
    }

    public static ChatConversationExistenceRepository chatExistenceRepository(boolean allExist) {
        var dataSource = new SingleConnectionDataSource(
                "jdbc:sqlite::memory:?foreign_keys=true", true);
        var jdbc = new JdbcTemplate(dataSource);
        var objMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var chatMemory = new ChatMemoryRepository(jdbc, objMapper) {
            @Override
            public boolean existsByConversationId(String conversationId) {
                return allExist;
            }
        };
        var chatSession = new ChatSessionMetadataRepository(jdbc) {
            @Override
            public boolean existsByConversationId(String conversationId) {
                return allExist;
            }
        };
        return new ChatConversationExistenceRepository(chatMemory, chatSession, jdbc);
    }

    public static ChatConversationExistenceRepository chatExistenceRepositoryWith(String... existingIds) {
        var dataSource = new SingleConnectionDataSource(
                "jdbc:sqlite::memory:?foreign_keys=true", true);
        var jdbc = new JdbcTemplate(dataSource);
        var objMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var chatMemory = new ChatMemoryRepository(jdbc, objMapper) {
            @Override
            public boolean existsByConversationId(String conversationId) {
                for (String existing : existingIds) {
                    if (existing.equals(conversationId)) return true;
                }
                return false;
            }
        };
        var chatSession = new ChatSessionMetadataRepository(jdbc) {
            @Override
            public boolean existsByConversationId(String conversationId) {
                for (String existing : existingIds) {
                    if (existing.equals(conversationId)) return true;
                }
                return false;
            }
        };
        return new ChatConversationExistenceRepository(chatMemory, chatSession, jdbc);
    }

    public static WorkspaceDirectoryService directoryService(Path dataRoot) throws IOException {
        return new WorkspaceDirectoryService(
                aiConfig(dataRoot),
                agentProfileRepository(true),
                chatExistenceRepository(true));
    }

    public static WorkspaceDirectoryService directoryService(
            Path dataRoot,
            AgentProfileRepository agentProfileRepo,
            ChatConversationExistenceRepository chatExistenceRepo) throws IOException {
        return new WorkspaceDirectoryService(
                aiConfig(dataRoot), agentProfileRepo, chatExistenceRepo);
    }

    /** Create a descriptor for tests that need one without directory resolution. */
    public static WorkspaceDescriptor descriptor(WorkspaceKind kind, String key, Path root) {
        return new WorkspaceDescriptor(kind, key, root.toAbsolutePath().normalize());
    }

    /**
     * Create a MaterializationLocation for tests. Uses {@code agentRoot} as the
     * absolute parent of the canonical confined path.
     */
    public static MaterializationLocation materializationLocation(
            WorkspaceDescriptor agentDescriptor,
            MaterializationKey key,
            int generation) {
        Path agentRoot = agentDescriptor.root();
        Path confined = agentRoot.resolve("projects/" + key.serialized());
        String relative = "workspace/agent/" + agentDescriptor.key()
                + "/projects/" + key.serialized() + "/";
        return new MaterializationLocation(agentDescriptor, key, generation,
                relative, confined.toAbsolutePath().normalize());
    }
}
