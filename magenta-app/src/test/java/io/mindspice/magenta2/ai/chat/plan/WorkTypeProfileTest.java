package io.mindspice.magenta2.ai.chat.plan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkTypeProfileTest {

    @Test
    void fromStringParsesCanonicalNames() {
        assertThat(WorkTypeProfile.fromString("CODING_CENTRIC"))
            .isEqualTo(WorkTypeProfile.CODING_CENTRIC);
        assertThat(WorkTypeProfile.fromString("DATA_CENTRIC"))
            .isEqualTo(WorkTypeProfile.DATA_CENTRIC);
        assertThat(WorkTypeProfile.fromString("RESEARCH_CENTRIC"))
            .isEqualTo(WorkTypeProfile.RESEARCH_CENTRIC);
    }

    @Test
    void fromStringParsesLegacyPromptProfileValues() {
        assertThat(WorkTypeProfile.fromString("CODING"))
            .isEqualTo(WorkTypeProfile.CODING_CENTRIC);
        assertThat(WorkTypeProfile.fromString("RESEARCH"))
            .isEqualTo(WorkTypeProfile.RESEARCH_CENTRIC);
    }

    @Test
    void fromStringMapsUnknownLegacyValuesToDataCentric() {
        assertThat(WorkTypeProfile.fromString("WRITING"))
            .isEqualTo(WorkTypeProfile.DATA_CENTRIC);
        assertThat(WorkTypeProfile.fromString("TECHNICAL_WRITING"))
            .isEqualTo(WorkTypeProfile.DATA_CENTRIC);
        assertThat(WorkTypeProfile.fromString("VALIDATION"))
            .isEqualTo(WorkTypeProfile.DATA_CENTRIC);
        assertThat(WorkTypeProfile.fromString("MANAGEMENT"))
            .isEqualTo(WorkTypeProfile.DATA_CENTRIC);
        assertThat(WorkTypeProfile.fromString("GENERAL"))
            .isEqualTo(WorkTypeProfile.DATA_CENTRIC);
    }

    @Test
    void fromStringDefaultsToCodingCentricForNullAndBlank() {
        assertThat(WorkTypeProfile.fromString(null))
            .isEqualTo(WorkTypeProfile.CODING_CENTRIC);
        assertThat(WorkTypeProfile.fromString(""))
            .isEqualTo(WorkTypeProfile.CODING_CENTRIC);
        assertThat(WorkTypeProfile.fromString("   "))
            .isEqualTo(WorkTypeProfile.CODING_CENTRIC);
    }

    @Test
    void fromStringDefaultsToCodingCentricForUnknownValue() {
        assertThat(WorkTypeProfile.fromString("BOGUS"))
            .isEqualTo(WorkTypeProfile.DATA_CENTRIC); // falls through to default in switch
    }

    @Test
    void fromStringIsCaseInsensitive() {
        assertThat(WorkTypeProfile.fromString("coding_centric"))
            .isEqualTo(WorkTypeProfile.CODING_CENTRIC);
        assertThat(WorkTypeProfile.fromString("Data_Centric"))
            .isEqualTo(WorkTypeProfile.DATA_CENTRIC);
        assertThat(WorkTypeProfile.fromString("research"))
            .isEqualTo(WorkTypeProfile.RESEARCH_CENTRIC);
    }

    @Test
    void nameMethodReturnsCanonicalConstantNames() {
        assertThat(WorkTypeProfile.CODING_CENTRIC.name()).isEqualTo("CODING_CENTRIC");
        assertThat(WorkTypeProfile.DATA_CENTRIC.name()).isEqualTo("DATA_CENTRIC");
        assertThat(WorkTypeProfile.RESEARCH_CENTRIC.name()).isEqualTo("RESEARCH_CENTRIC");
    }
}
