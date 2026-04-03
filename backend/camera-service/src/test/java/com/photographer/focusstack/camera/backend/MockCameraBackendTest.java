package com.photographer.focusstack.camera.backend;

import com.canon.focusstack.common.camera.CameraInfo;
import com.canon.focusstack.common.camera.CameraState;
import com.canon.focusstack.common.camera.CaptureResult;
import com.canon.focusstack.common.camera.FocusCommand;
import com.canon.focusstack.common.camera.LiveViewFrame;
import com.canon.focusstack.common.camera.Operation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockCameraBackendTest {

    private MockCameraBackend backend;

    @BeforeEach
    void setUp() {
        backend = new MockCameraBackend(0);
    }

    @Test
    void getInfo_returnsMockCameraInfo() {
        CameraInfo info = backend.getInfo();
        assertNotNull(info);
        assertEquals("Mock Camera", info.model());
        assertEquals("mock", info.backendType());
    }

    @Test
    void getLiveView_returnsFrameData() {
        LiveViewFrame frame = backend.getLiveView();
        assertNotNull(frame);
        assertEquals(640, frame.width());
        assertEquals(480, frame.height());
        assertNotNull(frame.data());
        assertTrue(frame.data().length > 0);
    }

    @Test
    void capture_incrementsFocusPosition() {
        CaptureResult result1 = backend.capture();
        CaptureResult result2 = backend.capture();

        assertNotNull(result1);
        assertNotNull(result2);
        assertEquals(0, result1.focusPosition());
        assertEquals(1, result2.focusPosition());
    }

    @Test
    void setFocus_adjustsPosition() {
        backend.setFocus(new FocusCommand(FocusCommand.Direction.FAR, 5));
        CameraState state = backend.getState();
        assertEquals(5, state.currentFocusPosition());

        backend.setFocus(new FocusCommand(FocusCommand.Direction.NEAR, 3));
        state = backend.getState();
        assertEquals(2, state.currentFocusPosition());
    }

    @Test
    void getState_returnsConnectedState() {
        CameraState state = backend.getState();
        assertNotNull(state);
        assertTrue(state.connected());
        assertTrue(state.liveViewActive());
    }

    @Test
    void supports_allOperations() {
        for (Operation op : Operation.values()) {
            assertTrue(backend.supports(op));
        }
    }
}
