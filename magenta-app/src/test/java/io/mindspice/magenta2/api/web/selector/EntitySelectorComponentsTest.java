package io.mindspice.magenta2.api.web.selector;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EntitySelectorComponentsTest {
    @Test
    void workspaceAndWorkAreaSelectorKindsAreRetired() {
        assertThat(Arrays.stream(EntityKind.values()).map(Enum::name))
            .doesNotContain("WORKSPACE", "WORK_AREA");
    }
}
