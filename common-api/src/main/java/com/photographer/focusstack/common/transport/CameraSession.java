package com.photographer.focusstack.common.transport;

import com.photographer.focusstack.common.capability.CapabilitySet;
import com.photographer.focusstack.common.device.DeviceInfo;

/**
 * Higher-level abstraction over a {@link CameraConnection}: bundles the
 * stable identity, the resolved capability set and the open transport so
 * workflow code (capture, ingest, AI) can take a single handle and stop
 * worrying about backend specifics.
 *
 * <p>Intentionally minimal — see {@code CameraSessions} (in {@code app}) for
 * the default implementation that wires this together.
 */
public interface CameraSession extends AutoCloseable {

    DeviceInfo deviceInfo();

    CapabilitySet capabilities();

    CameraConnection connection();

    GenericCameraBackend backend();

    @Override
    void close();
}
