package io.mindspice.magenta2.ai.chat.plan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkTypeProfileServiceTest {

    private final WorkTypeProfileService service = new WorkTypeProfileService();

    @Test
    void getSystemPromptAppendForNullReturnsEmpty() {
        assertThat(service.getSystemPromptAppend(null)).isEmpty();
    }

    @Test
    void codingCentricPromptContainsExpectedKeywords() {
        String prompt = service.getSystemPromptAppend(WorkTypeProfile.CODING_CENTRIC);
        assertThat(prompt).contains("coding-centric");
        assertThat(prompt).contains("repository evidence");
        assertThat(prompt).contains("code changes");
        assertThat(prompt).contains("startup smoke checks");
        assertThat(prompt).contains("existing project patterns");
    }

    @Test
    void dataCentricPromptContainsExpectedKeywords() {
        String prompt = service.getSystemPromptAppend(WorkTypeProfile.DATA_CENTRIC);
        assertThat(prompt).contains("data-centric");
        assertThat(prompt).contains("data contracts");
        assertThat(prompt).contains("schema clarity");
        assertThat(prompt).contains("source provenance");
        assertThat(prompt).contains("validation");
        assertThat(prompt).contains("transformation correctness");
        assertThat(prompt).contains("missing or dirty data");
    }

    @Test
    void researchCentricPromptContainsExpectedKeywords() {
        String prompt = service.getSystemPromptAppend(WorkTypeProfile.RESEARCH_CENTRIC);
        assertThat(prompt).contains("research-centric");
        assertThat(prompt).contains("source quality");
        assertThat(prompt).contains("recency");
        assertThat(prompt).contains("citations");
        assertThat(prompt).contains("uncertainty tracking");
        assertThat(prompt).contains("evidence from inference");
        assertThat(prompt).contains("unsupported conclusions");
    }

    @Test
    void promptsAreNonEmptyForAllProfiles() {
        for (WorkTypeProfile profile : WorkTypeProfile.values()) {
            String prompt = service.getSystemPromptAppend(profile);
            assertThat(prompt).isNotEmpty();
        }
    }

    @Test
    void getSystemPromptAppendForPlanMapsFromPromptProfileString() {
        String prompt = service.getSystemPromptAppendForPlan("CODING_CENTRIC");
        assertThat(prompt).contains("coding-centric");

        String legacyPrompt = service.getSystemPromptAppendForPlan("RESEARCH");
        assertThat(legacyPrompt).contains("research-centric");
    }

    @Test
    void getSystemPromptAppendForPlanReturnsEmptyForNullAndBlank() {
        assertThat(service.getSystemPromptAppendForPlan(null)).isEmpty();
        assertThat(service.getSystemPromptAppendForPlan("")).isEmpty();
        assertThat(service.getSystemPromptAppendForPlan("   ")).isEmpty();
    }
}
