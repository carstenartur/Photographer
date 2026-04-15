package com.photographer.ptp.integration;

import com.photographer.ptp.core.PtpDeviceInfo;
import com.photographer.ptp.core.PtpException;
import com.photographer.ptp.session.CameraCapabilities;
import com.photographer.ptp.session.ImageDownload;
import com.photographer.ptp.session.PtpCamera;
import com.photographer.ptp.session.StorageEntry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive integration test for USB Canon camera remote control.
 * <p>
 * Requires a real Canon camera connected via USB and set to "PC connection" mode.
 * Only runs when system property "ptp.test.usb" is set to "true".
 * Also excluded by the 'real-camera' JUnit tag in CI.
 * <p>
 * Tests are ordered so the non-destructive queries run first and the actual
 * shutter release (capture) runs last, allowing quick feedback on connection
 * issues before triggering a photo.
 *
 * <p><b>Windows driver note:</b> usb4java (libusb) requires the <b>WinUSB</b>
 * driver on the camera device.  If Windows has loaded the default MTP driver
 * ({@code wpdmtp.inf}), the USB claim will fail with "Access denied".  Use
 * <a href="https://zadig.akeo.ie/">Zadig</a> to replace the driver with WinUSB.
 *
 * <p>Run with:
 * <pre>
 * mvn verify -pl camera-ptp -am -Dptp.test.usb=true -DexcludedGroups= -Djava.awt.headless=true
 * </pre>
 */
@Tag("real-camera")
@EnabledIfSystemProperty(named = "ptp.test.usb", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UsbCanonCameraIT {

    private static final Logger log = LoggerFactory.getLogger(UsbCanonCameraIT.class);

    private PtpCamera camera;

    @BeforeAll
    void openCamera() {
        try {
            camera = PtpCamera.openUsb();
            log.info("Camera opened for integration test");
        } catch (PtpException e) {
            String msg = e.getMessage();
            Throwable cause = e.getCause();
            String causeMsg = cause != null ? cause.getMessage() : "";

            if (causeMsg.contains("Access denied") || causeMsg.contains("insufficient permissions")) {
                log.error("╔══════════════════════════════════════════════════════════════╗");
                log.error("║  USB CLAIM FAILED — wrong driver on Windows                 ║");
                log.error("║                                                              ║");
                log.error("║  The camera is visible on the USB bus but the Windows MTP    ║");
                log.error("║  driver (wpdmtp.inf) has exclusive access.                   ║");
                log.error("║  libusb/usb4java needs the WinUSB driver instead.            ║");
                log.error("║                                                              ║");
                log.error("║  Fix: run Zadig (https://zadig.akeo.ie/) as Admin:           ║");
                log.error("║    1. Options → List All Devices                             ║");
                log.error("║    2. Select 'Canon PowerShot G5 X Mark II'                  ║");
                log.error("║    3. Set target driver to WinUSB                            ║");
                log.error("║    4. Click 'Replace Driver'                                 ║");
                log.error("║                                                              ║");
                log.error("║  To restore MTP (Explorer access): Device Manager →          ║");
                log.error("║    right-click camera → Update driver → automatic            ║");
                log.error("╚══════════════════════════════════════════════════════════════╝");
                Assumptions.abort("USB claim denied — WinUSB driver not installed. " +
                        "Run Zadig to replace the MTP driver. See log above for instructions.");
            } else if (msg.contains("No Canon") || msg.contains("No Canon camera found")) {
                Assumptions.abort("No Canon camera connected via USB — skipping hardware tests.");
            }
            throw e; // unexpected error — let it fail normally
        }
    }

    @AfterAll
    void closeCamera() {
        if (camera != null) {
            camera.close();
            log.info("Camera closed after integration test");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  1. Connection & Device Info
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @Order(1)
    @DisplayName("USB connection established and DeviceInfo retrieved")
    void connectAndGetDeviceInfo() {
        PtpDeviceInfo info = camera.getDeviceInfo();

        assertNotNull(info, "DeviceInfo");
        assertNotNull(info.manufacturer(), "manufacturer");
        assertFalse(info.manufacturer().isBlank(), "manufacturer should not be blank");
        assertNotNull(info.model(), "model");
        assertFalse(info.model().isBlank(), "model should not be blank");
        assertNotNull(info.serialNumber(), "serial number");
        assertFalse(info.serialNumber().isBlank(), "serial number should not be blank");
        assertNotNull(info.deviceVersion(), "firmware version");
        assertFalse(info.deviceVersion().isBlank(), "firmware version should not be blank");

        assertTrue(info.manufacturer().toLowerCase().contains("canon"),
                "vendor should be Canon, was: " + info.manufacturer());
        assertTrue(info.operationsSupported().length > 0,
                "should have supported operations");

        log.info("═══ Connected Camera ═══");
        log.info("  Manufacturer : {}", info.manufacturer());
        log.info("  Model        : {}", info.model());
        log.info("  Firmware     : {}", info.deviceVersion());
        log.info("  Serial       : {}", info.serialNumber());
        log.info("  PTP version  : {}", info.standardVersion());
        log.info("  Operations   : {} supported", info.operationsSupported().length);
        log.info("  Events       : {} supported", info.eventsSupported().length);
        log.info("  Properties   : {} supported", info.devicePropertiesSupported().length);
        log.info("  Capture fmts : {} supported", info.captureFormats().length);
        log.info("  Image fmts   : {} supported", info.imageFormats().length);
    }

    @Test
    @Order(2)
    @DisplayName("Camera is recognized as Canon EOS")
    void cameraIsCanon() {
        assertTrue(camera.isCanon(), "camera should be detected as Canon");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  2. Capabilities
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @Order(10)
    @DisplayName("Camera capabilities are detected")
    void capabilities() {
        CameraCapabilities caps = camera.getCapabilities();

        assertNotNull(caps, "capabilities");
        assertTrue(caps.capture(), "capture should be supported");
        assertTrue(caps.propertiesRead(), "property read should be supported");

        log.info("═══ Capabilities ═══");
        log.info("  LiveView       : {}", caps.liveView());
        log.info("  Capture        : {}", caps.capture());
        log.info("  Focus drive    : {}", caps.focusDrive());
        log.info("  Zoom           : {}", caps.zoom());
        log.info("  Properties R   : {}", caps.propertiesRead());
        log.info("  Properties W   : {}", caps.propertiesWrite());
        log.info("  RAW download   : {}", caps.rawDownload());
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  3. Camera Properties (exposure settings)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @Order(20)
    @DisplayName("Read aperture, shutter speed, ISO, and battery level")
    void readExposureProperties() {
        String aperture = camera.getAperture();
        String shutterSpeed = camera.getShutterSpeed();
        String iso = camera.getIso();
        int battery = camera.getBatteryLevel();

        assertNotNull(aperture, "aperture");
        assertFalse(aperture.isBlank(), "aperture should not be blank");
        assertNotNull(shutterSpeed, "shutter speed");
        assertFalse(shutterSpeed.isBlank(), "shutter speed should not be blank");
        assertNotNull(iso, "ISO");
        assertFalse(iso.isBlank(), "ISO should not be blank");
        assertTrue(battery >= 0, "battery level should be >= 0, was: " + battery);

        log.info("═══ Exposure Settings ═══");
        log.info("  Aperture     : {}", aperture);
        log.info("  Shutter      : {}", shutterSpeed);
        log.info("  ISO          : {}", iso);
        log.info("  Battery      : {}%", battery);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  4. Storage listing
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @Order(30)
    @DisplayName("List images on camera storage")
    void listStorageImages() {
        List<StorageEntry> images = camera.listImages();

        // We don't require images to be present, but the call must not fail
        assertNotNull(images, "image list");

        log.info("═══ Storage ═══");
        log.info("  Images found : {}", images.size());
        images.stream().limit(5).forEach(img ->
                log.info("    {} — {}x{}, {} bytes, {}",
                        img.filename(), img.imageWidth(), img.imageHeight(),
                        img.compressedSize(), img.captureDate()));
        if (images.size() > 5) {
            log.info("    ... and {} more", images.size() - 5);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  5. LiveView (quick start/stop cycle)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @Order(40)
    @DisplayName("Start LiveView, grab one frame, stop LiveView")
    void liveViewCycle() {
        CameraCapabilities caps = camera.getCapabilities();
        Assumptions.assumeTrue(caps.liveView(), "Camera does not support LiveView");

        camera.startLiveView();
        try {
            // Give the camera a moment to start the EVF stream
            Thread.sleep(1000);
            byte[] frame = camera.getLiveViewFrame();

            assertNotNull(frame, "LiveView frame");
            assertTrue(frame.length > 1000,
                    "LiveView frame should be > 1000 bytes, was: " + frame.length);

            log.info("═══ LiveView ═══");
            log.info("  Frame size : {} bytes", frame.length);
            // Verify JPEG header (SOI marker 0xFFD8)
            assertEquals(0xFF, frame[0] & 0xFF, "JPEG SOI byte 0");
            assertEquals(0xD8, frame[1] & 0xFF, "JPEG SOI byte 1");
            log.info("  Valid JPEG : yes");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("LiveView test interrupted");
        } finally {
            camera.stopLiveView();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  6. Focus Drive (small near/far nudge)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @Order(50)
    @DisplayName("Drive focus near and far (smallest step)")
    void focusDriveNearAndFar() {
        CameraCapabilities caps = camera.getCapabilities();
        Assumptions.assumeTrue(caps.focusDrive(), "Camera does not support focus drive");

        // Drive focus a tiny step near, then back far — should not throw
        camera.driveFocusNear(1);
        log.info("  Focus near(1) : OK");

        // Small delay between focus commands
        try { Thread.sleep(300); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        camera.driveFocusFar(1);
        log.info("  Focus far(1)  : OK");

        log.info("═══ Focus Drive ═══  passed (near+far, magnitude 1)");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  7. Remote Capture (actually takes a photo!)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @Order(100)
    @DisplayName("Remote shutter release and image download")
    void remoteCapture() throws InterruptedException {
        CameraCapabilities caps = camera.getCapabilities();
        Assumptions.assumeTrue(caps.capture(), "Camera does not support remote capture");

        // Allow camera to settle after previous tests (LiveView, focus drive)
        Thread.sleep(2000);

        ImageDownload result = camera.capture();

        assertNotNull(result, "capture result");
        assertNotNull(result.fileName(), "filename");
        assertFalse(result.fileName().isBlank(), "filename should not be blank");
        assertNotNull(result.data(), "image data");
        assertTrue(result.size() > 0,
                "image size should be > 0, was: " + result.size());

        log.info("═══ Remote Capture ═══");
        log.info("  Filename   : {}", result.fileName());
        log.info("  Size       : {} bytes ({} KB)",
                result.size(), result.size() / 1024);
    }
}