package io.mindspice.magenta2.ai.skills;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta2.core.config.MagentaRootProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentSkillCatalogServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void refreshCatalogDiscoversValidAndMalformedSkillsAndPersistsMetadata() throws Exception {
        AgentSkillCatalogService service = service();
        Path skillsRoot = tempDir.resolve(".magenta/skills");
        Path valid = Files.createDirectories(skillsRoot.resolve("valid-skill"));
        Files.writeString(valid.resolve("SKILL.md"), """
            ---
            name: valid-skill
            description: Runs valid operations when user asks.
            metadata:
              author: test
            ---
            # Valid
            """, StandardCharsets.UTF_8);
        Files.createDirectories(valid.resolve("scripts"));
        Files.createDirectories(valid.resolve("references"));
        Files.createDirectories(valid.resolve("assets"));
        Path missingSkillMd = Files.createDirectories(skillsRoot.resolve("missing-markdown"));
        Path missingDescription = Files.createDirectories(skillsRoot.resolve("missing-description"));
        Files.writeString(missingDescription.resolve("SKILL.md"), """
            ---
            name: missing-description
            ---
            # Missing description
            """, StandardCharsets.UTF_8);
        Files.writeString(skillsRoot.resolve("README.md"), "not a skill", StandardCharsets.UTF_8);

        AgentSkillCatalogService.AgentSkillRefreshResult refresh = service.refreshCatalog();
        List<AgentSkill> skills = service.listAll();

        assertThat(refresh.validCount()).isEqualTo(1);
        assertThat(refresh.invalidCount()).isEqualTo(2);
        assertThat(skills).hasSize(3);

        AgentSkill validSkill = skills.stream().filter(skill -> skill.directorySlug().equals("valid-skill")).findFirst().orElseThrow();
        assertThat(validSkill.status()).isEqualTo(AgentSkillStatus.VALID);
        assertThat(validSkill.hasScripts()).isTrue();
        assertThat(validSkill.hasReferences()).isTrue();
        assertThat(validSkill.hasAssets()).isTrue();
        assertThat(validSkill.metadata()).containsEntry("author", "test");
        assertThat(validSkill.skillMdRootRelativePath()).isEqualTo("valid-skill/SKILL.md");
        assertThat(validSkill.contentHash()).isNotBlank();

        AgentSkill missingMarkdownSkill = skills.stream()
            .filter(skill -> skill.directorySlug().equals("missing-markdown"))
            .findFirst()
            .orElseThrow();
        assertThat(missingMarkdownSkill.status()).isEqualTo(AgentSkillStatus.INVALID);
        assertThat(missingMarkdownSkill.diagnostics())
            .extracting(AgentSkillDiagnostic::code)
            .contains(AgentSkillDiagnosticCode.SKILL_MD_MISSING);

        AgentSkill missingDescriptionSkill = skills.stream()
            .filter(skill -> skill.directorySlug().equals("missing-description"))
            .findFirst()
            .orElseThrow();
        assertThat(missingDescriptionSkill.status()).isEqualTo(AgentSkillStatus.INVALID);
        assertThat(missingDescriptionSkill.diagnostics())
            .extracting(AgentSkillDiagnostic::code)
            .contains(AgentSkillDiagnosticCode.SKILL_DESCRIPTION_MISSING);

        assertThat(missingSkillMd).exists();
    }

    @Test
    void refreshAfterEditUpdatesHashAndScanTimestamp() throws Exception {
        AgentSkillCatalogService service = service();
        Path skillDir = Files.createDirectories(tempDir.resolve(".magenta/skills/reload-skill"));
        Path skillMarkdown = skillDir.resolve("SKILL.md");
        Files.writeString(skillMarkdown, """
            ---
            name: reload-skill
            description: First version.
            ---
            Body v1
            """, StandardCharsets.UTF_8);

        service.refreshCatalog();
        AgentSkill first = service.listAll().stream()
            .filter(skill -> skill.directorySlug().equals("reload-skill"))
            .findFirst()
            .orElseThrow();
        String firstHash = first.contentHash();
        Instant firstScannedAt = first.lastScannedAt();

        Thread.sleep(5);
        Files.writeString(skillMarkdown, """
            ---
            name: reload-skill
            description: Second version after edit.
            ---
            Body v2
            """, StandardCharsets.UTF_8);

        service.refreshCatalog();
        AgentSkill second = service.listAll().stream()
            .filter(skill -> skill.directorySlug().equals("reload-skill"))
            .findFirst()
            .orElseThrow();

        assertThat(second.contentHash()).isNotEqualTo(firstHash);
        assertThat(second.description()).contains("Second version");
        assertThat(second.lastScannedAt()).isAfter(firstScannedAt);
    }

    @Test
    void pathConfinementRejectsTraversalAndSymlinkEscapes() throws Exception {
        AgentSkillRepositoryService repositoryService = new AgentSkillRepositoryService(
            new MagentaRootProperties(tempDir.resolve(".magenta"))
        );
        Path root = repositoryService.ensureSkillsRoot();
        Path skillDir = Files.createDirectories(root.resolve("safe-skill"));
        Files.writeString(skillDir.resolve("SKILL.md"), """
            ---
            name: safe-skill
            description: Safe
            ---
            body
            """, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> repositoryService.requireValidSlug("Unsafe"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("skillSlug");
        assertThatThrownBy(() -> repositoryService.resolveExistingRelativePath("safe-skill", "../outside.txt"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("escapes");

        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Path symlink = skillDir.resolve("escape");
        try {
            Files.createSymbolicLink(symlink, outside);
        } catch (UnsupportedOperationException exception) {
            return;
        }
        assertThatThrownBy(() -> repositoryService.resolveExistingRelativePath("safe-skill", "escape"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("symbolic links");
    }

    @Test
    void resolveRelativePathRejectsWritePathThroughSymlinkAncestor() throws Exception {
        AgentSkillRepositoryService repositoryService = new AgentSkillRepositoryService(
            new MagentaRootProperties(tempDir.resolve(".magenta"))
        );
        Path root = repositoryService.ensureSkillsRoot();
        Path skillDir = Files.createDirectories(root.resolve("safe-skill"));
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Path link = skillDir.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException exception) {
            return;
        }

        assertThatThrownBy(() -> repositoryService.resolveRelativePath(skillDir, "link/new.txt"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("symbolic links");
    }

    @Test
    void refreshCatalogMarksTopLevelSkillDirectorySymlinkInvalid() throws Exception {
        AgentSkillCatalogService service = service();
        Path skillsRoot = tempDir.resolve(".magenta/skills");
        Files.createDirectories(skillsRoot.resolve("valid-skill"));
        Files.writeString(skillsRoot.resolve("valid-skill/SKILL.md"), """
            ---
            name: valid-skill
            description: Valid
            ---
            body
            """, StandardCharsets.UTF_8);
        Path outside = Files.createDirectories(tempDir.resolve("outside-skill"));
        Path symlink = skillsRoot.resolve("linked-skill");
        try {
            Files.createSymbolicLink(symlink, outside);
        } catch (UnsupportedOperationException exception) {
            return;
        }

        service.refreshCatalog();
        AgentSkill linked = service.listAll().stream()
            .filter(skill -> skill.directorySlug().equals("linked-skill"))
            .findFirst()
            .orElseThrow();

        assertThat(linked.status()).isEqualTo(AgentSkillStatus.INVALID);
        assertThat(linked.diagnostics())
            .extracting(AgentSkillDiagnostic::code)
            .contains(AgentSkillDiagnosticCode.SKILL_SYMLINK_REJECTED);
    }

    private AgentSkillCatalogService service() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        AgentSkillRepository repository = new AgentSkillRepository(jdbcTemplate, new ObjectMapper());
        AgentSkillRepositoryService repositoryService = new AgentSkillRepositoryService(
            new MagentaRootProperties(tempDir.resolve(".magenta"))
        );
        return new AgentSkillCatalogService(repositoryService, new AgentSkillParser(), repository);
    }

    private JdbcTemplate jdbcTemplate() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
            "jdbc:sqlite::memory:?foreign_keys=true",
            true
        );
        return new JdbcTemplate(dataSource);
    }
}
