package com.photographer.focusstack.astro.service;

import com.photographer.focusstack.astro.entity.AstroSessionEntity;
import com.photographer.focusstack.astro.repository.AstroSessionRepository;
import com.photographer.focusstack.camera.backend.CameraBackendFactory;
import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.camera.CaptureResult;
import com.photographer.focusstack.common.model.AstroCaptureConfig;
import com.photographer.focusstack.common.model.AstroFocusConfig;
import com.photographer.focusstack.common.model.AstroSession;
import com.photographer.focusstack.common.model.AstroSessionStatus;
import com.photographer.focusstack.session.CameraBackendProvider;
import com.photographer.focusstack.session.CameraBackendSession;
import com.photographer.focusstack.session.CameraSessionPurpose;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AstroModeServiceLeaseLifecycleTest {

    @TempDir
    Path tempDir;

    @Test
    void preparationOpensSelectedCameraBeforeExecutionAndFailureReleases() {
        CameraBackend backend = mock(CameraBackend.class);
        when(backend.capture()).thenThrow(
                new IllegalStateException("http://private-camera.example/secret"));
        TrackingProvider provider = new TrackingProvider(backend);
        Fixture fixture = fixture(provider);
        AstroSession created = fixture.service.createSession(
                settings("real-camera", 1), AstroFocusConfig.manualFixed());

        AstroModeService.PreparedExecution execution =
                fixture.service.prepareExecution(created.id());

        assertThat(provider.opens.get()).isEqualTo(1);
        assertThat(provider.closes.get()).isZero();
        assertThat(fixture.service.getSession(created.id()).orElseThrow().status())
                .isEqualTo(AstroSessionStatus.PREPARING);
        verify(backend, never()).capture();

        execution.run();

        AstroSession failed = fixture.service.getSession(created.id()).orElseThrow();
        assertThat(failed.status()).isEqualTo(AstroSessionStatus.FAILED);
        assertThat(failed.errorMessage()).isEqualTo("Astro session execution failed");
        assertThat(failed.errorMessage()).doesNotContain("private-camera");
        assertThat(provider.closes.get()).isEqualTo(1);
    }

    @Test
    void queuedAbortReleasesWithoutCameraContact() {
        CameraBackend backend = mock(CameraBackend.class);
        TrackingProvider provider = new TrackingProvider(backend);
        Fixture fixture = fixture(provider);
        AstroSession created = fixture.service.createSession(
                settings("real-camera", 1), AstroFocusConfig.manualFixed());
        AstroModeService.PreparedExecution execution =
                fixture.service.prepareExecution(created.id());

        fixture.service.requestAbort(created.id());

        assertThat(provider.closes.get()).isEqualTo(1);
        assertThat(fixture.service.getSession(created.id()).orElseThrow().status())
                .isEqualTo(AstroSessionStatus.ABORTED);
        execution.run();
        verify(backend, never()).capture();
        assertThat(provider.closes.get()).isEqualTo(1);
    }

    @Test
    void schedulingRejectionMarksFailedAndReleasesExactlyOnce() {
        CameraBackend backend = mock(CameraBackend.class);
        TrackingProvider provider = new TrackingProvider(backend);
        Fixture fixture = fixture(provider);
        AstroSession created = fixture.service.createSession(
                settings("real-camera", 1), AstroFocusConfig.manualFixed());
        AstroModeService.PreparedExecution execution =
                fixture.service.prepareExecution(created.id());

        execution.reject(new IllegalStateException("executor rejected private-camera"));
        execution.reject(new IllegalStateException("duplicate rejection"));

        AstroSession failed = fixture.service.getSession(created.id()).orElseThrow();
        assertThat(failed.status()).isEqualTo(AstroSessionStatus.FAILED);
        assertThat(failed.errorMessage()).isEqualTo("Astro session scheduling failed");
        assertThat(provider.closes.get()).isEqualTo(1);
        execution.run();
        verify(backend, never()).capture();
    }

    @Test
    void runningAbortRetainsOwnershipUntilCaptureReturns() throws Exception {
        CameraBackend backend = mock(CameraBackend.class);
        CountDownLatch captureEntered = new CountDownLatch(1);
        CountDownLatch releaseCapture = new CountDownLatch(1);
        when(backend.capture()).thenAnswer(invocation -> {
            captureEntered.countDown();
            if (!releaseCapture.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("capture latch timed out");
            }
            return capture("frame-running", png());
        });
        TrackingProvider provider = new TrackingProvider(backend);
        Fixture fixture = fixture(provider);
        AstroSession created = fixture.service.createSession(
                settings("real-camera", 1), AstroFocusConfig.manualFixed());
        AstroModeService.PreparedExecution execution =
                fixture.service.prepareExecution(created.id());
        Thread worker = new Thread(execution, "astro-owned-execution-test");
        worker.start();
        assertThat(captureEntered.await(5, TimeUnit.SECONDS)).isTrue();

        fixture.service.requestAbort(created.id());

        assertThat(provider.closes.get()).isZero();
        releaseCapture.countDown();
        worker.join(5_000);

        assertThat(worker.isAlive()).isFalse();
        assertThat(fixture.service.getSession(created.id()).orElseThrow().status())
                .isEqualTo(AstroSessionStatus.ABORTED);
        assertThat(provider.closes.get()).isEqualTo(1);
    }

    @Test
    void missingCameraProfilePreservesUnmanagedMockPath() {
        CameraBackend mockBackend = mock(CameraBackend.class);
        CameraBackendFactory factory = mock(CameraBackendFactory.class);
        AstroSessionRepository repository = mock(AstroSessionRepository.class);
        AtomicReference<AstroSessionEntity> stored = wireRepository(repository);
        when(factory.getBackend(any())).thenReturn(mockBackend);
        CameraBackendProvider provider = mock(CameraBackendProvider.class);
        AstroModeService service = new AstroModeService(
                repository, factory, provider, tempDir.toString());
        AstroSession created = service.createSession(
                settings(null, 1), AstroFocusConfig.manualFixed());
        AstroModeService.PreparedExecution execution = service.prepareExecution(created.id());

        execution.reject(new IllegalStateException("test rejection"));

        verify(provider, never()).openSession(any(), any(), any());
        verify(factory).getBackend(any());
        assertThat(stored.get().getStatus()).isEqualTo(AstroSessionStatus.FAILED);
    }

    private Fixture fixture(CameraBackendProvider provider) {
        AstroSessionRepository repository = mock(AstroSessionRepository.class);
        AtomicReference<AstroSessionEntity> stored = wireRepository(repository);
        CameraBackendFactory factory = mock(CameraBackendFactory.class);
        AstroModeService service = new AstroModeService(
                repository, factory, provider, tempDir.toString());
        return new Fixture(service, stored);
    }

    private static AtomicReference<AstroSessionEntity> wireRepository(
            AstroSessionRepository repository) {
        AtomicReference<AstroSessionEntity> stored = new AtomicReference<>();
        when(repository.save(any(AstroSessionEntity.class))).thenAnswer(invocation -> {
            AstroSessionEntity entity = invocation.getArgument(0);
            stored.set(entity);
            return entity;
        });
        when(repository.findById(any())).thenAnswer(invocation -> {
            AstroSessionEntity entity = stored.get();
            String id = invocation.getArgument(0);
            return entity != null && entity.getId().equals(id)
                    ? Optional.of(entity)
                    : Optional.empty();
        });
        return stored;
    }

    private static AstroCaptureConfig settings(String cameraProfileId, int frameCount) {
        return new AstroCaptureConfig(
                cameraProfileId, frameCount, 1000, 800, null,
                0, "PNG", false, true, false);
    }

    private static CaptureResult capture(String id, byte[] imageData) {
        return new CaptureResult(
                id, id + ".png", "/mock/" + id + ".png",
                Instant.now(), 0, imageData.length, imageData);
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", output);
        return output.toByteArray();
    }

    private record Fixture(
            AstroModeService service,
            AtomicReference<AstroSessionEntity> stored) {
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
            throw new AssertionError("Owned astro execution must use openSession");
        }

        @Override
        public CameraBackendSession openSession(
                String cameraProfileId,
                CameraSessionPurpose purpose,
                String workflowId) {
            assertThat(cameraProfileId).isEqualTo("real-camera");
            assertThat(purpose).isEqualTo(CameraSessionPurpose.ASTRO);
            opens.incrementAndGet();
            return CameraBackendSession.managed(backend, closes::incrementAndGet);
        }
    }
}
