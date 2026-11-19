package com.photographer.focusstack.observer.service;

import com.photographer.focusstack.ai.VisionProvider;
import com.photographer.focusstack.camera.backend.CameraBackendFactory;
import com.photographer.focusstack.camera.service.CameraProfileService;
import com.photographer.focusstack.camera.service.RawImagePreviewService;
import com.photographer.focusstack.camera.session.*;
import com.photographer.focusstack.common.camera.*;
import com.photographer.focusstack.common.model.CameraProfile;
import com.photographer.focusstack.observer.bird.BirdTriggerSettings;
import com.photographer.focusstack.observer.events.BoundingBox;
import com.photographer.focusstack.observer.events.ObservationEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LocalBirdObserverServiceTest {
    @TempDir Path output;
    private final CameraBackendFactory backends = mock(CameraBackendFactory.class);
    private final CameraProfileService profiles = mock(CameraProfileService.class);
    private final CameraSessionLeaseManager leases = mock(CameraSessionLeaseManager.class);
    private final CameraLeaseIdentityProvider identities = mock(CameraLeaseIdentityProvider.class);
    private final CameraSessionLease lease = mock(CameraSessionLease.class);
    private final CameraBackend camera = mock(CameraBackend.class);
    private final VisionProvider vision = mock(VisionProvider.class);
    private final ObserverService events = mock(ObserverService.class);
    private LocalBirdObserverService service;
    private final AtomicInteger sequence = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        var profile = new CameraProfile("camera", "Perch", "canon-ccapi", "camera.local", 1, 10, .01);
        when(profiles.getProfile("camera")).thenReturn(profile);
        when(identities.resolve(profile)).thenReturn(Optional.of(CameraLeaseKey.fromCcapiProfile(profile)));
        when(leases.acquire(any())).thenReturn(lease);
        when(backends.getBackend(profile)).thenReturn(camera);
        when(camera.supports(any())).thenReturn(true);
        when(camera.getLiveView()).thenAnswer(inv -> new LiveViewFrame(
                BirdFrameAnalyzerTest.jpeg(sequence.incrementAndGet()), 80, 60, Instant.now(), "image/jpeg"));
        when(camera.capture()).thenAnswer(inv -> new CaptureResult("capture", "../../ORIGINAL.JPG", null,
                Instant.now(), 0, 0, BirdFrameAnalyzerTest.jpeg(77)));
        when(vision.isAvailable()).thenReturn(true);
        when(vision.detectObjects(any(), any())).thenReturn(BirdFrameAnalyzerTest.detected(
                new BoundingBox(.2, .2, .4, .4, "bird", .9)));
        service = new LocalBirdObserverService(backends, profiles, leases, identities, vision,
                new BirdCaptureStore(output.toString(), new RawImagePreviewService()), events);
    }

    @AfterEach
    void stop() throws Exception {
        service.requestCameraShutdown();
        await(() -> service.activeCameraExecutions() == 0);
    }

    @Test
    void previewDoesNotCaptureAndExplicitArmingPersistsUncroppedOriginal() throws Exception {
        service.start("camera", false, BirdTriggerSettings.defaults());
        await(() -> service.status().framesAnalyzed() >= 1);
        verify(camera, never()).capture();
        var settings = new BirdTriggerSettings(BirdTriggerSettings.defaults().roi(), .5, .0025, 200, 0, 3);
        service.update("camera", true, settings);
        await(() -> service.status().lastEventId() != null);
        service.stop();
        await(() -> service.activeCameraExecutions() == 0);
        assertEquals(1, service.status().captureCount());
        var event = org.mockito.ArgumentCaptor.forClass(ObservationEvent.class);
        verify(events).onEventCompleted(event.capture());
        assertEquals("bird", event.getValue().aiResult().primaryLabel());
        var original = Path.of(event.getValue().images().getFirst().filePath());
        assertTrue(original.startsWith(output));
        assertArrayEquals(BirdFrameAnalyzerTest.jpeg(77), Files.readAllBytes(original));
        verify(lease).close();
    }

    @Test
    void stopDuringInferencePreventsCaptureAndRetainsLeaseUntilWorkerExits() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(vision.detectObjects(any(), any())).thenAnswer(inv -> {
            if (calls.incrementAndGet() > 1) {
                entered.countDown();
                awaitUninterruptibly(release);
            }
            return BirdFrameAnalyzerTest.detected(new BoundingBox(.2, .2, .4, .4, "bird", .9));
        });
        service.start("camera", false, BirdTriggerSettings.defaults());
        await(() -> service.status().framesAnalyzed() == 1);
        service.update("camera", true, BirdTriggerSettings.defaults());
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        service.stop();
        assertEquals("STOPPING", service.status().phase());
        verify(lease, never()).close();
        release.countDown();
        await(() -> service.activeCameraExecutions() == 0);
        verify(camera, never()).capture();
        verify(lease).close();
    }

    @Test
    void settingsChangeDiscardsInFlightDetection() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(vision.detectObjects(any(), any())).thenAnswer(inv -> {
            entered.countDown();
            awaitUninterruptibly(release);
            return BirdFrameAnalyzerTest.detected(new BoundingBox(.2, .2, .4, .4, "bird", .9));
        });
        service.start("camera", false, BirdTriggerSettings.defaults());
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        service.update("camera", true, BirdTriggerSettings.defaults());
        // A preview-only result cannot be reused as the first armed detection.
        when(camera.getLiveView()).thenThrow(new IllegalStateException("camera offline"));
        release.countDown();
        await(() -> service.status().skippedFrames() > 0);
        verify(camera, never()).capture();
    }

    @Test
    void staleFramesAndMissingOriginalNeverBecomeSuccessfulEvents() throws Exception {
        when(camera.getLiveView()).thenReturn(new LiveViewFrame(BirdFrameAnalyzerTest.jpeg(1),
                80, 60, Instant.now().minusSeconds(30), "image/jpeg"));
        service.start("camera", false, BirdTriggerSettings.defaults());
        service.update("camera", true, BirdTriggerSettings.defaults());
        await(() -> service.status().skippedFrames() > 0);
        verifyNoInteractions(events);
        verify(vision, never()).detectObjects(any(), any());
        verify(camera, never()).capture();
    }

    @Test
    void unavailableModelDoesNotAcquireCamera() {
        when(vision.isAvailable()).thenReturn(false);
        assertThrows(IllegalStateException.class,
                () -> service.start("camera", false, BirdTriggerSettings.defaults()));
        verifyNoInteractions(backends, leases);
        assertFalse(service.status().modelAvailable());
    }

    @Test
    void repeatedStopAndShutdownDrainAnAlreadyCommittedPhotoWithoutInterruptingIt() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        when(camera.capture()).thenAnswer(inv -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException ex) { interrupted.set(true); throw new IllegalStateException("download interrupted"); }
            return new CaptureResult("capture", "original.jpg", null, Instant.now(), 0, 0, BirdFrameAnalyzerTest.jpeg(77));
        });
        service.start("camera", false, BirdTriggerSettings.defaults());
        await(() -> service.status().framesAnalyzed() > 0);
        service.update("camera", true, BirdTriggerSettings.defaults());
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        service.stop();
        service.requestCameraShutdown();
        release.countDown();
        await(() -> service.activeCameraExecutions() == 0);
        assertFalse(interrupted.get(), "A second stop must not interrupt the committed photo download");
        verify(events).onEventCompleted(any());
    }

    @Test
    void folderProfilesAreRejectedInsteadOfGettingStuckOnDuplicatePreview() {
        when(profiles.getProfile("folder")).thenReturn(new CameraProfile("folder", "Folder", "folder", "/images", 1, 10, .01));
        assertThrows(IllegalArgumentException.class, () -> service.start("folder", false, BirdTriggerSettings.defaults()));
        verifyNoInteractions(backends, leases);
    }

    @Test
    void requestedCancellationReportedByProviderFinishesStoppedRatherThanError() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        when(vision.detectObjects(any(), any())).thenAnswer(inv -> {
            entered.countDown();
            try { new CountDownLatch(1).await(5, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            return com.photographer.focusstack.ai.VisionResult.error("local-bird", "object_detection", "interrupted");
        });
        service.start("camera", false, BirdTriggerSettings.defaults());
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        service.stop();
        await(() -> service.activeCameraExecutions() == 0);
        assertEquals("STOPPED", service.status().phase());
        assertNull(service.status().error());
        verify(camera, never()).capture();
    }

    @Test
    void automaticStartRequiresSeparateArmingAndShutdownBlocksRestart() {
        assertThrows(IllegalArgumentException.class,
                () -> service.start("camera", true, BirdTriggerSettings.defaults()));
        service.requestCameraShutdown();
        assertThrows(IllegalStateException.class,
                () -> service.start("camera", false, BirdTriggerSettings.defaults()));
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Timed out waiting for observer state");
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try { latch.await(); break; }
            catch (InterruptedException ex) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
