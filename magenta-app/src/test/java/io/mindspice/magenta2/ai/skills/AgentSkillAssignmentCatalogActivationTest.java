package io.mindspice.magenta2.ai.skills;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfile;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileStatus;
import io.mindspice.magenta2.core.config.MagentaRootProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentSkillAssignmentCatalogActivationTest {
    @TempDir
    Path tempDir;

    @Test
    void assignmentFilteringUsesEnabledAssignedLoadableSkillsOnly() throws Exception {
        Fixture fixture = fixture();
        seedSkills(fixture);
        fixture.catalogService.refreshCatalog();

        fixture.assignmentService.assignToAgent("agent-1", "valid-skill", true);
        fixture.assignmentService.assignToAgent("agent-1", "invalid-skill", true);
        fixture.assignmentService.assignToAgent("agent-1", "missing-skill", true);
        fixture.assignmentService.assignToAgent("agent-1", "valid-skill", true);

        List<AgentSkillCatalogEntry> catalog = fixture.runtimeCatalogService.catalogForConversation("conversation-1");

        assertThat(catalog).extracting(AgentSkillCatalogEntry::name).containsExactly("valid-skill");
        assertThat(fixture.assignmentService.listAgentAssignments("agent-1"))
            .extracting(AgentSkillAssignment::skillName)
            .containsExactly("invalid-skill", "missing-skill", "valid-skill");

        fixture.assignmentService.assignToAgent("agent-1", "valid-skill", false);
        assertThat(fixture.runtimeCatalogService.catalogForConversation("conversation-1")).isEmpty();
    }

    @Test
    void activationFailsWithoutApprovedSnapshot() throws Exception {
        Fixture fixture = fixture();
        seedSkills(fixture);
        fixture.catalogService.refreshCatalog();
        fixture.assignmentService.assignToAgent("agent-1", "valid-skill", true);

        AgentSkillActivationResult result = fixture.activationService.activateSkill("conversation-1", "valid-skill");

        // Phase 07: activation requires approved snapshots. Without one, activation fails.
        assertThat(result.outcome()).isEqualTo(AgentSkillActivationOutcome.SKILL_UNAVAILABLE);
    }

    @Test
    void activationFailsForNoSkillsAndUnassigned() throws Exception {
        Fixture fixture = fixture();
        seedSkills(fixture);
        fixture.catalogService.refreshCatalog();

        AgentSkillActivationResult noSkills = fixture.activationService.activateSkill("conversation-2", "valid-skill");
        assertThat(noSkills.outcome()).isEqualTo(AgentSkillActivationOutcome.SKILL_UNAVAILABLE);

        fixture.assignmentService.assignToAgent("agent-1", "valid-skill", true);
        AgentSkillActivationResult missing = fixture.activationService.activateSkill("conversation-1", "missing-skill");
        assertThat(missing.outcome()).isEqualTo(AgentSkillActivationOutcome.SKILL_UNAVAILABLE);
    }

    private Path seedSkills(Fixture fixture) throws Exception {
        Path valid = Files.createDirectories(fixture.skillsRoot.resolve("valid-skill"));
        Files.writeString(valid.resolve("SKILL.md"), """
            ---
            name: valid-skill
            description: Use for valid operations.
            ---
            # Valid Skill
            Follow this workflow.
            """, StandardCharsets.UTF_8);
        Files.createDirectories(valid.resolve("scripts"));
        Files.writeString(valid.resolve("scripts/run.py"), "python3 scripts/run.py\n", StandardCharsets.UTF_8);
        Files.createDirectories(valid.resolve("references"));
        Files.writeString(valid.resolve("references/guide.md"), "guide\n", StandardCharsets.UTF_8);
        Files.createDirectories(valid.resolve("assets"));
        Files.writeString(valid.resolve("assets/template.txt"), "template\n", StandardCharsets.UTF_8);

        Path invalid = Files.createDirectories(fixture.skillsRoot.resolve("invalid-skill"));
        Files.writeString(invalid.resolve("SKILL.md"), """
            ---
            name: invalid-skill
            ---
            Missing description.
            """, StandardCharsets.UTF_8);
        return valid;
    }

    private Fixture fixture() throws Exception {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        ObjectMapper objectMapper = new ObjectMapper();
        Path magentaRoot = tempDir.resolve(".magenta");
        Path skillsRoot = Files.createDirectories(magentaRoot.resolve("skills"));
        AgentSkillRepositoryService repositoryService = new AgentSkillRepositoryService(new MagentaRootProperties(magentaRoot));
        AgentSkillRepository skillRepository = new AgentSkillRepository(jdbcTemplate, objectMapper);
        AgentSkillParser parser = new AgentSkillParser();
        AgentSkillCatalogService catalogService = new AgentSkillCatalogService(repositoryService, parser, skillRepository);
        AgentSkillAssignmentRepository assignmentRepository = new AgentSkillAssignmentRepository(jdbcTemplate);
        AgentProfileService agentProfileService = mock(AgentProfileService.class);
        when(agentProfileService.get("agent-1")).thenReturn(new AgentProfile(
            "agent-1",
            "Agent One",
            AgentProfileStatus.ACTIVE,
            null,
            null,
            List.of(),
            List.of(),
            false,
            null,
            null
        ));
        AgentSkillAssignmentService assignmentService = new AgentSkillAssignmentService(
            assignmentRepository,
            catalogService,
            agentProfileService
        );
        AgentSkillAgentContextResolver contextResolver = mock(AgentSkillAgentContextResolver.class);
        when(contextResolver.resolveAgentId("conversation-1")).thenReturn(Optional.of("agent-1"));
        when(contextResolver.resolveAgentId("conversation-2")).thenReturn(Optional.of("agent-2"));
        when(contextResolver.resolveConversationId("conversation-1")).thenReturn(Optional.of("conversation-1"));
        when(contextResolver.resolveConversationId("conversation-2")).thenReturn(Optional.of("conversation-2"));
        AgentSkillRuntimeCatalogService runtimeCatalogService = new AgentSkillRuntimeCatalogService(
            catalogService,
            assignmentService,
            contextResolver
        );
        AgentSkillActivationService activationService = new AgentSkillActivationService(
            new SkillSnapshotRepository(jdbcTemplate),
            mock(SkillTrustService.class),
            mock(SkillResolutionService.class)
        );

        return new Fixture(skillsRoot, catalogService, assignmentService, runtimeCatalogService, activationService);
    }

    private JdbcTemplate jdbcTemplate() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
            "jdbc:sqlite::memory:?foreign_keys=true",
            true
        );
        return new JdbcTemplate(dataSource);
    }

    private record Fixture(
        Path skillsRoot,
        AgentSkillCatalogService catalogService,
        AgentSkillAssignmentService assignmentService,
        AgentSkillRuntimeCatalogService runtimeCatalogService,
        AgentSkillActivationService activationService
    ) { }
}
