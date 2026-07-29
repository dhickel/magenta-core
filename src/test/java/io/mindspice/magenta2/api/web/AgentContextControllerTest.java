package io.mindspice.magenta2.api.web;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.mindspice.magenta2.ai.orchestration.agents.AgentProfile;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileStatus;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.ContextBinding;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.ContextDefault;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextService;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextService.ContextLookup;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextService.ContextOutcome;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextService.ContextSnapshot;
import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationRepository;
import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationRepository.MaterializationRecord;
import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationService;
import io.mindspice.magenta2.ai.orchestration.workspaces.MaterializationLocation;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentContextControllerTest {

    private static final String AGENT_ID = "test-agent";
    private static final String MISSING_AGENT_ID = "missing-agent";

    @TempDir
    Path tempDir;

    private StubAgentProfileService profileService;
    private StubAgentContextService contextService;
    private AgentContextController controller;

    @BeforeEach
    void setUp() {
        profileService = new StubAgentProfileService();
        contextService = new StubAgentContextService();
        controller = new AgentContextController(profileService, contextService);
    }

    // ── JSON endpoints: 404 for missing agent ──

    @Test
    void summaryReturns404ForMissingAgent() {
        ResponseEntity<Map<String, Object>> response = controller.summary(MISSING_AGENT_ID);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsKey("reason");
    }

    @Test
    void getDefaultReturns404ForMissingAgent() {
        ResponseEntity<Map<String, Object>> response = controller.getDefault(MISSING_AGENT_ID);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsKey("reason");
    }

    @Test
    void listMaterializationsReturns404ForMissingAgent() {
        ResponseEntity<Map<String, Object>> response = controller.listMaterializations(MISSING_AGENT_ID);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsKey("reason");
    }

    @Test
    void listBindingsReturns404ForMissingAgent() {
        ResponseEntity<Map<String, Object>> response = controller.listBindings(MISSING_AGENT_ID);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsKey("reason");
    }

    // ── JSON endpoints: 200 success ──

    @Test
    void summaryReturns200WithPayload() {
        profileService.setProfile(AGENT_ID);
        ResponseEntity<Map<String, Object>> response = controller.summary(AGENT_ID);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKeys("agentId", "default", "defaultRecord", "materializationCount");
    }

    @Test
    void getDefaultReturns200WithPayload() {
        profileService.setProfile(AGENT_ID);
        ResponseEntity<Map<String, Object>> response = controller.getDefault(AGENT_ID);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKeys("agentId", "available");
    }

    @Test
    void listMaterializationsReturns200WithPayload() {
        profileService.setProfile(AGENT_ID);
        ResponseEntity<Map<String, Object>> response = controller.listMaterializations(AGENT_ID);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKeys("agentId", "materializations");
    }

    @Test
    void listBindingsReturns200WithPayload() {
        profileService.setProfile(AGENT_ID);
        ResponseEntity<Map<String, Object>> response = controller.listBindings(AGENT_ID);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKeys("agentId", "bindings");
    }

    // ── Fragment endpoints: 404 for missing agent ──

    @Test
    void contextPanelReturns404FragmentForMissingAgent() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        String fragment = controller.contextPanel(MISSING_AGENT_ID, response);
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(fragment).contains("Not Found");
    }

    @Test
    void editDefaultReturns404FragmentForMissingAgent() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        String fragment = controller.editDefault(MISSING_AGENT_ID, response);
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(fragment).contains("Not Found");
    }

    @Test
    void materializationsPanelReturns404FragmentForMissingAgent() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        String fragment = controller.materializationsPanel(MISSING_AGENT_ID, response);
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(fragment).contains("Not Found");
    }

    // ── Fragment endpoints: 503 for service failure ──

    @Test
    void contextPanelReturns503FragmentWhenServiceFails() {
        profileService.setProfile(AGENT_ID);
        contextService.setFailSnapshot(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        String fragment = controller.contextPanel(AGENT_ID, response);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(fragment).contains("Unavailable");
    }

    @Test
    void editDefaultReturns503FragmentWhenServiceFails() {
        profileService.setProfile(AGENT_ID);
        contextService.setFailGetDefault(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        String fragment = controller.editDefault(AGENT_ID, response);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(fragment).contains("Unavailable");
    }

    @Test
    void materializationsPanelReturns503FragmentWhenServiceFails() {
        profileService.setProfile(AGENT_ID);
        contextService.setFailMaterializations(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        String fragment = controller.materializationsPanel(AGENT_ID, response);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(fragment).contains("Unavailable");
    }

    // ── Fragment endpoint: 400 for validation ──

    @Test
    void provisionMaterializationReturns400ForInvalidInput() {
        profileService.setProfile(AGENT_ID);
        contextService.setFailProvisionWith(new IllegalArgumentException("project key is required"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        String fragment = controller.provisionMaterialization(AGENT_ID, "", null, response);
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(fragment).contains("Invalid");
    }

    // ── Fragment endpoint: 409 for conflict ──

    @Test
    void provisionMaterializationReturns409ForConflict() {
        profileService.setProfile(AGENT_ID);
        contextService.setFailProvisionWith(
                new AgentContextService.OperationConflictException("already provisioned"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        String fragment = controller.provisionMaterialization(AGENT_ID, "proj", null, response);
        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(fragment).contains("Conflict");
    }

    // ── Safe fragments: no sensitive details ──

    @Test
    void errorFragmentsContainNoSensitiveDetails() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        String fragment = controller.contextPanel(MISSING_AGENT_ID, response);
        assertThat(fragment).doesNotContain("Exception");
        assertThat(fragment).doesNotContain("stack");
        assertThat(fragment).doesNotContain("SQL");
        assertThat(fragment).doesNotContain("/home/");
        assertThat(fragment).doesNotContain("jdbc");
    }

    // ── Stubs ──

    private static final class StubAgentProfileService extends AgentProfileService {
        private String presentId;

        StubAgentProfileService() {
            super(null, null, null, null, null, null, null);
        }

        void setProfile(String id) {
            this.presentId = id;
        }

        @Override
        public Optional<AgentProfile> find(String id) {
            if (presentId != null && presentId.equals(id)) {
                return Optional.of(new AgentProfile(
                        id, "Test", AgentProfileStatus.ACTIVE, "test-model",
                        null, List.of(), List.of(), true, null, null));
            }
            return Optional.empty();
        }
    }

    private static class StubAgentContextService extends AgentContextService {
        private boolean failSnapshot;
        private boolean failGetDefault;
        private boolean failMaterializations;
        private RuntimeException failProvisionWith;

        StubAgentContextService() {
            super(mock(AgentContextRepository.class),
                  mock(AgentMaterializationRepository.class),
                  mock(AgentMaterializationService.class),
                  mock(WorkspaceDirectoryService.class));
        }

        void setFailSnapshot(boolean fail) { this.failSnapshot = fail; }
        void setFailGetDefault(boolean fail) { this.failGetDefault = fail; }
        void setFailMaterializations(boolean fail) { this.failMaterializations = fail; }
        void setFailProvisionWith(RuntimeException ex) { this.failProvisionWith = ex; }

        @Override
        public ContextSnapshot snapshot(String agentId) {
            if (failSnapshot) throw new RuntimeException("snapshot unavailable");
            return new ContextSnapshot(null, List.of(), List.of());
        }

        @Override
        public ContextLookup<ContextDefault> getDefault(String agentId) {
            if (failGetDefault) throw new RuntimeException("default unavailable");
            return new ContextLookup<>(ContextOutcome.UNAVAILABLE, null);
        }

        @Override
        public List<MaterializationRecord> materializations(String agentId) {
            if (failMaterializations) throw new RuntimeException("materializations unavailable");
            return List.of();
        }

        @Override
        public List<MaterializationRecord> readyMaterializations(String agentId) {
            return List.of();
        }

        @Override
        public List<ContextBinding> listBindings(String agentId) {
            return List.of();
        }

        @Override
        public MaterializationLocation provisionProject(String agentId, String projectId, String operationKey) {
            if (failProvisionWith != null) throw failProvisionWith;
            return null;
        }
    }
}
