package com.photographer.focusstack.common.camera;

import com.photographer.focusstack.common.device.LensInfo;

/** Optional rich lens-status contract for legacy camera backends. */
public interface CameraLensStatusProvider {

    LensInfo getLensInfo();
}
