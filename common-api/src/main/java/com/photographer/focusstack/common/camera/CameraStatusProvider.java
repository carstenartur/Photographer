package com.photographer.focusstack.common.camera;

import com.photographer.focusstack.common.device.BatteryInfo;
import com.photographer.focusstack.common.device.StorageInfo;

/**
 * Optional rich status contract for legacy camera backends.
 *
 * <p>{@link CameraBackend#getState()} remains the compatibility summary used by
 * existing workflows. Backends that can preserve vendor-neutral battery details
 * and multiple storage volumes implement this interface in addition to
 * {@link CameraBackend}. An unknown or empty value returned here is authoritative;
 * callers must not replace it with invented legacy defaults.</p>
 */
public interface CameraStatusProvider {

    BatteryInfo getBatteryInfo();

    StorageInfo getStorageInfo();
}
