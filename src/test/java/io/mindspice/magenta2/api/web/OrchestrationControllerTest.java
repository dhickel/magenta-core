package io.mindspice.magenta2.api.web;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import static org.assertj.core.api.Assertions.assertThat;

class OrchestrationControllerTest {

    @Test
    void orchestrationControllerHasNoRetiredWorkspaceOrWorkAreaRoutes() {
        var routes = allRoutes(OrchestrationController.class);
        assertThat(routes).noneMatch(route -> route.contains("work-areas")
            || route.contains("workspaces")
            || route.contains("release-workspace"));
    }

    @Test
    void filesTabRoutesArePresent() {
        var routes = allRoutes(OrchestrationController.class);
        assertThat(routes)
            .anyMatch(r -> r.equals("/agents/_detail/{agentId}/files"))
            .anyMatch(r -> r.equals("/agents/_detail/{agentId}/files/list"))
            .anyMatch(r -> r.equals("/agents/_detail/{agentId}/files/inspect"))
            .anyMatch(r -> r.equals("/agents/_detail/{agentId}/files/viewer"))
            .anyMatch(r -> r.equals("/agents/_detail/{agentId}/files/text"))
            .anyMatch(r -> r.equals("/agents/_detail/{agentId}/files/modal/{action}"))
            .anyMatch(r -> r.equals("/agents/_detail/{agentId}/files/modal/close"))
            .anyMatch(r -> r.equals("/agents/_detail/{agentId}/files/create-folder"))
            .anyMatch(r -> r.equals("/agents/_detail/{agentId}/files/create-text"))
            .anyMatch(r -> r.equals("/agents/_detail/{agentId}/files/create-markdown"))
            .anyMatch(r -> r.equals("/agents/_detail/{agentId}/files/rename"))
            .anyMatch(r -> r.equals("/agents/_detail/{agentId}/files/copy"))
            .anyMatch(r -> r.equals("/agents/_detail/{agentId}/files/move"))
            .anyMatch(r -> r.equals("/agents/_detail/{agentId}/files/delete"))
            .anyMatch(r -> r.equals("/agents/_detail/{agentId}/files/save-text"))
            .anyMatch(r -> r.equals("/agents/_detail/{agentId}/files/labels"));
    }

    @Test
    void workspaceSelectorRoutesArePresent() {
        var routes = allRoutes(OrchestrationController.class);
        assertThat(routes)
            .anyMatch(r -> r.equals("/selectors/workspace/select"))
            .anyMatch(r -> r.equals("/selectors/workspace/create"));
    }

    @Test
    void noAvatarOrWorkAreaReferencesInRoutes() {
        var routes = allRoutes(OrchestrationController.class);
        assertThat(routes).noneMatch(r -> r.contains("avatar") || r.contains("Avatar")
            || r.contains("work-area") || r.contains("WorkArea")
            || r.contains("workspaces/") || r.contains("canonical-identity")
            || r.contains("raw-root"));
    }

    @Test
    void noChatOrUserFilesSelectorInRoutes() {
        var routes = allRoutes(OrchestrationController.class);
        // Files tab is agent-only; no user or chat workspace selection in files routes
        assertThat(routes).noneMatch(r ->
            (r.contains("/files") || r.contains("workspace/select")) && r.contains("{chatId}"));
    }

    @Test
    void workspaceTabReplacedByFilesTab() {
        var routes = allRoutes(OrchestrationController.class);
        // "workspace" tab route no longer exists; "files" does
        assertThat(routes).noneMatch(r -> r.equals("/agents/_detail/{agentId}/workspace"));
        assertThat(routes).anyMatch(r -> r.equals("/agents/_detail/{agentId}/files"));
    }

    @Test
    void stableExplorerIdsPresent() {
        // The WorkspaceExplorerFragments must expose the stable IDs
        assertThat(WorkspaceExplorerFragments.AGENT_SHELL_ID).isEqualTo("agent-files-explorer");
        assertThat(WorkspaceExplorerFragments.AGENT_LIST_ID).isEqualTo("workspace-file-list");
        assertThat(WorkspaceExplorerFragments.AGENT_INSPECTOR_ID).isEqualTo("workspace-file-inspector");
        assertThat(WorkspaceExplorerFragments.AGENT_MODAL_ID).isEqualTo("workspace-file-modal");
    }

    @Test
    void mutationRoutesUseCorrectHttpMethods() {
        var methods = OrchestrationController.class.getDeclaredMethods();
        for (var method : methods) {
            String mapping = routeMapping(method);
            if (mapping == null) continue;
            if (mapping.contains("/files/create-folder")) {
                assertThat(method.isAnnotationPresent(PostMapping.class))
                    .as("create-folder should be POST").isTrue();
            }
            if (mapping.contains("/files/save-text")) {
                assertThat(method.isAnnotationPresent(PutMapping.class))
                    .as("save-text should be PUT").isTrue();
            }
            if (mapping.contains("/files/labels") && !mapping.contains("/files/labels/")) {
                // POST adds, DELETE removes
                assertThat(method.isAnnotationPresent(PostMapping.class)
                    || method.isAnnotationPresent(DeleteMapping.class))
                    .as("labels should be POST or DELETE").isTrue();
            }
        }
    }

    @Test
    void workspaceSelectorOmitsChat() {
        var imports = Arrays.toString(OrchestrationController.class.getDeclaredFields());
        // No chat workspace offered in WorkspaceSelectionComponents
        assertThat(WorkspaceSelectionComponents.SELECTOR_ID).isNotEmpty();
        // Chat workspace kind is never used in agent-files or workspace-selector routes
        var routes = allRoutes(OrchestrationController.class);
        assertThat(routes).noneMatch(r ->
            r.contains("/selectors") && (r.contains("chat") || r.contains("CHAT")));
    }

    // ── helpers ──

    private static java.util.List<String> allRoutes(Class<?> clazz) {
        return Arrays.stream(clazz.getDeclaredMethods())
            .flatMap(method -> {
                var get = method.getAnnotation(GetMapping.class);
                var post = method.getAnnotation(PostMapping.class);
                var put = method.getAnnotation(PutMapping.class);
                var delete = method.getAnnotation(DeleteMapping.class);
                java.util.stream.Stream<String> s = java.util.stream.Stream.empty();
                if (get != null) s = java.util.stream.Stream.concat(s, Arrays.stream(get.value()));
                if (post != null) s = java.util.stream.Stream.concat(s, Arrays.stream(post.value()));
                if (put != null) s = java.util.stream.Stream.concat(s, Arrays.stream(put.value()));
                if (delete != null) s = java.util.stream.Stream.concat(s, Arrays.stream(delete.value()));
                return s;
            })
            .toList();
    }

    private static String routeMapping(java.lang.reflect.Method method) {
        var get = method.getAnnotation(GetMapping.class);
        if (get != null && get.value().length > 0) return get.value()[0];
        var post = method.getAnnotation(PostMapping.class);
        if (post != null && post.value().length > 0) return post.value()[0];
        var put = method.getAnnotation(PutMapping.class);
        if (put != null && put.value().length > 0) return put.value()[0];
        var delete = method.getAnnotation(DeleteMapping.class);
        if (delete != null && delete.value().length > 0) return delete.value()[0];
        return null;
    }
}
