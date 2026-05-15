package com.photographer.focusstack.common.transport;

/**
 * Open, transport-specific connection to one camera.
 *
 * <p>Carries no business operations itself — those live on
 * {@link GenericCameraBackend}. Backends use a {@code CameraConnection} to
 * stash transport-level handles (HTTP client + base URL for CCAPI; PTP
 * session handle for USB) and to perform graceful shutdown.
 *
 * <p>Implementations should be safely closeable from any thread.
 */
public interface CameraConnection extends AutoCloseable {

    /** Stable id of the camera reachable through this connection. */
    String cameraId();

    /** Vendor-neutral transport label (e.g. {@code "canon-ccapi"}, {@code "usb-ptp"}). */
    String transport();

    /** {@code true} while the underlying transport is healthy. */
    boolean isOpen();

    @Override
    void close();
}
