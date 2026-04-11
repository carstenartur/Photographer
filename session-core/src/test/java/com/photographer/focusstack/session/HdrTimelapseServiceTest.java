package com.photographer.focusstack.session;

import com.photographer.focusstack.capture.BracketCaptureService;
import com.photographer.focusstack.common.model.BracketSet;
import com.photographer.focusstack.common.model.CaptureFrame;
import com.photographer.focusstack.common.model.CaptureSession;
import com.photographer.focusstack.common.model.HdrSettings;
import com.photographer.focusstack.common.model.SessionStatus;
import com.photographer.focusstack.hdr.HdrProcessor;
import com.photographer.focusstack.hdr.MockHdrProcessor;
import com.photographer.focusstack.hdr.MockTonemapper;
import com.photographer.focusstack.hdr.Tonemapper;
import com.photographer.focusstack.storage.FileSessionStorage;
import com.photographer.focusstack.storage.SessionStorage;
import com.photographer.focusstack.video.MockVideoEncoder;
import com.photographer.focusstack.video.VideoEncoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HdrTimelapseServiceTest {

    @TempDir
    Path tempDir;

    private BracketCaptureService captureService;
    private HdrTimelapseService service;

    @BeforeEach
    void setUp() {
        captureService = mock(BracketCaptureService.class);
        SessionStorage storage = new FileSessionStorage(tempDir);
        HdrProcessor hdrProcessor = new MockHdrProcessor();
        Tonemapper tonemapper = new MockTonemapper();
        VideoEncoder videoEncoder = new MockVideoEncoder();
        service = new HdrTimelapseService(captureService, storage, hdrProcessor, tonemapper, videoEncoder);
    }

    @Test
    void createSession_returnsCreatedSession() {
        HdrSettings settings = HdrSettings.defaults();

        CaptureSession session = service.createSession(settings);

        assertNotNull(session.sessionId());
        assertEquals("hdr-timelapse", session.type());
        assertEquals(SessionStatus.CREATED, session.status());
    }

    @Test
    void executeSession_completesSuccessfully() {
        HdrSettings settings = new HdrSettings(0, 3, 1.0, 2, false, true);
        CaptureSession session = service.createSession(settings);

        when(captureService.captureBracket(anyString(), eq(3), eq(1.0), anyInt()))
                .thenReturn(new BracketSet("bracket-1", session.sessionId(), Instant.now(),
                        List.of(new CaptureFrame("f1", session.sessionId(), "img1.jpg", "/img1.jpg",
                                Instant.now(), -1.0, 0, Map.of())), null));

        CaptureSession result = service.executeSession(session.sessionId());

        assertEquals(SessionStatus.COMPLETED, result.status());
        assertNotNull(result.completedAt());
    }

    @Test
    void executeSession_invalidState_throwsException() {
        HdrSettings settings = HdrSettings.defaults();
        CaptureSession session = service.createSession(settings);

        when(captureService.captureBracket(anyString(), anyInt(), anyDouble(), anyInt()))
                .thenReturn(new BracketSet("bracket-1", session.sessionId(), Instant.now(),
                        List.of(new CaptureFrame("f1", session.sessionId(), "img1.jpg", "/img1.jpg",
                                Instant.now(), 0.0, 0, Map.of())), null));

        // Execute to completion first
        service.executeSession(session.sessionId());

        // Try to execute again
        assertThrows(SessionException.class, () -> service.executeSession(session.sessionId()));
    }

    @Test
    void getSession_nonExistent_throwsException() {
        assertThrows(SessionException.class, () -> service.getSession("nonexistent"));
    }

    @Test
    void getBrackets_returnsCaptureBrackets() {
        HdrSettings settings = new HdrSettings(0, 3, 1.0, 1, false, false);
        CaptureSession session = service.createSession(settings);

        when(captureService.captureBracket(anyString(), eq(3), eq(1.0), anyInt()))
                .thenReturn(new BracketSet("bracket-1", session.sessionId(), Instant.now(),
                        List.of(new CaptureFrame("f1", session.sessionId(), "img1.jpg", "/img1.jpg",
                                Instant.now(), 0.0, 0, Map.of())), null));

        service.executeSession(session.sessionId());
        List<BracketSet> brackets = service.getBrackets(session.sessionId());

        assertFalse(brackets.isEmpty());
    }
}
