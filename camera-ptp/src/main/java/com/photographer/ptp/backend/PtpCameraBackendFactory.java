package com.photographer.ptp.backend;

import com.photographer.ptp.core.PtpException;
import com.photographer.ptp.session.PtpCamera;
import com.photographer.ptp.usb.PtpTransport;
import com.photographer.ptp.usb.UsbDeviceScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating PtpCameraBackend instances.
 * Scans the USB bus for Canon PTP cameras and creates backends.
 */
public class PtpCameraBackendFactory {
    private static final Logger log = LoggerFactory.getLogger(PtpCameraBackendFactory.class);

    private PtpCameraBackendFactory() {}

    /**
     * Creates a PtpCameraBackend by scanning USB for a Canon camera.
     * @return connected PtpCameraBackend
     * @throws PtpException if no camera is found or connection fails
     */
    public static PtpCameraBackend createUsbBackend() {
        log.info("Scanning USB bus for Canon PTP camera...");
        PtpTransport transport = UsbDeviceScanner.findCanon();
        PtpCamera camera = PtpCamera.open(transport);
        log.info("PTP camera backend created: {}", camera.getDeviceInfo().model());
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
