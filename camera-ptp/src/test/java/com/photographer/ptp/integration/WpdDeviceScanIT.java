package com.photographer.ptp.integration;

import com.photographer.ptp.core.PtpException;
import com.photographer.ptp.usb.PtpTransport;
import com.photographer.ptp.wpd.WpdDeviceScanner;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Quick WPD device scan — verifies the FFM-based WPD COM calls
 * can enumerate devices and find a Canon camera.
 * <p>
 * Run with:
 * <pre>
 * mvn verify -pl camera-ptp -am -Dptp.test.usb=true -DexcludedGroups= -Djava.awt.headless=true
 * </pre>
 */
@Tag("real-camera")
@EnabledIfSystemProperty(named = "ptp.test.usb", matches = "true")
@EnabledOnOs(OS.WINDOWS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WpdDeviceScanIT {

    private static final Logger log = LoggerFactory.getLogger(WpdDeviceScanIT.class);

    @Test
    @Order(1)
    @DisplayName("WPD is available on this platform")
    void wpdIsAvailable() {
        assertTrue(WpdDeviceScanner.isAvailable(), "WPD should be available on Windows");
        log.info("✓ WPD scanner reports available = true");
    }

    @Test
    @Order(2)
    @DisplayName("WPD finds a Canon camera via FFM COM calls")
    void findCanonViaWpd() {
        PtpTransport transport = null;
        try {
            transport = WpdDeviceScanner.findCanon();
            assertNotNull(transport, "WPD transport");
            log.info("✓ Canon camera found via WPD (FFM): {}", transport);
        } catch (PtpException e) {
            log.error("✗ WPD scan failed: {}", e.getMessage());
            fail("WPD scan did not find a Canon camera: " + e.getMessage());
        } finally {
            if (transport != null) {
                transport.close();
            }
        }
    }
}
