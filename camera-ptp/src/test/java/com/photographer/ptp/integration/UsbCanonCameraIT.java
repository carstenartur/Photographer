package com.photographer.ptp.integration;

import com.photographer.ptp.session.PtpCamera;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that requires a real Canon USB camera.
 * Only runs when system property "ptp.test.usb" is set to "true".
 * Also excluded by the 'real-camera' JUnit tag in CI.
 *
 * Run with: mvn verify -DexcludedGroups= -Dptp.test.usb=true
 */
@Tag("real-camera")
@EnabledIfSystemProperty(named = "ptp.test.usb", matches = "true")
class UsbCanonCameraIT {

    @Test
    void connectAndGetDeviceInfo() {
        try (PtpCamera camera = PtpCamera.openUsb()) {
            assertNotNull(camera.getDeviceInfo());
            assertNotNull(camera.getDeviceInfo().manufacturer());
            assertNotNull(camera.getDeviceInfo().model());
            System.out.println("Connected to: " + camera.getDeviceInfo().model());
            System.out.println("Serial: " + camera.getDeviceInfo().serialNumber());
        }
    }
}
