package io.mindspice.magenta2.api.web.selector;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EntitySelectorControllerTest {

    @Test
    void optionsReadsOnlyNamespacedSelectorContext() {
        CapturingLookupService lookup = new CapturingLookupService();
        EntitySelectorController controller = new EntitySelectorController(lookup, new EntitySelectorComponents());

        String html = controller.options("job", Map.of(
            "name", "jobId",
            "jobId", "typed",
            "agentId", "business-agent",
            "selectorContext.agentId", "context-agent",
            "selectorContext.projectId", "project-1"
        ));

        assertThat(lookup.lastQuery.get().q()).isEqualTo("typed");
        assertThat(lookup.lastQuery.get().context())
            .containsEntry("agentId", "context-agent")
            .containsEntry("projectId", "project-1")
            .doesNotContainEntry("agentId", "business-agent");
        assertThat(html).contains("selectorContext.agentId=context-agent");
        assertThat(html).doesNotContain("&amp;agentId=context-agent");
    }

    @Test
    void selectedReRendersNamespacedSelectorContext() {
        CapturingLookupService lookup = new CapturingLookupService();
        EntitySelectorController controller = new EntitySelectorController(lookup, new EntitySelectorComponents());

        String html = controller.selected("job", Map.of(
            "name", "jobId",
            "value", "job-1",
            "selectorContext.agentId", "context-agent"
        ));

        assertThat(html).contains("name=\"selectorContext.agentId\" value=\"context-agent\"");
        assertThat(html).contains("selectorContext.agentId=context-agent");
    }

    private static class CapturingLookupService extends EntityLookupService {
        private final AtomicReference<SelectorQuery> lastQuery = new AtomicReference<>();

        CapturingLookupService() {
            super(null, null, null, null, null, null);
        }

        @Override
        public List<EntityOption> search(EntityKind kind, SelectorQuery query) {
            lastQuery.set(query);
            return List.of(new EntityOption(kind.wireName(), "job-1", "Job One", "detail", "READY", true));
        }

        @Override
        public EntityOption currentOption(EntityKind kind, String id) {
            return new EntityOption(kind.wireName(), id, "Job One", "detail", "READY", true);
        }
    }
}
