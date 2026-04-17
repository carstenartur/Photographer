package com.photographer.focusstack.camera.integration.realcamera;

import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.ptp.backend.PtpCameraBackend;
import com.photographer.ptp.backend.PtpCameraBackendFactory;
import com.photographer.ptp.core.PtpException;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * USB/PTP end-to-end focus stacking integration test.
 *
 * <p>Inherits the full focus stacking workflow from {@link AbstractFocusStackEndToEndIT}
 * and connects to the camera via USB (WPD on Windows, usb4java elsewhere).
 *
 * <p>This test performs:
 * <ol>
 *   <li>Capture multiple CR3 RAW images at different focus positions via USB/PTP</li>
 *   <li>Transfer full CR3 data inline (USB/PTP returns image bytes directly)</li>
 *   <li>Decode CR3 to full-resolution BufferedImages</li>
 *   <li>Stack using Laplacian-based focus merge</li>
 *   <li>Save result to {@code target/focus-stack-results/}</li>
 * </ol>
 *
 * <p>Run with:
 * <pre>
 *   mvn verify -pl app -am -Djava.awt.headless=true \
 *       -DexcludedGroups= -Dgroups=real-camera
 * </pre>
 *
 * @see AbstractFocusStackEndToEndIT
 */
@RealCamera
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UsbFocusStackEndToEndIT extends AbstractFocusStackEndToEndIT {

    private static final Logger log = LoggerFactory.getLogger(UsbFocusStackEndToEndIT.class);

    private PtpCameraBackend usbBackend;

    @BeforeAll
    void openCamera() {
        try {
            usbBackend = PtpCameraBackendFactory.createUsbBackend();
            log.info("USB camera backend created: {}", usbBackend.getInfo().model());
        } catch (PtpException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            Throwable cause = e.getCause();
            String causeMsg = cause != null && cause.getMessage() != null ? cause.getMessage() : "";

            if (causeMsg.contains("Access denied") || causeMsg.contains("insufficient permissions")) {
                log.error("╔══════════════════════════════════════════════════════════════╗");
                log.error("║  USB CLAIM FAILED — wrong driver on Windows                 ║");
                log.error("║  Run Zadig (https://zadig.akeo.ie/) to install WinUSB,      ║");
                log.error("║  or use WPD transport (requires Java 22+ FFM).              ║");
                log.error("╚══════════════════════════════════════════════════════════════╝");
                Assumptions.abort("USB claim denied — see log for driver instructions.");
            } else if (msg.contains("No Canon")) {
                Assumptions.abort("No Canon camera connected via USB — skipping.");
            }
            throw e;
        }
    }

    @AfterAll
    void closeCamera() {
        if (usbBackend != null) {
            log.info("Closing USB camera backend");
        }
    }

    @Override
    protected CameraBackend backend() {
        return usbBackend;
    }

    @Override
    protected String transportName() {
        return "usb";
    }
}
