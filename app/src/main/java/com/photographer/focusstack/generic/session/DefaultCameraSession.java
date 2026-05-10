package com.photographer.focusstack.generic.session;

import com.photographer.focusstack.common.capability.CapabilitySet;
import com.photographer.focusstack.common.device.DeviceInfo;
import com.photographer.focusstack.common.transport.CameraConnection;
import com.photographer.focusstack.common.transport.CameraSession;
import com.photographer.focusstack.common.transport.GenericCameraBackend;

/**
 * Default {@link CameraSession} implementation: bundles backend + open
 * connection + cached identity/capabilities. {@link #close()} is idempotent.
 */
public final class DefaultCameraSession implements CameraSession {

    private final GenericCameraBackend backend;
    private final CameraConnection connection;
    private final DeviceInfo deviceInfo;
    private final CapabilitySet capabilities;

    public DefaultCameraSession(GenericCameraBackend backend) {
        this.backend = backend;
        this.connection = backend.connect();
        this.deviceInfo = backend.getDeviceInfo(connection);
        this.capabilities = backend.getCapabilities(connection);
    }

    @Override public DeviceInfo deviceInfo()      { return deviceInfo; }
    @Override public CapabilitySet capabilities() { return capabilities; }
    @Override public CameraConnection connection() { return connection; }
    @Override public GenericCameraBackend backend() { return backend; }

    @Override
    public void close() {
        if (connection.isOpen()) {
            backend.disconnect(connection);
        }
    }
}
