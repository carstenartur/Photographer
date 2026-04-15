package com.photographer.ptp.wpd;

import com.photographer.ptp.core.PtpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scans for Canon PTP cameras using the Windows Portable Devices (WPD) API.
 * <p>
 * Unlike {@link com.photographer.ptp.usb.UsbDeviceScanner} (which requires the
 * WinUSB driver), this scanner works with the <b>standard Windows MTP driver</b>
 * ({@code wpdmtp.inf}) that Windows loads automatically for cameras.
 */
public class WpdDeviceScanner {

    private static final Logger log = LoggerFactory.getLogger(WpdDeviceScanner.class);

    private WpdDeviceScanner() {}

    /**
     * Finds the first Canon camera via WPD and returns a transport for it.
     *
     * @return WpdPtpTransport ready to be opened
     * @throws PtpException if no Canon camera is found
     */
    public static WpdPtpTransport findCanon() throws PtpException {
        String[] deviceIds = WpdNative.findCanonDeviceIds();

        for (String deviceId : deviceIds) {
            String name = WpdNative.getDeviceFriendlyName(deviceId);
            String manufacturer = WpdNative.getDeviceManufacturer(deviceId);

            log.info("WPD device found: '{}' by '{}' (id={})", name, manufacturer, deviceId);

            if (manufacturer != null && manufacturer.toLowerCase().contains("canon")) {
                log.info("Using Canon WPD device: {} ({})", name, deviceId);
                return new WpdPtpTransport(deviceId);
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
