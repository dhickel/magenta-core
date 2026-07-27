package io.mindspice.magenta2.ai.orchestration.context;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.ContextBinding;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.ContextDefault;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.PersonalSelection;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.SelectionKind;
import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationRepository.MaterializationKey;
import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationRepository.MaterializationRecord;
import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationRepository.MaterializationState;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class AgentContextPersistenceRepositoryTest {

    @Test
    void defaultAndMaterializationTransitionsUseExpectedVersionAndGeneration() throws Exception {
        try (SingleConnectionDataSource dataSource = database()) {
            AgentContextRepository contexts = new AgentContextRepository(new JdbcTemplate(dataSource));
            AgentMaterializationRepository materializations = new AgentMaterializationRepository(new JdbcTemplate(dataSource));
            Instant now = Instant.now();
            MaterializationKey key = MaterializationKey.forProject("agent-1", "project-1");
            MaterializationRecord provisioning = new MaterializationRecord(
                    "agent-1", key, "project", "project-1", 1, MaterializationState.PROVISIONING,
                    null, null, now, now);

            assertThat(materializations.insertIfAbsent(provisioning)).isTrue();
            assertThat(materializations.transitionState("agent-1", key, 1,
                    MaterializationState.PROVISIONING, MaterializationState.READY,
                    "workspace/agent/agent-1/projects/" + key.serialized(), null)).isTrue();
            assertThat(materializations.transitionState("agent-1", key, 1,
                    MaterializationState.PROVISIONING, MaterializationState.FAILED, null, "stale")).isFalse();

            ContextDefault initial = new ContextDefault("agent-1", 1, SelectionKind.PERSONAL,
                    null, null, PersonalSelection.HOME, null, now, now);
            contexts.insertDefault(initial);
            ContextDefault replacement = new ContextDefault("agent-1", 2, SelectionKind.PERSONAL,
                    null, null, PersonalSelection.SCRIPTS, null, now, now);
            assertThat(contexts.updateDefault("agent-1", 1, replacement)).isTrue();
            assertThat(contexts.updateDefault("agent-1", 1, replacement)).isFalse();
        }
    }

    @Test
    void bindingsOwnerLinksAndContextOperationsAreInsertOnlyAndIdempotent() throws Exception {
        try (SingleConnectionDataSource dataSource = database()) {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            AgentContextRepository contexts = new AgentContextRepository(jdbc);
            AgentMaterializationRepository materializations = new AgentMaterializationRepository(jdbc);
            Instant now = Instant.now();
            ContextBinding binding = ContextBinding.forInsert("agent-1", "binding-1",
                    SelectionKind.PERSONAL, null, null, PersonalSelection.HOME, now);

            assertThat(contexts.captureBindingIdempotently("capture-1", "a".repeat(64), binding).status())
                    .isEqualTo(AgentContextRepository.ContextOperationStatus.CREATED);
            assertThat(contexts.captureBindingIdempotently("capture-1", "a".repeat(64), binding).status())
                    .isEqualTo(AgentContextRepository.ContextOperationStatus.REPLAYED);
            assertThat(contexts.captureBindingIdempotently("capture-1", "b".repeat(64), binding).status())
                    .isEqualTo(AgentContextRepository.ContextOperationStatus.CONFLICT);
            assertThat(contexts.linkOwnerIdempotently("owner-1", "c".repeat(64),
                    new AgentContextRepository.BindingOwnerLink(
                            "conversation", "conversation-1", "binding-1", now)).status())
                    .isEqualTo(AgentContextRepository.ContextOperationStatus.CREATED);
            assertThat(contexts.linkOwnerIdempotently("owner-1", "c".repeat(64),
                    new AgentContextRepository.BindingOwnerLink(
                            "conversation", "conversation-1", "binding-1", now)).status())
                    .isEqualTo(AgentContextRepository.ContextOperationStatus.REPLAYED);
            assertThat(contexts.linkOwnerIdempotently("owner-1", "d".repeat(64),
                    new AgentContextRepository.BindingOwnerLink(
                            "conversation", "conversation-1", "binding-1", now)).status())
                    .isEqualTo(AgentContextRepository.ContextOperationStatus.CONFLICT);

            assertThat(materializations.insertOperationIfAbsent("REGISTER", "binding-1",
                    "a".repeat(64), "COMPLETED", null, null)).isTrue();
            assertThat(materializations.findOperation("REGISTER", "binding-1", "a".repeat(64)))
                    .hasValueSatisfying(result -> assertThat(result.isCompleted()).isTrue());
            assertThat(materializations.findOperation("REGISTER", "binding-1", "b".repeat(64)))
                    .hasValueSatisfying(result -> assertThat(result.isConflict()).isTrue());
        }
    }

    private static SingleConnectionDataSource database() throws Exception {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
                "jdbc:sqlite::memory:?foreign_keys=true", true);
        dataSource.setSuppressClose(true);
        ScriptUtils.executeSqlScript(dataSource.getConnection(), new EncodedResource(
                new ClassPathResource("db/migrations/010-agent-materialization-context.sql"),
                StandardCharsets.UTF_8));
        ScriptUtils.executeSqlScript(dataSource.getConnection(), new EncodedResource(
                new ClassPathResource("db/migrations/011-materialization-key-constraints.sql"),
                StandardCharsets.UTF_8));
        return dataSource;
    }
}
