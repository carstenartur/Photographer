package com.photographer.focusstack.session;

import com.photographer.focusstack.capture.SequenceCaptureService;
import com.photographer.focusstack.common.model.CaptureFrame;
import com.photographer.focusstack.common.model.CaptureSession;
import com.photographer.focusstack.common.model.FaceOrbitSettings;
import com.photographer.focusstack.common.model.FaceProfileJob;
import com.photographer.focusstack.common.model.QualityCheck;
import com.photographer.focusstack.common.model.SessionStatus;
import com.photographer.focusstack.face.analysis.MockFaceAnalysisEngine;
import com.photographer.focusstack.face.profile.MockFaceProfileEngine;
import com.photographer.focusstack.storage.FileSessionStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FaceOrbitServiceTest {

    @TempDir
    Path tempDir;

    private SequenceCaptureService captureService;
    private FaceOrbitService service;

    @BeforeEach
    void setUp() {
        captureService = mock(SequenceCaptureService.class);
        service = new FaceOrbitService(
                captureService,
                new FileSessionStorage(tempDir),
                new MockFaceAnalysisEngine(),
                new MockFaceProfileEngine());
    }

    @Test
    void createSession_returnsCreatedSession() {
        FaceOrbitSettings settings = FaceOrbitSettings.defaults();

        CaptureSession session = service.createSession(settings);

        assertNotNull(session.sessionId());
        assertEquals("face-orbit", session.type());
        assertEquals(SessionStatus.CREATED, session.status());
    }

    @Test
    void captureNextStep_returnsQualityCheck() {
        FaceOrbitSettings settings = FaceOrbitSettings.defaults();
        CaptureSession session = service.createSession(settings);

        when(captureService.captureFrame(anyString(), anyInt(), anyDouble()))
                .thenReturn(new CaptureFrame("f1", session.sessionId(), "img1.jpg", "/img1.jpg",
                        Instant.now(), 0.0, 0, Map.of("angle", "0.0")));

        QualityCheck quality = service.captureNextStep(session.sessionId());

        assertNotNull(quality);
        assertTrue(quality.passed());
    }

    @Test
    void executeAutoMode_completesSuccessfully() {
        FaceOrbitSettings settings = new FaceOrbitSettings(3, 180.0, true, true, 0.5, 0.3, true);
        CaptureSession session = service.createSession(settings);

        when(captureService.captureFrame(anyString(), anyInt(), anyDouble()))
                .thenReturn(new CaptureFrame("f1", session.sessionId(), "img1.jpg", "/img1.jpg",
                        Instant.now(), 0.0, 0, Map.of("angle", "0.0")));

        CaptureSession result = service.executeAutoMode(session.sessionId());

        assertEquals(SessionStatus.COMPLETED, result.status());
    }

    @Test
    void generateProfile_returnsCompletedJob() {
        FaceOrbitSettings settings = new FaceOrbitSettings(2, 180.0, false, false, 0.5, 0.3, false);
        CaptureSession session = service.createSession(settings);

        when(captureService.captureFrame(anyString(), anyInt(), anyDouble()))
                .thenReturn(new CaptureFrame("f1", session.sessionId(), "img1.jpg", "/img1.jpg",
                        Instant.now(), 0.0, 0, Map.of("angle", "0.0")));

        // Capture some frames first
        service.captureNextStep(session.sessionId());
        service.captureNextStep(session.sessionId());
        service.completeSession(session.sessionId());

        FaceProfileJob job = service.generateProfile(session.sessionId());

        assertEquals(SessionStatus.COMPLETED, job.status());
        assertTrue(job.vertexCount() > 0);
        assertTrue(job.confidence() > 0);
    }

    @Test
    void getSession_nonExistent_throwsSessionNotFoundException() {
        assertThrows(SessionNotFoundException.class, () -> service.getSession("nonexistent"));
    }

    @Test
    void completeSession_updatesStatus() {
        FaceOrbitSettings settings = FaceOrbitSettings.defaults();
        CaptureSession session = service.createSession(settings);

        CaptureSession completed = service.completeSession(session.sessionId());

        assertEquals(SessionStatus.COMPLETED, completed.status());
    }
}
