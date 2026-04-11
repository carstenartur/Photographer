package com.photographer.ptp.session;

import com.photographer.focusstack.common.camera.*;
import com.photographer.ptp.backend.PtpCameraBackend;
import com.photographer.ptp.mock.MockPtpTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PtpCameraBackendTest {
    private MockPtpTransport transport;
    private PtpCamera camera;
    private PtpCameraBackend backend;

    @BeforeEach
    void setUp() {
        transport = new MockPtpTransport();
        camera = PtpCamera.open(transport);
        backend = new PtpCameraBackend(camera);
    }

    @AfterEach
    void tearDown() {
        if (camera != null) {
            camera.close();
        }
    }

    @Test
    void getInfo() {
        CameraInfo info = backend.getInfo();
        assertNotNull(info);
        assertTrue(info.id().startsWith("ptp-"));
        assertEquals("ptp-usb", info.backendType());
        assertTrue(info.model().contains("Canon") || info.model().contains("PowerShot"));
    }

    @Test
    void capture() {
        CaptureResult result = backend.capture();
        assertNotNull(result);
        assertNotNull(result.fileName());
    }

    @Test
    void getState() {
        CameraState state = backend.getState();
        assertNotNull(state);
        assertTrue(state.connected());
        assertTrue(state.batteryLevel() > 0);
    }

    @Test
    void supportsOperations() {
        assertTrue(backend.supports(Operation.CAPTURE));
        assertTrue(backend.supports(Operation.FOCUS_CONTROL));
        assertTrue(backend.supports(Operation.LIVE_VIEW));
    }

    @Test
    void setFocusNear() {
        FocusCommand cmd = new FocusCommand(FocusCommand.Direction.NEAR, 3);
        assertDoesNotThrow(() -> backend.setFocus(cmd));
    }

    @Test
    void setFocusFar() {
        FocusCommand cmd = new FocusCommand(FocusCommand.Direction.FAR, 1);
        assertDoesNotThrow(() -> backend.setFocus(cmd));
    }
}
