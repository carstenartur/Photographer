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
     * Executes a capture sequence that <strong>skips the half-press</strong>
     * (AF + metering) phase and goes directly to full-press → release.
     *
     * <p>Use this when the body is in <em>manual focus</em> and the operator
     * wants to bypass the AF trigger entirely. The motivating cases are:</p>
     * <ul>
     *   <li><b>Dark / lens-cap-on captures</b> — Canon firmware rejects the
     *       half-press with {@code Device_Busy 0x2019} when AF is active and
     *       the lens cannot lock focus, blocking the entire capture chain
     *       even though the camera would otherwise be able to expose the
     *       frame (the shutter does not need focus to actuate).</li>
     *   <li><b>Pre-locked focus-stacking</b> — when the host has already
     *       positioned the lens via {@code OC_EOS_DRIVE_LENS}, an additional
     *       AF pass would defeat the careful per-frame focus offset and
     *       potentially cause focus hunting.</li>
     * </ul>
     *
     * <p>This is a Canon-EOS-specific path; non-Canon PTP cameras use the
     * standard {@code InitiateCapture} operation instead which has no AF
     * coupling.</p>
     *
     * @param transactionIdSupplier supplies the next transaction ID for each step
     * @throws InterruptedException if the calling thread is interrupted during a delay
     */
    public void captureNoAf(TransactionIdSupplier transactionIdSupplier) throws InterruptedException {
        log.info("Starting Canon remote capture sequence (no-AF: half-press skipped)");

        // Skip half-press entirely; the camera will neither focus nor meter
        // before exposure. Use only when the body is set to manual focus.
        pressShutter(CanonConstants.RELEASE_PRESS_FULL, transactionIdSupplier.next());
        if (delayAfterFullPressMs > 0) {
            Thread.sleep(delayAfterFullPressMs);
        }

        releaseShutter(transactionIdSupplier.next());
        if (delayAfterReleaseMs > 0) {
            Thread.sleep(delayAfterReleaseMs);
        }

        log.info("Canon remote capture sequence complete (no-AF)");
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