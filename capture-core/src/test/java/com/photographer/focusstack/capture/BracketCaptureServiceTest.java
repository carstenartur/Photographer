package com.photographer.focusstack.capture;

import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.camera.CaptureResult;
import com.photographer.focusstack.common.model.BracketSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BracketCaptureServiceTest {

    private CameraBackend cameraBackend;
    private BracketCaptureService service;

    @BeforeEach
    void setUp() {
        cameraBackend = mock(CameraBackend.class);
        service = new BracketCaptureService(cameraBackend);
    }

    @Test
    void captureBracket_capturesCorrectNumberOfFrames() {
        when(cameraBackend.capture()).thenReturn(
                new CaptureResult("img-1", "IMG_1.jpg", "/mock/img-1.jpg", Instant.now(), 0, 1024L, null));

        BracketSet result = service.captureBracket("session-1", 3, 1.0, 0);

        assertEquals(3, result.frames().size());
        assertEquals("session-1", result.sessionId());
        verify(cameraBackend, times(3)).capture();
    }

    @Test
    void captureBracket_setsCorrectEvOffsets() {
        when(cameraBackend.capture()).thenReturn(
                new CaptureResult("img-1", "IMG_1.jpg", "/mock/img-1.jpg", Instant.now(), 0, 1024L, null));

        BracketSet result = service.captureBracket("session-1", 3, 1.0, 0);

        assertEquals("-1.0", result.frames().get(0).metadata().get("evOffset"));
        assertEquals("0.0", result.frames().get(1).metadata().get("evOffset"));
        assertEquals("1.0", result.frames().get(2).metadata().get("evOffset"));
    }

    @Test
    void calculateEvOffset_threeFrames() {
        assertEquals(-1.0, BracketCaptureService.calculateEvOffset(0, 3, 1.0));
        assertEquals(0.0, BracketCaptureService.calculateEvOffset(1, 3, 1.0));
        assertEquals(1.0, BracketCaptureService.calculateEvOffset(2, 3, 1.0));
    }

    @Test
    void calculateEvOffset_fiveFrames() {
        assertEquals(-1.0, BracketCaptureService.calculateEvOffset(0, 5, 0.5));
        assertEquals(-0.5, BracketCaptureService.calculateEvOffset(1, 5, 0.5));
        assertEquals(0.0, BracketCaptureService.calculateEvOffset(2, 5, 0.5));
        assertEquals(0.5, BracketCaptureService.calculateEvOffset(3, 5, 0.5));
        assertEquals(1.0, BracketCaptureService.calculateEvOffset(4, 5, 0.5));
    }
}
