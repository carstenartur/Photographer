package com.photographer.ptp.wpd;

import com.photographer.ptp.core.PtpException;
import com.photographer.ptp.usb.PtpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scans for Canon PTP cameras using the Windows Portable Devices (WPD) API.
 * <p>
 * Unlike {@link com.photographer.ptp.usb.UsbDeviceScanner} (which requires the
 * WinUSB driver), this scanner works with the <b>standard Windows MTP driver</b>
 * ({@code wpdmtp.inf}) that Windows loads automatically for cameras.
 * <p>
 * Since Java 22 this scanner uses the <b>Foreign Function &amp; Memory API</b>
 * (JEP 454) to call WPD COM interfaces directly, eliminating the JNA dependency.
 */
public class WpdDeviceScanner {

    private static final Logger log = LoggerFactory.getLogger(WpdDeviceScanner.class);

    /** Maximum number of USB bus refresh attempts to wake a sleeping camera. */
    private static final int WAKE_RETRY_COUNT = 5;
    /** Delay between wake attempts (ms). */
    private static final long WAKE_RETRY_DELAY_MS = 2000;

    private WpdDeviceScanner() {}

    /**
     * Attempts to wake a sleeping USB camera by repeatedly refreshing the
     * Windows Portable Devices bus, then returns the first Canon camera found.
     * <p>
     * USB PTP cameras may enter standby after a period of inactivity. The WPD
     * {@code RefreshDeviceList} call triggers USB bus enumeration signals that
     * can wake the device. Multiple attempts with delays are needed because the
     * camera may take several seconds to respond after being woken.
     *
     * @return {@link FfmWpdPtpTransport} ready to be opened
     * @throws PtpException if no Canon camera is found after all retries
     */
    public static PtpTransport wakeAndFindCanon() throws PtpException {
        for (int attempt = 1; attempt <= WAKE_RETRY_COUNT; attempt++) {
            log.info("USB wake attempt {}/{}: refreshing WPD device list...",
                    attempt, WAKE_RETRY_COUNT);
            try {
                FfmWpdNative.refreshDeviceList();
            } catch (Exception e) {
                log.debug("Device list refresh failed on attempt {}: {}", attempt, e.getMessage());
            }

            try {
                return findCanon();
            } catch (PtpException e) {
                if (attempt < WAKE_RETRY_COUNT) {
                    log.info("Camera not found yet, waiting {}ms before retry...",
                            WAKE_RETRY_DELAY_MS);
                    try {
                        Thread.sleep(WAKE_RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new PtpException("USB wake interrupted", ie);
                    }
                }
            }
        }
        throw new PtpException("No Canon camera found via WPD after "
                + WAKE_RETRY_COUNT + " wake attempts");
    }

    /**
     * Finds the first Canon camera via WPD and returns an FFM-based transport.
     *
     * @return {@link FfmWpdPtpTransport} ready to be opened
     * @throws PtpException if no Canon camera is found
     */
    public static PtpTransport findCanon() throws PtpException {
        String[] deviceIds = FfmWpdNative.findCanonDeviceIds();

        for (String deviceId : deviceIds) {
            String name = FfmWpdNative.getDeviceFriendlyName(deviceId);
            String manufacturer = FfmWpdNative.getDeviceManufacturer(deviceId);

            log.info("WPD device found: '{}' by '{}' (id={})", name, manufacturer, deviceId);

            if (manufacturer != null && manufacturer.toLowerCase().contains("canon")) {
                log.info("Using Canon WPD device (FFM): {} ({})", name, deviceId);
                return new FfmWpdPtpTransport(deviceId);
            }
        }

        throw new PtpException("No Canon camera found via WPD. "
                + "Devices scanned: " + deviceIds.length);
    }

    /**
     * Returns true if this platform supports WPD (i.e. is Windows).
     */
    public static boolean isAvailable() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
