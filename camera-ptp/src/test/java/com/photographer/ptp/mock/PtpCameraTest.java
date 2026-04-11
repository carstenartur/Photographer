package com.photographer.ptp.mock;

import com.photographer.ptp.session.CameraCapabilities;
import com.photographer.ptp.session.ImageDownload;
import com.photographer.ptp.session.PtpCamera;
import com.photographer.ptp.session.StorageEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PtpCameraTest {
    private MockPtpTransport transport;
    private PtpCamera camera;

    @BeforeEach
    void setUp() {
        transport = new MockPtpTransport();
        camera = PtpCamera.open(transport);
    }

    @AfterEach
    void tearDown() {
        if (camera != null) {
            camera.close();
        }
    }

    @Test
    void openAndGetDeviceInfo() {
        assertNotNull(camera.getDeviceInfo());
        assertEquals("Canon Inc.", camera.getDeviceInfo().manufacturer());
        assertEquals("Canon PowerShot G5 X Mark II", camera.getDeviceInfo().model());
    }

    @Test
    void isCanon() {
        assertTrue(camera.isCanon());
    }

    @Test
    void capabilities() {
        CameraCapabilities caps = camera.getCapabilities();
        assertTrue(caps.capture());
        assertTrue(caps.focusDrive());
        assertTrue(caps.liveView());
        assertTrue(caps.propertiesRead());
        assertTrue(caps.propertiesWrite());
    }

    @Test
    void capture() {
        ImageDownload result = camera.capture();
        assertNotNull(result);
        assertNotNull(result.fileName());
    }

    @Test
    void driveFocus() {
        assertDoesNotThrow(() -> camera.driveFocusNear(1));
        assertDoesNotThrow(() -> camera.driveFocusNear(2));
        assertDoesNotThrow(() -> camera.driveFocusNear(3));
        assertDoesNotThrow(() -> camera.driveFocusFar(1));
        assertDoesNotThrow(() -> camera.driveFocusFar(2));
        assertDoesNotThrow(() -> camera.driveFocusFar(3));
    }

    @Test
    void liveView() {
        camera.startLiveView();
        byte[] frame = camera.getLiveViewFrame();
        assertNotNull(frame);
        assertTrue(frame.length > 0);
        // Should start with JPEG SOI
        assertEquals((byte) 0xFF, frame[0]);
        assertEquals((byte) 0xD8, frame[1]);
        camera.stopLiveView();
    }

    @Test
    void listImages() {
        List<StorageEntry> images = camera.listImages();
        assertNotNull(images);
        assertEquals(2, images.size());
        assertTrue(images.get(0).filename().contains("IMG_"));
    }

    @Test
    void downloadImage() {
        byte[] data = camera.downloadImage(1);
        assertNotNull(data);
        assertTrue(data.length > 0);
    }

    @Test
    void downloadThumbnail() {
        byte[] data = camera.downloadThumbnail(1);
        assertNotNull(data);
        assertTrue(data.length > 0);
    }

    @Test
    void getProperties() {
        assertNotNull(camera.getAperture());
        assertNotNull(camera.getShutterSpeed());
        assertNotNull(camera.getIso());
        assertTrue(camera.getBatteryLevel() > 0);
    }
}
