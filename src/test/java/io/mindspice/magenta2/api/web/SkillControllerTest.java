package io.mindspice.magenta2.api.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfile;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileStatus;
import io.mindspice.magenta2.ai.skills.AgentSkillAssignmentRepository;
import io.mindspice.magenta2.ai.skills.AgentSkillTargetType;
import io.mindspice.magenta2.ai.skills.CandidateVersion;
import io.mindspice.magenta2.ai.skills.SkillProvenance;
import io.mindspice.magenta2.ai.skills.SkillScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SkillControllerTest {
    private static final Path MAGENTA_ROOT = Path.of(
        System.getProperty("java.io.tmpdir"),
        "magenta-skill-controller-test-root-" + UUID.randomUUID()
    );
    private static final Path DB_PATH = MAGENTA_ROOT.resolve("magenta.sqlite");
    private static final Path AI_CONFIG_PATH = MAGENTA_ROOT.resolve("config/ai-config.json");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AgentProfileService agentProfileService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AgentSkillAssignmentRepository assignmentRepository;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("magenta.root.path", () -> MAGENTA_ROOT.toString());
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB_PATH + "?foreign_keys=true");
        registry.add("app.ai.config-path", () -> aiConfigPath().toString());
    }

    @BeforeEach
    void setUp() throws Exception {
        deleteRecursively(MAGENTA_ROOT.resolve("skills"));
        Files.createDirectories(MAGENTA_ROOT.resolve("skills"));
        ensureAgent("agent-1", "Agent One");
        ensureAgent("agent-2", "Agent Two");
        mockMvc.perform(post("/api/skills/refresh")).andExpect(status().isOk());
    }

    @Test
    void refreshAndListIncludeMalformedSkillsWithDiagnostics() throws Exception {
        Path valid = Files.createDirectories(MAGENTA_ROOT.resolve("skills/valid-skill"));
        Files.writeString(valid.resolve("SKILL.md"), """
            ---
            name: valid-skill
            description: Valid skill.
            ---
            # Valid
            """);
        Path malformed = Files.createDirectories(MAGENTA_ROOT.resolve("skills/malformed-skill"));
        Files.writeString(malformed.resolve("SKILL.md"), """
            ---
            name: malformed-skill
            ---
            # Missing Description
            """);

        mockMvc.perform(post("/api/skills/refresh"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.skills", hasSize(2)))
            .andExpect(jsonPath("$.invalidCount").value(1));

        mockMvc.perform(get("/api/skills/malformed-skill/diagnostics"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.diagnostics[0].code").value("SKILL_DESCRIPTION_MISSING"));
    }

    @Test
    void saveSkillMarkdownTriggersCatalogRefresh() throws Exception {
        createSkill("refresh-skill", "Before refresh.");
        mockMvc.perform(post("/api/skills/refresh")).andExpect(status().isOk());

        mockMvc.perform(put("/api/skills/refresh-skill/files/text")
                .param("path", "SKILL.md")
                .contentType(APPLICATION_JSON)
                .content(json(Map.of(
                    "content", """
                        ---
                        name: refresh-skill
                        description: After refresh.
                        ---
                        # Refresh
                        """
                ))))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/skills/refresh-skill"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.description").value("After refresh."));
    }

    @Test
    void fileTreeViewSaveAndCreateRoutesWorkForTextFiles() throws Exception {
        createSkill("file-skill", "File operations.");
        mockMvc.perform(post("/api/skills/refresh")).andExpect(status().isOk());

        mockMvc.perform(post("/api/skills/file-skill/files")
                .contentType(APPLICATION_JSON)
                .content(json(Map.of(
                    "parentPath", ".",
                    "fileName", "notes.txt",
                    "content", "hello"
                ))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.path").value("notes.txt"));

        mockMvc.perform(get("/api/skills/file-skill/files"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.entries[?(@.name == 'notes.txt')]").exists());

        mockMvc.perform(get("/api/skills/file-skill/files/view").param("path", "notes.txt"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.text").value(true))
            .andExpect(jsonPath("$.content").value("hello"));

        mockMvc.perform(put("/api/skills/file-skill/files/text")
                .param("path", "notes.txt")
                .contentType(APPLICATION_JSON)
                .content(json(Map.of("content", "updated"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").value("updated"));
    }

    @Test
    void traversalPathsAreRejected() throws Exception {
        createSkill("path-skill", "Path guards.");
        mockMvc.perform(post("/api/skills/refresh")).andExpect(status().isOk());

        mockMvc.perform(get("/api/skills/path-skill/files/view")
                .param("path", "../outside.txt"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", containsString("path escapes skill directory")));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void symlinkPathsAreRejected() throws Exception {
        createSkill("symlink-skill", "Symlink guards.");
        Path linkTarget = Files.writeString(MAGENTA_ROOT.resolve("outside-link-target.txt"), "secret");
        Path skillDir = MAGENTA_ROOT.resolve("skills/symlink-skill");
        Files.createSymbolicLink(skillDir.resolve("link.txt"), linkTarget);
        mockMvc.perform(post("/api/skills/refresh")).andExpect(status().isOk());

        mockMvc.perform(get("/api/skills/symlink-skill/files/view")
                .param("path", "link.txt"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", containsString("symbolic links are not allowed")));
    }

    @Test
    void assignmentEndpointsRejectUnknownAgentAndUnknownSkill() throws Exception {
        createSkill("assign-skill", "Assignment routes.");
        mockMvc.perform(post("/api/skills/refresh")).andExpect(status().isOk());

        mockMvc.perform(post("/api/skills/assign-skill/assignments/agents/missing-agent"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error", containsString("not found")));

        mockMvc.perform(post("/api/skills/missing-skill/assignments/agents/agent-1"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error", containsString("skill not found")));
    }

    @Test
    void duplicateAssignmentIsIdempotentAndUnassignRouteRemovesIt() throws Exception {
        createSkill("dedupe-skill", "Assignment dedupe.");
        mockMvc.perform(post("/api/skills/refresh")).andExpect(status().isOk());

        mockMvc.perform(post("/api/skills/dedupe-skill/assignments/agents/agent-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true));
        mockMvc.perform(post("/api/skills/dedupe-skill/assignments/agents/agent-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true));

        mockMvc.perform(get("/api/skills/dedupe-skill/assignments"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.assignments", hasSize(1)));

        mockMvc.perform(delete("/api/skills/dedupe-skill/assignments/agents/agent-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.removed").value(true));
    }

    @Test
    void candidateCollectionsHistoryAndOverlayListingsUsePersistedControlPlaneState() throws Exception {
        CandidateVersion candidate = assignmentRepository.insertCandidateVersion(new CandidateVersion(
            null, "control-plane-skill", SkillScope.GLOBAL, "global",
            SkillProvenance.GLOBAL_PACKAGE, 1, "a".repeat(64), java.time.Instant.now()));
        assignmentRepository.upsertOverlay(candidate.id(), candidate.logicalKey(),
            AgentSkillTargetType.AGENT, "agent-1", true, "skill-controller-overlay", "operator");

        mockMvc.perform(get("/api/skills/candidates"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.logicalKey == 'control-plane-skill')]").exists());
        mockMvc.perform(get("/api/skills/candidates/control-plane-skill"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].contentDigest").value("a".repeat(64)));
        mockMvc.perform(get("/api/skills/overlays"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.logicalKey == 'control-plane-skill')].targetKey").value("agent-1"));
        mockMvc.perform(get("/api/skills/overlays/control-plane-skill"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].enabled").value(true));
    }

    @Test
    void minimalFragmentRoutesRender() throws Exception {
        createSkill("fragment-skill", "Fragment routes.");
        mockMvc.perform(post("/api/skills/refresh")).andExpect(status().isOk());

        mockMvc.perform(get("/skills"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("id=\"skills-page\"")))
            .andExpect(content().string(containsString("href=\"/skills\" class=\"sidenav-item active\"")))
            .andExpect(content().string(containsString("hx-get=\"/skills/_list\"")));
        mockMvc.perform(get("/skills/_list"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("fragment-skill")))
            .andExpect(content().string(containsString("skill-status-badge")))
            .andExpect(content().string(containsString("0 assigned")));
        mockMvc.perform(get("/skills/_detail/fragment-skill"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("fragment-skill")))
            .andExpect(content().string(containsString("skills-file-region")))
            .andExpect(content().string(containsString("skills-file-viewer")))
            .andExpect(content().string(containsString("skills-assignment-panel")))
            .andExpect(content().string(containsString("name=\"content\"")));
    }

    @Test
    void webFragmentsFilterShowDiagnosticsAndDirectoryOverview() throws Exception {
        createSkill("render-skill", "Visible render skill.");
        Files.createDirectories(MAGENTA_ROOT.resolve("skills/render-skill/references"));
        Path malformed = Files.createDirectories(MAGENTA_ROOT.resolve("skills/render-broken"));
        Files.writeString(malformed.resolve("SKILL.md"), """
            ---
            name: render-broken
            ---
            # Missing Description
            """);
        mockMvc.perform(post("/api/skills/refresh")).andExpect(status().isOk());

        mockMvc.perform(get("/skills/_list").param("skillFilter", "render"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("render-skill")))
            .andExpect(content().string(containsString("render-broken")))
            .andExpect(content().string(containsString("valid 1")))
            .andExpect(content().string(containsString("invalid 1")));

        mockMvc.perform(get("/skills/_detail/render-broken"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("SKILL_DESCRIPTION_MISSING")))
            .andExpect(content().string(containsString("Create references/")));

        mockMvc.perform(get("/skills/_detail/render-skill"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("references/")))
            .andExpect(content().string(containsString("present")))
            .andExpect(content().string(containsString("scripts/")))
            .andExpect(content().string(containsString("absent")));
    }

    @Test
    void webEditorSaveAndAddFileFlowsRefreshDetail() throws Exception {
        createSkill("web-edit-skill", "Before web edit.");
        mockMvc.perform(post("/api/skills/refresh")).andExpect(status().isOk());

        mockMvc.perform(put("/skills/_files/web-edit-skill/text")
                .param("path", "SKILL.md")
                .param("skillFilter", "web-edit")
                .param("content", """
                    ---
                    name: web-edit-skill
                    description: After web edit.
                    ---
                    # Web Edit
                    """))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("After web edit.")))
            .andExpect(content().string(containsString("skill-editor-textarea")))
            .andExpect(content().string(containsString("id=\"skills-list\"")))
            .andExpect(content().string(containsString("hx-swap-oob=\"true\"")))
            .andExpect(content().string(containsString("0 assigned")));

        Files.writeString(MAGENTA_ROOT.resolve("skills/web-edit-skill/SKILL.md"), """
            ---
            name: web-edit-skill
            description: After web refresh.
            ---
            # Web Edit
            """);
        mockMvc.perform(post("/skills/_detail/web-edit-skill/refresh")
                .param("selectedPath", "SKILL.md")
                .param("skillFilter", "web-edit"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("After web refresh.")))
            .andExpect(content().string(containsString("id=\"skills-list\"")))
            .andExpect(content().string(containsString("hx-swap-oob=\"true\"")));

        mockMvc.perform(post("/skills/_directories/web-edit-skill")
                .param("directoryName", "references"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("references/")))
            .andExpect(content().string(containsString("present")));

        mockMvc.perform(post("/skills/_files/web-edit-skill")
                .param("parentPath", "references")
                .param("fileName", "notes.md")
                .param("content", "reference notes"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("notes.md")))
            .andExpect(content().string(containsString("reference notes")));
    }

    @Test
    void webAssignmentControlsUseSelectorAndUpdateAssignmentPanel() throws Exception {
        createSkill("web-assign-skill", "Assign from web.");
        mockMvc.perform(post("/api/skills/refresh")).andExpect(status().isOk());

        mockMvc.perform(get("/skills/_detail/web-assign-skill"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("entity-selector-agent-agentId")))
            .andExpect(content().string(containsString("hx-post=\"/skills/_assignments/web-assign-skill\"")));

        mockMvc.perform(post("/skills/_assignments/web-assign-skill")
                .param("agentId", "agent-1")
                .param("skillFilter", "web-assign"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Agent One")))
            .andExpect(content().string(containsString("Unassign")))
            .andExpect(content().string(containsString("id=\"skills-list\"")))
            .andExpect(content().string(containsString("hx-swap-oob=\"true\"")))
            .andExpect(content().string(containsString("1 assigned")));

        mockMvc.perform(delete("/skills/_assignments/web-assign-skill/agent-1")
                .param("skillFilter", "web-assign"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("No agents assigned.")))
            .andExpect(content().string(containsString("id=\"skills-list\"")))
            .andExpect(content().string(containsString("hx-swap-oob=\"true\"")))
            .andExpect(content().string(containsString("0 assigned")));
    }

    @Test
    void guidedCreationWritesSkillMarkdownAndOptionalResourceFiles() throws Exception {
        mockMvc.perform(post("/skills/_create")
                .param("skillName", "guided-skill")
                .param("description", "Use when guided creation is requested.")
                .param("instructions", "1. Ask for scope.\n2. Perform the workflow.")
                .param("createReferences", "true")
                .param("referenceFileName", "REFERENCE.md")
                .param("referenceContent", "Reference body")
                .param("createScripts", "true")
                .param("scriptFileName", "README.md")
                .param("scriptContent", "Scripts are not executed by this UI.")
                .param("createAssets", "true"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("guided-skill")))
            .andExpect(content().string(containsString("Use when guided creation is requested.")))
            .andExpect(content().string(containsString("hx-swap-oob=\"true\"")));

        mockMvc.perform(get("/api/skills/guided-skill"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.description").value("Use when guided creation is requested."))
            .andExpect(jsonPath("$.hasReferences").value(true))
            .andExpect(jsonPath("$.hasScripts").value(true))
            .andExpect(jsonPath("$.hasAssets").value(true));

        mockMvc.perform(get("/api/skills/guided-skill/files/view").param("path", "references/REFERENCE.md"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").value("Reference body"));
    }

    private void createSkill(String slug, String description) throws Exception {
        Path dir = Files.createDirectories(MAGENTA_ROOT.resolve("skills").resolve(slug));
        Files.writeString(dir.resolve("SKILL.md"), """
            ---
            name: %s
            description: %s
            ---
            # %s
            """.formatted(slug, description, slug));
    }

    private void ensureAgent(String id, String name) {
        boolean exists = agentProfileService.list().stream().anyMatch(agent -> id.equals(agent.id()));
        if (exists) {
            return;
        }
        agentProfileService.create(new AgentProfile(
            id,
            name,
            AgentProfileStatus.ACTIVE,
            null,
            null,
            List.of(),
            List.of(),
            false,
            null,
            null
        ));
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private static Path aiConfigPath() {
        try {
            Files.createDirectories(AI_CONFIG_PATH.getParent().resolve("prompts"));
            Files.writeString(AI_CONFIG_PATH.getParent().resolve("prompts/system.md"), "Skill controller test agent.");
            Files.writeString(AI_CONFIG_PATH, """
                {
                  "defaultAgent": "magenta",
                  "defaultModel": "local-qwen",
                  "summaryModel": "local-qwen",
                  "planningModel": "local-qwen",
                  "compactionModel": "local-qwen",
                  "contextBufferPercent": 33,
                  "unsafeAllowWildcardShellCommands": false,
                  "models": {
                    "local-qwen": {
                      "remoteModelName": "qwen3.6:35b",
                      "remoteEndpoint": "http://127.0.0.1:11434",
                      "endpointType": "OLLAMA",
                      "contextLength": 32000,
                      "thinkLevel": 0
                    }
                  },
                  "agents": {
                    "magenta": {
                      "model": "local-qwen",
                      "systemPrompt": "prompts/system.md",
                      "approvedTools": [],
                      "allowedShellCommands": []
                    }
                  }
                }
                """);
            return AI_CONFIG_PATH;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create skill test AI config", exception);
        }
    }

    private void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new IllegalStateException("Unable to delete test path " + path, exception);
                }
            });
        }
    }
}
