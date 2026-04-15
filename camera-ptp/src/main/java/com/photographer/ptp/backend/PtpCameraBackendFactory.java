package com.photographer.ptp.backend;

import com.photographer.ptp.core.PtpException;
import com.photographer.ptp.session.PtpCamera;
import com.photographer.ptp.usb.PtpTransport;
import com.photographer.ptp.usb.UsbDeviceScanner;
import com.photographer.ptp.wpd.WpdDeviceScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating {@link PtpCameraBackend} instances.
 * <p>
 * On <b>Windows</b> the factory first tries the <b>WPD (Windows Portable
 * Devices)</b> transport which works with the standard MTP driver and uses the
 * Foreign Function &amp; Memory API (Java 22+). If WPD is unavailable or fails
 * it falls back to the <b>usb4java</b> transport (requires WinUSB/Zadig).
 * <p>
 * On other platforms only the USB transport is used.
 */
public class PtpCameraBackendFactory {
    private static final Logger log = LoggerFactory.getLogger(PtpCameraBackendFactory.class);

    private PtpCameraBackendFactory() {}

    /**
     * Creates a PtpCameraBackend by scanning for a Canon camera.
     * <p>
     * Discovery order:
     * <ol>
     *   <li><b>WPD</b> (Windows only, FFM-based) — no driver changes needed</li>
     *   <li><b>USB</b> (usb4java) — requires WinUSB on Windows, works natively on Linux/macOS</li>
     * </ol>
     *
     * @return connected PtpCameraBackend
     * @throws PtpException if no camera is found or connection fails
     */
    public static PtpCameraBackend createUsbBackend() {
        // Try WPD first on Windows — uses the standard MTP driver (no Zadig needed)
        if (WpdDeviceScanner.isAvailable()) {
            try {
                log.info("Scanning WPD (Windows Portable Devices) for Canon camera (FFM)...");
                PtpTransport transport = WpdDeviceScanner.findCanon();
                PtpCamera camera = PtpCamera.open(transport);
                log.info("PTP camera backend created via WPD (FFM): {}",
                        camera.getDeviceInfo().model());
                return new PtpCameraBackend(camera);
            } catch (PtpException e) {
                log.info("WPD scan did not find a Canon camera, falling back to USB: {}",
                        e.getMessage());
            }
        }

        // Fall back to USB (usb4java)
        log.info("Scanning USB bus for Canon PTP camera...");
        PtpTransport transport = UsbDeviceScanner.findCanon();
        PtpCamera camera = PtpCamera.open(transport);
        log.info("PTP camera backend created via USB: {}", camera.getDeviceInfo().model());
        return new PtpCameraBackend(camera);
    }

    /**
     * Creates a PtpCameraBackend with a custom transport (for testing).
     * @param transport the PTP transport to use
     * @return connected PtpCameraBackend
     */
    public static PtpCameraBackend create(PtpTransport transport) {
        PtpCamera camera = PtpCamera.open(transport);
        return new PtpCameraBackend(camera);
    }
}
