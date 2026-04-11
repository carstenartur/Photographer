package com.photographer.ptp.canon;

import com.photographer.ptp.core.PtpConstants;
import com.photographer.ptp.core.PtpContainer;
import com.photographer.ptp.core.PtpException;
import com.photographer.ptp.usb.PtpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles Canon EOS remote capture sequence via PTP.
 * Canon cameras use a 3-phase shutter sequence:
 * 1. Half-press (AF + metering)
 * 2. Full-press (actual capture)
 * 3. Release
 */
public class CanonRemoteCapture {
    private static final Logger log = LoggerFactory.getLogger(CanonRemoteCapture.class);

    private final PtpTransport transport;

    public CanonRemoteCapture(PtpTransport transport) {
        this.transport = transport;
    }

    /**
     * Executes the full capture sequence: half-press, full-press, release.
     * @param transactionIdSupplier supplies the next transaction ID for each step
     */
    public void capture(TransactionIdSupplier transactionIdSupplier) {
        log.info("Starting Canon remote capture sequence");

        // Phase 1: Half-press (auto-focus + metering)
        pressShutter(CanonConstants.RELEASE_PRESS_HALF, transactionIdSupplier.next());

        // Phase 2: Full-press (take the photo)
        pressShutter(CanonConstants.RELEASE_PRESS_FULL, transactionIdSupplier.next());

        // Phase 3: Release
        releaseShutter(transactionIdSupplier.next());

        log.info("Canon remote capture sequence complete");
    }

    /**
     * Sends RemoteReleaseOn with the specified press mode.
     */
    public void pressShutter(int pressMode, int transactionId) {
        log.debug("Remote release ON: mode=0x{}", String.format("%02X", pressMode));
        PtpContainer request = PtpContainer.createCommand(
                CanonConstants.OC_EOS_REMOTE_RELEASE_ON, transactionId, pressMode, 0);
        PtpContainer response = transport.execute(request);
        if (response.code() != PtpConstants.RC_OK) {
            throw new PtpException(response.code(),
                    "Remote release ON failed: 0x%04X".formatted(response.code()));
        }
    }

    /**
     * Sends RemoteReleaseOff.
     */
    public void releaseShutter(int transactionId) {
        log.debug("Remote release OFF");
        PtpContainer request = PtpContainer.createCommand(
                CanonConstants.OC_EOS_REMOTE_RELEASE_OFF, transactionId, CanonConstants.RELEASE_RELEASE);
        PtpContainer response = transport.execute(request);
        if (response.code() != PtpConstants.RC_OK) {
            throw new PtpException(response.code(),
                    "Remote release OFF failed: 0x%04X".formatted(response.code()));
        }
    }

    @FunctionalInterface
    public interface TransactionIdSupplier {
        int next();
    }
}
