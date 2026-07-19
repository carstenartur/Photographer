package com.photographer.focusstack.common.camera;

/**
 * Optional vendor-neutral camera capability and diagnostic contract.
 *
 * <p>The snapshot must not expose transport addresses, camera serial numbers,
 * credentials or vendor authentication material. Implementations should derive
 * support from the camera-advertised contract rather than from model-name guesses.</p>
 */
public interface CameraCapabilityProvider {

    CameraCapabilitySnapshot getCapabilitySnapshot();
}
