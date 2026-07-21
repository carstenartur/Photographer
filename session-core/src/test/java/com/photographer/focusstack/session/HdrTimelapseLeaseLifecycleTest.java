package com.photographer.focusstack.session;

import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.camera.CaptureResult;
import com.photographer.focusstack.common.model.CaptureSession;
import com.photographer.focusstack.common.model.HdrSettings;
import com.photographer.focusstack.common.model.SessionStatus;
import com.photographer.focusstack.hdr.MockHdrProcessor;
import com.photographer.focusstack.hdr.MockTonemapper;
import com.photographer.focusstack.storage.FileSessionStorage;
import com.photographer.focusstack.video.MockVideoEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HdrTimelapseLeaseLifecycleTest {

    @TempDir
    Path tempDir;

    @Test
    void preparationOpensOwnershipBeforeAnyCaptureAndCompletionReleasesIt() {
        CameraBackend backend = mock(CameraBackend.class);
        when(backend.capture()).thenReturn(capture("frame-1"));
        TrackingProvider provider = new TrackingProvider(backend);
        HdrTimelapseService service = service(provider);
        CaptureSession created = service.createSession(settings());

        HdrTimelapseService.PreparedExecution execution =
                service.prepareExecution(created.sessionId());

        assertEquals(1, provider.opens.get());
        assertEquals(0, provider.closes.get());
        assertEquals(SessionStatus.RUNNING,
                service.getSession(created.sessionId()).status());
        verify(backend, never()).capture();

        CaptureSession completed = execution.execute();

        assertEquals(SessionStatus.COMPLETED, completed.status());
        assertEquals(1, provider.closes.get());
        verify(backend).capture();
        assertEquals(CameraSessionPurpose.HDR_TIMELAPSE, provider.lastPurpose);
        assertEquals(created.sessionId(), provider.lastWorkflowId);
    }

    @Test
    void queuedStopCancelsAndReleasesWithoutCameraContact() {
        CameraBackend backend = mock(CameraBackend.class);
        TrackingProvider provider = new TrackingProvider(backend);
        HdrTimelapseService service = service(provider);
        CaptureSession created = service.createSession(settings());
        HdrTimelapseService.PreparedExecution execution =
                service.prepareExecution(created.sessionId());

        service.stopSession(created.sessionId());

        assertEquals(1, provider.closes.get());
        assertEquals(SessionStatus.CANCELLED,
                service.getSession(created.sessionId()).status());
        execution.run();
        verify(backend, never()).capture();
        assertEquals(1, provider.closes.get());
    }

    @Test
    void schedulingRejectionMarksFailedAndReleasesExactlyOnce() {
        CameraBackend backend = mock(CameraBackend.class);
        TrackingProvider provider = new TrackingProvider(backend);
        HdrTimelapseService service = service(provider);
        CaptureSession created = service.createSession(settings());
        HdrTimelapseService.PreparedExecution execution =
                service.prepareExecution(created.sessionId());

        execution.reject(new IllegalStateException("executor rejected"));
        execution.reject(new IllegalStateException("duplicate rejection"));

        assertEquals(SessionStatus.FAILED,
                service.getSession(created.sessionId()).status());
        assertEquals(1, provider.closes.get());
        execution.run();
        verify(backend, never()).capture();
    }

    @Test
    void runningStopRetainsOwnershipUntilCaptureReturns() throws Exception {
        CameraBackend backend = mock(CameraBackend.class);
        CountDownLatch captureEntered = new CountDownLatch(1);
        CountDownLatch releaseCapture = new CountDownLatch(1);
        when(backend.capture()).thenAnswer(invocation -> {
            captureEntered.countDown();
            if (!releaseCapture.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("capture latch timed out");
            }
            return capture("frame-running");
        });
        TrackingProvider provider = new TrackingProvider(backend);
        HdrTimelapseService service = service(provider);
        CaptureSession created = service.createSession(settings());
        HdrTimelapseService.PreparedExecution execution =
                service.prepareExecution(created.sessionId());
        Thread worker = new Thread(execution, "hdr-owned-execution-test");
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

    @Test
    void backendFailureMarksFailedAndStillReleasesOwnership() {
        CameraBackend backend = mock(CameraBackend.class);
        when(backend.capture()).thenThrow(new IllegalStateException("synthetic capture failure"));
        TrackingProvider provider = new TrackingProvider(backend);
        HdrTimelapseService service = service(provider);
        CaptureSession created = service.createSession(settings());

        CaptureSession result = service.prepareExecution(created.sessionId()).execute();

        assertEquals(SessionStatus.FAILED, result.status());
        assertEquals(1, provider.closes.get());
    }

    private HdrTimelapseService service(CameraBackendProvider provider) {
        return new HdrTimelapseService(
                provider,
                new FileSessionStorage(tempDir),
                new MockHdrProcessor(),
                new MockTonemapper(),
                new MockVideoEncoder(),
                milliseconds -> { });
    }

    private static HdrSettings settings() {
        return new HdrSettings("camera-profile", 0, 1, 1.0, 1, false, false);
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
        private CameraSessionPurpose lastPurpose;
        private String lastWorkflowId;

        private TrackingProvider(CameraBackend backend) {
            this.backend = backend;
        }

        @Override
        public CameraBackend getBackend(String cameraProfileId) {
            throw new AssertionError("Owned execution must use openSession");
        }

        @Override
        public CameraBackendSession openSession(
                String cameraProfileId,
                CameraSessionPurpose purpose,
                String workflowId) {
            opens.incrementAndGet();
            lastPurpose = purpose;
            lastWorkflowId = workflowId;
            return CameraBackendSession.managed(backend, closes::incrementAndGet);
        }
    }
}
