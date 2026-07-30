package io.mindspice.magenta2.ai.chat.project;

/**
 * Thread-local holder for the active project-chat profile so callbacks
 * can revalidate the server-created profile, bound agent, project scope,
 * and workspace at every invocation without caller-supplied authority.
 */
final class ProjectChatProfileHolder {
    private ProjectChatProfileHolder() {}

    private static final ThreadLocal<ProjectChatProfile> CURRENT = new ThreadLocal<>();

    static void set(ProjectChatProfile profile) {
        CURRENT.set(profile);
    }

    static ProjectChatProfile current() {
        return CURRENT.get();
    }

    static void clear() {
        CURRENT.remove();
    }
}
