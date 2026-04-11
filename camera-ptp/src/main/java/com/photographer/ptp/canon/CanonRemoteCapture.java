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

    /** Delay after half-press to let AF and metering complete (ms). */
    public static final int DELAY_AFTER_HALF_PRESS = 300;
    /** Delay after full-press to let shutter actuate (ms). */
    public static final int DELAY_AFTER_FULL_PRESS = 250;
    /** Delay after release to let the image be written to the SD card (ms). */
    public static final int DELAY_AFTER_RELEASE = 1200;

    private final PtpTransport transport;
    private final long delayAfterHalfPressMs;
    private final long delayAfterFullPressMs;
    private final long delayAfterReleaseMs;

    public CanonRemoteCapture(PtpTransport transport) {
        this(transport, DELAY_AFTER_HALF_PRESS, DELAY_AFTER_FULL_PRESS, DELAY_AFTER_RELEASE);
    }

    /** Constructor that allows overriding delays for testing or custom timing. */
    public CanonRemoteCapture(PtpTransport transport,
                       long delayAfterHalfPressMs,
                       long delayAfterFullPressMs,
                       long delayAfterReleaseMs) {
        this.transport = transport;
        this.delayAfterHalfPressMs = delayAfterHalfPressMs;
        this.delayAfterFullPressMs = delayAfterFullPressMs;
        this.delayAfterReleaseMs = delayAfterReleaseMs;
    }

    /**
     * Executes the full capture sequence: half-press, full-press, release.
     * Delays between phases allow AF/metering to complete and the image to be
     * written to the SD card, matching the Canon SDK sample timing.
     *
     * @param transactionIdSupplier supplies the next transaction ID for each step
     * @throws InterruptedException if the calling thread is interrupted during a delay
     */
    public void capture(TransactionIdSupplier transactionIdSupplier) throws InterruptedException {
        log.info("Starting Canon remote capture sequence");

        // Phase 1: Half-press (auto-focus + metering)
        pressShutter(CanonConstants.RELEASE_PRESS_HALF, transactionIdSupplier.next());
        if (delayAfterHalfPressMs > 0) {
            Thread.sleep(delayAfterHalfPressMs);
        }

        // Phase 2: Full-press (take the photo)
        pressShutter(CanonConstants.RELEASE_PRESS_FULL, transactionIdSupplier.next());
        if (delayAfterFullPressMs > 0) {
            Thread.sleep(delayAfterFullPressMs);
        }

        // Phase 3: Release
        releaseShutter(transactionIdSupplier.next());
        if (delayAfterReleaseMs > 0) {
            Thread.sleep(delayAfterReleaseMs);
        }

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
