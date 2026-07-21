package com.photographer.focusstack.camera.session;

import com.photographer.focusstack.common.model.CameraProfile;

import java.util.Optional;

/** Resolves a stable private transport identity when one is available. */
@FunctionalInterface
public interface CameraLeaseIdentityProvider {
    Optional<CameraLeaseKey> resolve(CameraProfile profile);
}
