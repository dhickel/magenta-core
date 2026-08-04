package io.mindspice.magenta2.ai.skills;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentSkillParserTest {
    private final AgentSkillParser parser = new AgentSkillParser();

    @Test
    void parsesValidSkillMarkdown() {
        String markdown = """
            ---
            name: pdf-processing
            description: Extract text from PDFs when users request PDF processing.
            license: Apache-2.0
            compatibility: Requires Python 3.12+
            metadata:
              author: magenta
            allowed-tools: Bash(python3:*)
            ---
            # PDF Skill
            Use this for PDFs.
            """;

        AgentSkillParseResult result = parser.parse(markdown, "pdf-processing", "pdf-processing/SKILL.md");

        assertThat(result.status()).isEqualTo(AgentSkillStatus.VALID);
        assertThat(result.frontmatter()).isNotNull();
        assertThat(result.frontmatter().name()).isEqualTo("pdf-processing");
        assertThat(result.frontmatter().description()).contains("PDF");
        assertThat(result.frontmatter().metadata()).containsEntry("author", "magenta");
        assertThat(result.frontmatter().allowedTools()).isEqualTo("Bash(python3:*)");
        assertThat(result.body()).startsWith("# PDF Skill");
        assertThat(result.diagnostics()).isEmpty();
    }

    @Test
    void marksMissingDescriptionAsInvalid() {
        String markdown = """
            ---
            name: data-cleanup
            ---
            # Missing description
            """;

        AgentSkillParseResult result = parser.parse(markdown, "data-cleanup", "data-cleanup/SKILL.md");

        assertThat(result.status()).isEqualTo(AgentSkillStatus.INVALID);
        assertThat(result.diagnostics())
            .extracting(AgentSkillDiagnostic::code)
            .contains(AgentSkillDiagnosticCode.SKILL_DESCRIPTION_MISSING);
    }

    @Test
    void allowsNameMismatchWithWarning() {
        String markdown = """
            ---
            name: another-name
            description: Valid description.
            ---
            body
            """;

        AgentSkillParseResult result = parser.parse(markdown, "directory-name", "directory-name/SKILL.md");

        assertThat(result.status()).isEqualTo(AgentSkillStatus.WARNING);
        assertThat(result.diagnostics())
            .extracting(AgentSkillDiagnostic::code)
            .contains(AgentSkillDiagnosticCode.SKILL_NAME_DIRECTORY_MISMATCH);
        assertThat(result.frontmatter().name()).isEqualTo("another-name");
    }

    @Test
    void appliesYamlColonFallbackForDescription() {
        String markdown = """
            ---
            name: fallback-skill
            description: Use this skill when: user asks about PDFs
            ---
            body
            """;

        AgentSkillParseResult result = parser.parse(markdown, "fallback-skill", "fallback-skill/SKILL.md");

        assertThat(result.status()).isEqualTo(AgentSkillStatus.WARNING);
        assertThat(result.frontmatter().description()).contains("user asks about PDFs");
        assertThat(result.diagnostics())
            .extracting(AgentSkillDiagnostic::code)
            .contains(AgentSkillDiagnosticCode.SKILL_FRONTMATTER_YAML_FALLBACK_USED);
    }

    @Test
    void reportsUnparseableYamlAsInvalid() {
        String markdown = """
            ---
            name: broken
            description: "unterminated
            ---
            body
            """;

        AgentSkillParseResult result = parser.parse(markdown, "broken", "broken/SKILL.md");

        assertThat(result.status()).isEqualTo(AgentSkillStatus.INVALID);
        assertThat(result.diagnostics())
            .extracting(AgentSkillDiagnostic::code)
            .contains(AgentSkillDiagnosticCode.SKILL_FRONTMATTER_YAML_INVALID);
    }

    @Test
    void marksMissingNameAsInvalid() {
        String markdown = """
            ---
            description: Description exists but name is missing.
            ---
            body
            """;

        AgentSkillParseResult result = parser.parse(markdown, "missing-name", "missing-name/SKILL.md");

        assertThat(result.status()).isEqualTo(AgentSkillStatus.INVALID);
        assertThat(result.diagnostics())
            .extracting(AgentSkillDiagnostic::code)
            .contains(AgentSkillDiagnosticCode.SKILL_NAME_MISSING);
    }

    @Test
    void invalidNameShapeAndTooLongProduceWarnings() {
        String tooLongAndInvalid = "a".repeat(65) + "_bad";
        String markdown = """
            ---
            name: %s
            description: Valid description.
            ---
            body
            """.formatted(tooLongAndInvalid);

        AgentSkillParseResult result = parser.parse(markdown, "directory-name", "directory-name/SKILL.md");

        assertThat(result.status()).isEqualTo(AgentSkillStatus.WARNING);
        assertThat(result.diagnostics())
            .extracting(AgentSkillDiagnostic::code)
            .contains(
                AgentSkillDiagnosticCode.SKILL_NAME_TOO_LONG,
                AgentSkillDiagnosticCode.SKILL_NAME_INVALID,
                AgentSkillDiagnosticCode.SKILL_NAME_DIRECTORY_MISMATCH
            );
    }
}
