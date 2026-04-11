package com.photographer.ptp.session;

import com.photographer.ptp.canon.CanonEventParser;
import com.photographer.ptp.core.PtpContainer;

/**
 * Listener for PTP events from the camera.
 */
public interface PtpEventListener {
    /** Called when a standard PTP event is received via the interrupt endpoint. */
    void onEvent(PtpContainer event);

    /**
     * Called when a Canon-specific event is received via GetEvent (0x9116).
     * The default implementation does nothing; override to handle Canon events.
     */
    default void onCanonEvent(CanonEventParser.CanonEvent event) {}
}
