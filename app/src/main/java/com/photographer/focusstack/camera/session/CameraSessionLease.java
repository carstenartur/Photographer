package com.photographer.focusstack.camera.session;

/** Idempotently closeable handle for one logical camera ownership reference. */
public interface CameraSessionLease extends AutoCloseable {
    String ownerCorrelationId();

    @Override
    void close();

    static CameraSessionLease noop() {
        return NoopHolder.INSTANCE;
    }

    final class NoopHolder {
        private static final CameraSessionLease INSTANCE = new CameraSessionLease() {
            @Override
            public String ownerCorrelationId() {
                return "unmanaged";
            }

            @Override
            public void close() {
                // No transport-specific stable identity is available.
            }
        };

        private NoopHolder() {
        }
    }
}
