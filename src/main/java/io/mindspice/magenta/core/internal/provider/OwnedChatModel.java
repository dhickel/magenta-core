package io.mindspice.magenta.core.internal.provider;

import org.springframework.ai.chat.model.ChatModel;

import java.util.concurrent.atomic.AtomicBoolean;

/** Package-private ownership handle for a provider model and its client resources. */
public final class OwnedChatModel {

    private final ChatModel model;
    private final AutoCloseable resource;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public OwnedChatModel(ChatModel model, AutoCloseable resource) {
        this.model = model;
        this.resource = resource;
    }

    public ChatModel model() {
        return model;
    }

    public void close() throws Exception {
        if (closed.compareAndSet(false, true) && resource != null) {
            resource.close();
        }
    }
}
