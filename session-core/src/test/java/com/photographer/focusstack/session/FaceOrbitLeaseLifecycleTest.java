package com.photographer.focusstack.session;

import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.camera.CaptureResult;
import com.photographer.focusstack.common.model.CaptureSession;
import com.photographer.focusstack.common.model.FaceOrbitSettings;
import com.photographer.focusstack.common.model.SessionStatus;
import com.photographer.focusstack.face.analysis.MockFaceAnalysisEngine;
import com.photographer.focusstack.face.profile.MockFaceProfileEngine;
import com.photographer.focusstack.storage.FileSessionStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FaceOrbitLeaseLifecycleTest {

    @TempDir
    Path tempDir;

    @Test
    void manualStepsReuseOneOwnershipUntilCompletion() {
        CameraBackend backend = mock(CameraBackend.class);
        when(backend.capture()).thenReturn(capture("one"), capture("two"));
        TrackingProvider provider = new TrackingProvider(backend);
        FaceOrbitService service = service(provider);
        CaptureSession created = service.createSession(settings(false, 2));

        service.captureNextStep(created.sessionId());
        service.captureNextStep(created.sessionId());

        assertEquals(1, provider.opens.get());
        assertEquals(0, provider.closes.get());
        assertEquals(SessionStatus.RUNNING,
                service.getSession(created.sessionId()).status());
        assertEquals(2, service.getSession(created.sessionId()).frameIds().size());

        CaptureSession completed = service.completeSession(created.sessionId());

        assertEquals(SessionStatus.COMPLETED, completed.status());
        assertEquals(1, provider.closes.get());
        verify(backend, times(2)).capture();
    }

    @Test
    void manualStopReleasesAndPreventsFurtherCapture() {
        CameraBackend backend = mock(CameraBackend.class);
        when(backend.capture()).thenReturn(capture("one"));
        TrackingProvider provider = new TrackingProvider(backend);
        FaceOrbitService service = service(provider);
        CaptureSession created = service.createSession(settings(false, 2));
        service.captureNextStep(created.sessionId());

        service.stopSession(created.sessionId());

        assertEquals(1, provider.closes.get());
        assertEquals(SessionStatus.CANCELLED,
                service.getSession(created.sessionId()).status());
        assertThrows(SessionException.class,
                () -> service.captureNextStep(created.sessionId()));
        verify(backend, times(1)).capture();
    }

    @Test
    void manualCaptureFailureSanitizesStateAndReleases() {
        CameraBackend backend = mock(CameraBackend.class);
        when(backend.capture()).thenThrow(
                new IllegalStateException("http://private-camera.example/secret"));
        TrackingProvider provider = new TrackingProvider(backend);
        FaceOrbitService service = service(provider);
        CaptureSession created = service.createSession(settings(false, 1));

        SessionException failure = assertThrows(SessionException.class,
                () -> service.captureNextStep(created.sessionId()));

        assertEquals("Face orbit capture failed", failure.getMessage());
        assertEquals(SessionStatus.FAILED,
                service.getSession(created.sessionId()).status());
        assertEquals("Face orbit capture failed",
                service.getSession(created.sessionId()).errorMessage());
        assertEquals(1, provider.closes.get());
    }

    @Test
    void queuedAutoStopReleasesWithoutCameraContact() {
        CameraBackend backend = mock(CameraBackend.class);
        TrackingProvider provider = new TrackingProvider(backend);
        FaceOrbitService service = service(provider);
        CaptureSession created = service.createSession(settings(true, 1));
        FaceOrbitService.PreparedAutoExecution execution =
                service.prepareAutoExecution(created.sessionId());

        service.stopSession(created.sessionId());

        assertEquals(1, provider.closes.get());
        assertEquals(SessionStatus.CANCELLED,
                service.getSession(created.sessionId()).status());
        execution.run();
        verify(backend, never()).capture();
    }

    @Test
    void autoSchedulingRejectionReleasesExactlyOnce() {
        CameraBackend backend = mock(CameraBackend.class);
        TrackingProvider provider = new TrackingProvider(backend);
        FaceOrbitService service = service(provider);
        CaptureSession created = service.createSession(settings(true, 1));
        FaceOrbitService.PreparedAutoExecution execution =
                service.prepareAutoExecution(created.sessionId());

        execution.reject(new IllegalStateException("executor rejected"));
        execution.reject(new IllegalStateException("duplicate rejection"));

        assertEquals(SessionStatus.FAILED,
                service.getSession(created.sessionId()).status());
        assertEquals(1, provider.closes.get());
        execution.run();
        verify(backend, never()).capture();
    }

    @Test
    void runningAutoStopRetainsOwnershipUntilCaptureReturns() throws Exception {
        CameraBackend backend = mock(CameraBackend.class);
        CountDownLatch captureEntered = new CountDownLatch(1);
        CountDownLatch releaseCapture = new CountDownLatch(1);
        when(backend.capture()).thenAnswer(invocation -> {
            captureEntered.countDown();
            if (!releaseCapture.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("capture latch timed out");
            }
            return capture("running");
        });
        TrackingProvider provider = new TrackingProvider(backend);
        FaceOrbitService service = service(provider);
        CaptureSession created = service.createSession(settings(true, 1));
        FaceOrbitService.PreparedAutoExecution execution =
                service.prepareAutoExecution(created.sessionId());
        Thread worker = new Thread(execution, "face-orbit-owned-execution-test");
        worker.start();
        assertTrue(captureEntered.await(5, TimeUnit.SECONDS));

        service.stopSession(created.sessionId());

        assertEquals(0, provider.closes.get());
        assertEquals(SessionStatus.RUNNING,
                service.getSession(created.sessionId()).status());

        releaseCapture.countDown();
        worker.join(5_000);

        assertFalse(worker.isAlive());
        assertEquals(SessionStatus.CANCELLED,
                service.getSession(created.sessionId()).status());
        assertEquals(1, provider.closes.get());
    }

    private FaceOrbitService service(CameraBackendProvider provider) {
        return new FaceOrbitService(
                provider,
                new FileSessionStorage(tempDir),
                new MockFaceAnalysisEngine(),
                new MockFaceProfileEngine());
    }

    private static FaceOrbitSettings settings(boolean autoMode, int steps) {
        return new FaceOrbitSettings(
                "camera-profile", steps, 180.0, autoMode,
                true, 0.5, 0.3, true);
    }

    private static CaptureResult capture(String id) {
        return new CaptureResult(
                id, id + ".jpg", "/mock/" + id + ".jpg",
                Instant.now(), 0, 1L, new byte[]{1});
    }

    private static final class TrackingProvider implements CameraBackendProvider {
        private final CameraBackend backend;
        private final AtomicInteger opens = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();

        private TrackingProvider(CameraBackend backend) {
            this.backend = backend;
        }

        @Override
        public CameraBackend getBackend(String cameraProfileId) {
            throw new AssertionError("Owned face orbit must use openSession");
        }

        @Override
        public CameraBackendSession openSession(
                String cameraProfileId,
                CameraSessionPurpose purpose,
                String workflowId) {
            assertEquals(CameraSessionPurpose.FACE_ORBIT, purpose);
            opens.incrementAndGet();
            return CameraBackendSession.managed(backend, closes::incrementAndGet);
        }
    }
}
