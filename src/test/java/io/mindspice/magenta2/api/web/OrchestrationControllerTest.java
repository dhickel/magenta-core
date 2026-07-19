package io.mindspice.magenta2.api.web;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import static org.assertj.core.api.Assertions.assertThat;

class OrchestrationControllerTest {
    @Test
    void orchestrationControllerHasNoRetiredWorkspaceOrWorkAreaRoutes() {
        var routes = Arrays.stream(OrchestrationController.class.getDeclaredMethods())
            .flatMap(method -> {
                var get = method.getAnnotation(GetMapping.class);
                var post = method.getAnnotation(PostMapping.class);
                if (get != null) return Arrays.stream(get.value());
                if (post != null) return Arrays.stream(post.value());
                return java.util.stream.Stream.<String>empty();
            })
            .toList();

        assertThat(routes).noneMatch(route -> route.contains("work-areas")
            || route.contains("workspaces")
            || route.contains("release-workspace"));
    }
}
