package com.photographer.ptp.session;

import com.photographer.ptp.core.PtpContainer;

/**
 * Listener for PTP events from the camera.
 */
@FunctionalInterface
public interface PtpEventListener {
    void onEvent(PtpContainer event);
}
