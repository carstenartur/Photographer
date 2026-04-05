package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.camera.exception.CameraException;
import com.photographer.focusstack.common.camera.CameraInfo;
import com.photographer.focusstack.common.camera.CameraState;
import com.photographer.focusstack.common.camera.CaptureResult;
import com.photographer.focusstack.common.camera.FocusCommand;
import com.photographer.focusstack.common.camera.LiveViewFrame;
import com.photographer.focusstack.common.camera.Operation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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

    // ── Failure simulation tests ──────────────────────────────────────────────

    @Test
    void simulateEmptyFrame_returnsZeroByteImageAtSpecifiedCapture() {
        MockCameraBackend sim = new MockCameraBackend(0, 0, 0, 2, 0);
        CaptureResult first  = sim.capture(); // capture 1 — normal
        CaptureResult second = sim.capture(); // capture 2 — empty

        assertTrue(first.imageData().length > 0, "First frame should have data");
        assertEquals(0, second.imageData().length, "Second frame should be empty");
        assertEquals(0, second.fileSize(), "Empty frame should report fileSize 0");
    }

    @Test
    void simulateMovement_returnsDistinctImageAtSpecifiedCapture() {
        MockCameraBackend sim = new MockCameraBackend(0, 0, 0, 0, 2);
        byte[] normal    = sim.capture().imageData(); // capture 1
        byte[] movement  = sim.capture().imageData(); // capture 2 — simulated movement

        assertNotNull(movement);
        assertTrue(movement.length > 0);
        // The movement image should differ from the normal checkerboard image
        boolean differs = false;
        for (int i = 0; i < Math.min(normal.length, movement.length); i++) {
            if (normal[i] != movement[i]) { differs = true; break; }
        }
        assertTrue(differs, "Movement frame should differ from normal frame");
    }

    @Test
    void simulateDisconnect_throwsAfterThreshold() {
        MockCameraBackend sim = new MockCameraBackend(0, 0, 1, 0, 0);
        sim.capture(); // capture 1 — should succeed
        assertThrows(CameraException.class, sim::capture,
                "Second capture should throw CameraException to simulate disconnect");
    }
}
