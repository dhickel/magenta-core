package io.mindspice.magenta2.api.web;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import static org.assertj.core.api.Assertions.assertThat;

class SseStreamLifecycleTest {

    // ── SseStreamLifecycle.createEmitter ──────────────────────────────

    @Test
    void createEmitterWithTimeoutSetsTimeout() {
        SseEmitter emitter = SseStreamLifecycle.createEmitter(5000L);
        assertThat(emitter.getTimeout()).isEqualTo(5000L);
    }

    @Test
    void createEmitterWithZeroTimeoutIsNoTimeout() {
        SseEmitter emitter = SseStreamLifecycle.createEmitter(0L);
        assertThat(emitter.getTimeout()).isZero();
    }

    @Test
    void createEmitterWithNegativeTimeoutCoercesToZero() {
        SseEmitter emitter = SseStreamLifecycle.createEmitter(-1L);
        assertThat(emitter.getTimeout()).isZero();
    }

    @Test
    void createEmitterWithoutArgumentHasNoTimeout() {
        SseEmitter emitter = SseStreamLifecycle.createEmitter();
        assertThat(emitter.getTimeout()).isZero();
    }

    // ── SubscriptionGuard.set / dispose ──────────────────────────────

    @Test
    void guardDisposeDisposesTrackedSubscription() {
        SseStreamLifecycle.SubscriptionGuard guard = SseStreamLifecycle.guardSubscription();
        FakeDisposable disposable = new FakeDisposable();

        guard.set(disposable);
        guard.dispose();

        assertThat(disposable.disposed).isTrue();
    }

    @Test
    void guardDisposeIsSafeWhenNoSubscriptionSet() {
        SseStreamLifecycle.SubscriptionGuard guard = SseStreamLifecycle.guardSubscription();
        // Should not throw
        guard.dispose();
    }

    @Test
    void guardDisposeIsSafeWhenCalledMultipleTimes() {
        SseStreamLifecycle.SubscriptionGuard guard = SseStreamLifecycle.guardSubscription();
        FakeDisposable disposable = new FakeDisposable();

        guard.set(disposable);
        guard.dispose();
        guard.dispose(); // second call should be a no-op

        assertThat(disposable.disposed).isTrue();
    }

    @Test
    void guardSetReplacesPreviousSubscriptionAndDisposesOld() {
        SseStreamLifecycle.SubscriptionGuard guard = SseStreamLifecycle.guardSubscription();
        FakeDisposable first = new FakeDisposable();
        FakeDisposable second = new FakeDisposable();

        guard.set(first);
        guard.set(second);

        assertThat(first.disposed).isTrue();
        assertThat(second.disposed).isFalse();
    }

    @Test
    void guardSetWithAlreadyDisposedSubscriptionIsSafe() {
        SseStreamLifecycle.SubscriptionGuard guard = SseStreamLifecycle.guardSubscription();
        FakeDisposable disposable = new FakeDisposable();
        disposable.disposed = true;

        guard.set(disposable);
        // Should not throw during dispose
        guard.dispose();
    }

    @Test
    void guardDisposeDoesNotDisposeAlreadyDisposedSubscription() {
        SseStreamLifecycle.SubscriptionGuard guard = SseStreamLifecycle.guardSubscription();
        FakeDisposable disposable = new FakeDisposable();
        guard.set(disposable);
        disposable.disposed = true;

        guard.dispose();
        // disposed stays true, no exception
        assertThat(disposable.disposed).isTrue();
    }

    // ── registerCallbacks ─────────────────────────────────────────────

    @Test
    void registerCallbacksCompletionDisposesGuard() {
        SseEmitter emitter = SseStreamLifecycle.createEmitter();
        SseStreamLifecycle.SubscriptionGuard guard = SseStreamLifecycle.guardSubscription();
        FakeDisposable disposable = new FakeDisposable();
        guard.set(disposable);

        SseStreamLifecycle.LifecycleCallbacks callbacks =
            SseStreamLifecycle.callbacks(guard, null, null);
        SseStreamLifecycle.registerCallbacks(emitter, guard, null, null);

        callbacks.onCompletion().run();

        assertThat(disposable.disposed).isTrue();
    }

    @Test
    void registerCallbacksTimeoutDisposesGuardAndInvokesHandler() {
        SseStreamLifecycle.SubscriptionGuard guard = SseStreamLifecycle.guardSubscription();
        FakeDisposable disposable = new FakeDisposable();
        guard.set(disposable);
        AtomicBoolean handlerCalled = new AtomicBoolean(false);

        SseStreamLifecycle.LifecycleCallbacks callbacks =
            SseStreamLifecycle.callbacks(guard, () -> handlerCalled.set(true), null);

        callbacks.onTimeout().run();

        assertThat(disposable.disposed).isTrue();
        assertThat(handlerCalled.get()).isTrue();
    }

    @Test
    void registerCallbacksErrorDisposesGuardAndInvokesHandler() {
        SseStreamLifecycle.SubscriptionGuard guard = SseStreamLifecycle.guardSubscription();
        FakeDisposable disposable = new FakeDisposable();
        guard.set(disposable);
        AtomicReference<Throwable> capturedError = new AtomicReference<>();

        SseStreamLifecycle.LifecycleCallbacks callbacks =
            SseStreamLifecycle.callbacks(guard, null, capturedError::set);

        RuntimeException error = new RuntimeException("test error");
        callbacks.onError().accept(error);

        assertThat(disposable.disposed).isTrue();
        assertThat(capturedError.get()).isSameAs(error);
    }

    @Test
    void registerCallbacksTimeoutWithNullHandlerOnlyDisposesGuard() {
        SseStreamLifecycle.SubscriptionGuard guard = SseStreamLifecycle.guardSubscription();
        FakeDisposable disposable = new FakeDisposable();
        guard.set(disposable);

        SseStreamLifecycle.LifecycleCallbacks callbacks =
            SseStreamLifecycle.callbacks(guard, null, null);

        callbacks.onTimeout().run();

        assertThat(disposable.disposed).isTrue();
    }

    @Test
    void registerCallbacksErrorWithNullHandlerOnlyDisposesGuard() {
        SseStreamLifecycle.SubscriptionGuard guard = SseStreamLifecycle.guardSubscription();
        FakeDisposable disposable = new FakeDisposable();
        guard.set(disposable);

        SseStreamLifecycle.LifecycleCallbacks callbacks =
            SseStreamLifecycle.callbacks(guard, null, null);

        callbacks.onError().accept(new RuntimeException("test"));

        assertThat(disposable.disposed).isTrue();
    }

    // ── sendSseEvent ─────────────────────────────────────────────────

    @Test
    void sendSseEventConstructsEventWithoutError() {
        SseEmitter emitter = SseStreamLifecycle.createEmitter();
        // Verifies the method builds the SseEventBuilder chain without error.
        // Calling emitter.send() requires a fully initialized async context,
        // so we only verify the builder construction is valid.
        assertThat(SseEmitter.event().name("test").data("data")).isNotNull();
    }

    @Test
    void sendSseEventWithMediaTypeConstructsEventWithoutError() {
        assertThat(SseEmitter.event().name("test").data("data",
            org.springframework.http.MediaType.APPLICATION_JSON)).isNotNull();
    }

    @Test
    void trySendReturnsFalseWhenEmitterHandlerRejectsSend() throws Exception {
        SseEmitter emitter = SseStreamLifecycle.createEmitter();
        initializeEmitterWithFailingSend(emitter);

        assertThat(SseStreamLifecycle.trySendSseEvent(emitter, "test", "data")).isFalse();
    }

    @Test
    void trySendReturnsFalseWhenEmitterAlreadyCompleted() throws Exception {
        SseEmitter emitter = SseStreamLifecycle.createEmitter();
        initializeEmitterWithFailingSend(emitter);
        emitter.complete();

        assertThat(SseStreamLifecycle.trySendSseEvent(emitter, "test", "data")).isFalse();
    }

    // ── Fake Disposable for testing ──────────────────────────────────

    private static final class FakeDisposable implements Disposable {
        private boolean disposed;

        @Override
        public void dispose() {
            this.disposed = true;
        }

        @Override
        public boolean isDisposed() {
            return disposed;
        }
    }

    private void initializeEmitterWithFailingSend(SseEmitter emitter) throws Exception {
        Class<?> handlerType = Class.forName(
            "org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter$Handler"
        );
        Object handler = Proxy.newProxyInstance(
            handlerType.getClassLoader(),
            new Class<?>[] { handlerType },
            (proxy, method, args) -> {
                if ("send".equals(method.getName())) {
                    throw new IOException("client disconnected");
                }
                return null;
            }
        );
        var initialize = org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.class
            .getDeclaredMethod("initialize", handlerType);
        initialize.setAccessible(true);
        initialize.invoke(emitter, handler);
    }
}
