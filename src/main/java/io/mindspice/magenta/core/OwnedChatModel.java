package io.mindspice.magenta.core;

import org.springframework.ai.chat.model.ChatModel;

import java.util.concurrent.atomic.AtomicBoolean;

/** Package-private ownership handle for a provider model and its client resources. */
final class OwnedChatModel {

    private final ChatModel model;
    private final AutoCloseable resource;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    OwnedChatModel(ChatModel model, AutoCloseable resource) {
        this.model = model;
        this.resource = resource;
    }

    ChatModel model() {
        return model;
    }

    void close() throws Exception {
        if (closed.compareAndSet(false, true) && resource != null) {
            resource.close();
        }
    }
}
