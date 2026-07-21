package com.photographer.focusstack.session;

import com.photographer.focusstack.common.camera.CameraBackend;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A camera backend together with the logical ownership that makes its use safe.
 *
 * <p>The handle is intentionally independent of Spring and of any concrete lease
 * implementation. Closing is idempotent so controller rejection, cancellation and
 * the worker's outer {@code try}-with-resources boundary may converge safely.</p>
 */
public interface CameraBackendSession extends AutoCloseable {

    CameraBackend backend();

    @Override
    void close();

    static CameraBackendSession unmanaged(CameraBackend backend) {
        return managed(backend, () -> { });
    }

    static CameraBackendSession managed(CameraBackend backend, Runnable closeAction) {
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(closeAction, "closeAction");
        AtomicBoolean closed = new AtomicBoolean();
        return new CameraBackendSession() {
            @Override
            public CameraBackend backend() {
                return backend;
            }

            @Override
            public void close() {
                if (closed.compareAndSet(false, true)) {
                    closeAction.run();
                }
            }
        };
    }
}
