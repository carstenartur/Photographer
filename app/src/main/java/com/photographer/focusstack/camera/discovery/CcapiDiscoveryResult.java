package com.photographer.focusstack.camera.discovery;

import java.util.List;

/** Immutable result of one bounded local-network CCAPI discovery pass. */
public record CcapiDiscoveryResult(
        List<CcapiDiscoveredCamera> cameras,
        List<CcapiDiscoveryWarning> warnings
) {
    public CcapiDiscoveryResult {
        cameras = cameras == null ? List.of() : List.copyOf(cameras);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
