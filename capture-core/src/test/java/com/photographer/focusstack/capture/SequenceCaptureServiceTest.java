package com.photographer.focusstack.capture;

import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.camera.CaptureResult;
import com.photographer.focusstack.common.model.CaptureFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SequenceCaptureServiceTest {

    private CameraBackend cameraBackend;
    private SequenceCaptureService service;

    @BeforeEach
    void setUp() {
        cameraBackend = mock(CameraBackend.class);
        service = new SequenceCaptureService(cameraBackend);
    }

    @Test
    void captureFrame_returnsFrameWithMetadata() {
        when(cameraBackend.capture()).thenReturn(
                new CaptureResult("img-1", "IMG_1.jpg", "/mock/img-1.jpg", Instant.now(), 0, 1024L, null));

        CaptureFrame result = service.captureFrame("session-1", 0, 45.0);

        assertEquals("img-1", result.frameId());
        assertEquals("session-1", result.sessionId());
        assertEquals(0, result.sequenceIndex());
        assertEquals("45.0", result.metadata().get("angle"));
    }

    @Test
    void captureFrame_delegatesToCameraBackend() {
        when(cameraBackend.capture()).thenReturn(
                new CaptureResult("img-2", "IMG_2.jpg", "/mock/img-2.jpg", Instant.now(), 0, 2048L, null));

        service.captureFrame("session-2", 5, 90.0);

        verify(cameraBackend, times(1)).capture();
    }
}
