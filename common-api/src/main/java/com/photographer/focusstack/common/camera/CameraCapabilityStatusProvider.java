package com.photographer.focusstack.common.camera;

import com.photographer.focusstack.common.capability.CapabilityStatusSnapshot;

/** Optional evidence-rich capability contract for camera backends. */
public interface CameraCapabilityStatusProvider {

    /**
     * Returns an immutable, sanitized snapshot based on the backend's authoritative
     * discovery evidence and most recently observed camera state.
     */
    CapabilityStatusSnapshot getCapabilityStatus();
}
