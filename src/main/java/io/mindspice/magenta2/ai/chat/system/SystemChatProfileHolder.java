package io.mindspice.magenta2.ai.chat.system;

import io.mindspice.magenta2.ai.chat.service.ChatInvocationProfile;

/**
 * Thread-local holder for the active system-chat profile so callbacks
 * can revalidate the server-created profile, bound agent, and scope
 * at every invocation without caller-supplied authority.
 */
final class SystemChatProfileHolder {
    private SystemChatProfileHolder() {}

    private static final ThreadLocal<ChatInvocationProfile.SystemChatProfile> CURRENT =
            new ThreadLocal<>();

    static void set(ChatInvocationProfile.SystemChatProfile profile) {
        CURRENT.set(profile);
    }

    static ChatInvocationProfile.SystemChatProfile current() {
        return CURRENT.get();
    }

    static void clear() {
        CURRENT.remove();
    }
}
