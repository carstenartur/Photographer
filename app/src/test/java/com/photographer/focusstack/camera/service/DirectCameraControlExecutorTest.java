package com.photographer.focusstack.camera.service;

import com.photographer.focusstack.camera.backend.CameraBackendFactory;
import com.photographer.focusstack.camera.session.CameraLeaseKey;
import com.photographer.focusstack.camera.session.CameraLeaseRequest;
import com.photographer.focusstack.camera.session.CameraSessionConflictException;
import com.photographer.focusstack.camera.session.CameraSessionLease;
import com.photographer.focusstack.camera.session.CameraWorkflowType;
import com.photographer.focusstack.camera.session.DefaultCameraLeaseIdentityProvider;
import com.photographer.focusstack.camera.session.InMemoryCameraSessionLeaseManager;
import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.model.CameraProfile;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DirectCameraControlExecutorTest {

    @Test
    void activeWorkflowBlocksCaptureBeforeBackendResolution() {
        CameraProfile profile = profile("camera", "camera.example");
        CameraProfileService profileService = mock(CameraProfileService.class);
        CameraBackendFactory backendFactory = mock(CameraBackendFactory.class);
        when(profileService.getProfile("camera")).thenReturn(profile);
        InMemoryCameraSessionLeaseManager manager = manager();
        DirectCameraControlExecutor executor = executor(
                profileService, backendFactory, manager);
        CameraLeaseKey key = CameraLeaseKey.fromCcapiProfile(profile);

        try (CameraSessionLease ignored = manager.acquire(new CameraLeaseRequest(
                key,
                CameraWorkflowType.HDR_TIMELAPSE,
                "hdr-session",
                "hdr-owner",
                Duration.ofHours(1)))) {
            assertThatThrownBy(() -> executor.withBackend(
                    "camera", CameraBackend::capture))
                    .isInstanceOf(CameraSessionConflictException.class);
        }

        verify(backendFactory, never()).getBackend(profile);
    }

    @Test
    void activeWorkflowBlocksProfileMaintenanceBeforeCacheEvictionCallback() {
        CameraProfile profile = profile("camera", "camera.example");
        CameraProfileService profileService = mock(CameraProfileService.class);
        CameraBackendFactory backendFactory = mock(CameraBackendFactory.class);
        when(profileService.getProfile("camera")).thenReturn(profile);
        InMemoryCameraSessionLeaseManager manager = manager();
        DirectCameraControlExecutor executor = executor(
                profileService, backendFactory, manager);
        CameraLeaseKey key = CameraLeaseKey.fromCcapiProfile(profile);
        AtomicBoolean mutationCalled = new AtomicBoolean();

        try (CameraSessionLease ignored = manager.acquire(new CameraLeaseRequest(
                key,
                CameraWorkflowType.FOCUS_STACKING,
                "stack-job",
                "stack-owner",
                Duration.ofHours(1)))) {
            assertThatThrownBy(() -> executor.withProfileLease(
                    "camera", () -> mutationCalled.set(true)))
                    .isInstanceOf(CameraSessionConflictException.class);
        }

        assertThat(mutationCalled).isFalse();
        verify(backendFactory, never()).getBackend(profile);
    }

    @Test
    void operationFailureReleasesLeaseForNextRequest() {
        CameraProfile profile = profile("camera", "camera.example");
        CameraProfileService profileService = mock(CameraProfileService.class);
        CameraBackendFactory backendFactory = mock(CameraBackendFactory.class);
        CameraBackend backend = mock(CameraBackend.class);
        when(profileService.getProfile("camera")).thenReturn(profile);
        when(backendFactory.getBackend(profile)).thenReturn(backend);
        when(backend.capture())
                .thenThrow(new IllegalStateException("synthetic failure"))
                .thenReturn(null);
        InMemoryCameraSessionLeaseManager manager = manager();
        DirectCameraControlExecutor executor = executor(
                profileService, backendFactory, manager);

        assertThatThrownBy(() -> executor.withBackend(
                "camera", CameraBackend::capture))
                .isInstanceOf(IllegalStateException.class);
        assertThat(manager.metrics().activeLeases()).isZero();

        executor.withBackend("camera", CameraBackend::capture);
        assertThat(manager.metrics().activeLeases()).isZero();
    }

    @Test
    void aliasProfilesCannotRunConcurrentDirectOperations() throws Exception {
        CameraProfile first = profile("first", "Camera.Example");
        CameraProfile alias = profile("alias", "http://camera.example:8080/ccapi");
        CameraProfileService profileService = mock(CameraProfileService.class);
        CameraBackendFactory backendFactory = mock(CameraBackendFactory.class);
        CameraBackend firstBackend = mock(CameraBackend.class);
        when(profileService.getProfile("first")).thenReturn(first);
        when(profileService.getProfile("alias")).thenReturn(alias);
        when(backendFactory.getBackend(first)).thenReturn(firstBackend);
        InMemoryCameraSessionLeaseManager manager = manager();
        DirectCameraControlExecutor executor = executor(
                profileService, backendFactory, manager);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();

        Thread worker = new Thread(() -> {
            try {
                executor.withBackend("first", backend -> {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test latch timed out");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("test interrupted");
                    }
                    return null;
                });
            } catch (Throwable failure) {
                workerFailure.set(failure);
            }
        }, "direct-camera-owner-test");
        worker.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> executor.withBackend(
                "alias", CameraBackend::capture))
                .isInstanceOf(CameraSessionConflictException.class);
        verify(backendFactory, never()).getBackend(alias);

        release.countDown();
        worker.join(5_000);
        assertThat(worker.isAlive()).isFalse();
        assertThat(workerFailure.get()).isNull();
        assertThat(manager.metrics().activeLeases()).isZero();
    }

    @Test
    void unmanagedBackendRunsWithoutNetworkLease() {
        CameraProfile profile = new CameraProfile(
                "mock", "Mock", "mock", null, 10, 30, 0.1);
        CameraProfileService profileService = mock(CameraProfileService.class);
        CameraBackendFactory backendFactory = mock(CameraBackendFactory.class);
        CameraBackend backend = mock(CameraBackend.class);
        when(profileService.getProfile("mock")).thenReturn(profile);
        when(backendFactory.getBackend(profile)).thenReturn(backend);
        InMemoryCameraSessionLeaseManager manager = manager();
        DirectCameraControlExecutor executor = executor(
                profileService, backendFactory, manager);

        executor.withBackend("mock", camera -> null);

        assertThat(manager.metrics().activeLeases()).isZero();
        verify(backendFactory).getBackend(profile);
    }

    private static DirectCameraControlExecutor executor(
            CameraProfileService profileService,
            CameraBackendFactory backendFactory,
            InMemoryCameraSessionLeaseManager manager) {
        return new DirectCameraControlExecutor(
                profileService,
                backendFactory,
                manager,
                new DefaultCameraLeaseIdentityProvider(),
                Duration.ofMinutes(5));
    }

    private static InMemoryCameraSessionLeaseManager manager() {
        return new InMemoryCameraSessionLeaseManager(Duration.ofHours(6), 1024);
    }

    private static CameraProfile profile(String id, String host) {
        return new CameraProfile(
                id, "Camera " + id, "canon-ccapi", host,
                10, 30, 0.1);
    }
}
