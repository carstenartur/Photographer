package com.photographer.focusstack.common.transport;

import com.photographer.focusstack.common.media.MediaItem;

/**
 * Issues capture commands against an open {@link CameraSession}.
 *
 * <p>Workflow code (HDR, focus stack, timelapse) targets this small surface
 * instead of poking at backends directly. Implementations enforce the
 * required {@link com.photographer.focusstack.common.capability.Capability}
 * checks (e.g. live-view stopped before capture).
 */
public interface CaptureService {

    /** Releases the shutter once and returns the resulting media descriptor. */
    MediaItem captureStill(CameraSession session);
}
