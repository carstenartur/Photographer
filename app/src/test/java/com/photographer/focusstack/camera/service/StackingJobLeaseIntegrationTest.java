package com.photographer.focusstack.camera.service;

import com.photographer.focusstack.analysis.service.StackMerger;
import com.photographer.focusstack.analysis.service.StackingAdvisor;
import com.photographer.focusstack.camera.backend.CameraBackendFactory;
import com.photographer.focusstack.camera.config.PlainWebSocketBridge;
import com.photographer.focusstack.camera.entity.StackingJobEntity;
import com.photographer.focusstack.camera.exception.CameraException;
import com.photographer.focusstack.camera.exception.CameraServiceUnavailableException;
import com.photographer.focusstack.camera.repository.StackingJobRepository;
import com.photographer.focusstack.camera.session.CameraLeaseKey;
import com.photographer.focusstack.camera.session.CameraLeaseRequest;
import com.photographer.focusstack.camera.session.CameraSessionConflictException;
import com.photographer.focusstack.camera.session.CameraSessionLease;
import com.photographer.focusstack.camera.session.CameraWorkflowType;
import com.photographer.focusstack.camera.session.DefaultCameraLeaseIdentityProvider;
import com.photographer.focusstack.camera.session.InMemoryCameraSessionLeaseManager;
import com.photographer.focusstack.common.model.CameraProfile;
import com.photographer.focusstack.common.model.JobStatus;
import com.photographer.focusstack.common.model.QualityMode;
import com.photographer.focusstack.common.model.StackingJob;
import com.photographer.focusstack.common.model.StackingParameters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StackingJobLeaseIntegrationTest {

    @Mock private CameraBackendFactory backendFactory;
    @Mock private CameraProfileService profileService;
    @Mock private StackingJobRepository repository;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private StackingAdvisor stackingAdvisor;
    @Mock private StackMerger stackMerger;
    @Mock private PlainWebSocketBridge plainWebSocketBridge;

    @TempDir Path tempDir;

    private static final CameraProfile PROFILE = new CameraProfile(
            "cam-1", "Lease Camera", "canon-ccapi", "camera.example",
            10, 30, 0.1);
    private static final StackingParameters PARAMETERS = new StackingParameters(
            0, 5, 1, 0.01, false, QualityMode.BALANCED);
    private static final StackingJob CREATED_JOB = new StackingJob(
            "job-1", "cam-1", JobStatus.CREATED,
            null, null, PARAMETERS, List.of(), null, null);

    private AtomicReference<StackingJobEntity> persistedJob;

    @BeforeEach
    void setUpProfileAndJob() {
        persistedJob = new AtomicReference<>(StackingJobEntity.fromRecord(CREATED_JOB));
        lenient().when(repository.findById("job-1"))
                .thenAnswer(invocation -> Optional.ofNullable(persistedJob.get()));
        lenient().when(repository.save(any(StackingJobEntity.class)))
                .thenAnswer(invocation -> {
                    StackingJobEntity entity = invocation.getArgument(0);
                    persistedJob.set(entity);
                    return entity;
                });
        lenient().when(profileService.getProfile("cam-1")).thenReturn(PROFILE);
    }

    @Test
    void acquiresBeforeBackendResolutionAndReleasesAfterAsyncFailure() {
        InMemoryCameraSessionLeaseManager manager = manager();
        AtomicReference<Runnable> scheduled = new AtomicReference<>();
        TaskExecutor capturingExecutor = scheduled::set;
        StackingJobService service = service(manager, capturingExecutor);
        when(backendFactory.getBackend(PROFILE))
                .thenThrow(new CameraException("synthetic backend failure"));

        StackingJob running = service.startJob("job-1");

        assertThat(running.status()).isEqualTo(JobStatus.RUNNING);
        assertThat(manager.metrics().activeLeases()).isEqualTo(1);
        assertThat(scheduled.get()).isNotNull();
        verify(backendFactory, never()).getBackend(any());

        scheduled.get().run();

        verify(backendFactory).getBackend(PROFILE);
        assertThat(manager.metrics().activeLeases()).isZero();
        assertThat(persistedJob.get().getStatus()).isEqualTo(JobStatus.FAILED);
    }

    @Test
    void conflictingOwnerFailsBeforeRunningStateOrBackendContact() {
        InMemoryCameraSessionLeaseManager manager = manager();
        CameraLeaseKey key = CameraLeaseKey.fromCcapiProfile(PROFILE);
        StackingJobService service = service(manager, Runnable::run);

        try (CameraSessionLease ignored = manager.acquire(new CameraLeaseRequest(
                key, CameraWorkflowType.DIRECT_CONTROL,
                "direct-1", "direct-owner", Duration.ofMinutes(30)))) {
            assertThatThrownBy(() -> service.startJob("job-1"))
                    .isInstanceOf(CameraSessionConflictException.class);
        }

        verify(backendFactory, never()).getBackend(any());
        assertThat(persistedJob.get().getStatus()).isEqualTo(JobStatus.CREATED);
    }

    @Test
    void executorRejectionReleasesLeaseAndPersistsFailure() {
        InMemoryCameraSessionLeaseManager manager = manager();
        TaskExecutor rejectingExecutor = task -> {
            throw new IllegalStateException("executor rejected task");
        };
        StackingJobService service = service(manager, rejectingExecutor);

        assertThatThrownBy(() -> service.startJob("job-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("executor rejected task");

        assertThat(manager.metrics().activeLeases()).isZero();
        verify(backendFactory, never()).getBackend(any());
        assertThat(persistedJob.get().getStatus()).isEqualTo(JobStatus.FAILED);

        CameraLeaseKey key = CameraLeaseKey.fromCcapiProfile(PROFILE);
        try (CameraSessionLease retry = manager.acquire(new CameraLeaseRequest(
                key, CameraWorkflowType.DIRECT_CONTROL,
                "direct-2", "different-owner", Duration.ofMinutes(30)))) {
            assertThat(retry).isNotNull();
        }
    }

    @Test
    void cancelBeforeQueuedTaskStartsReleasesLeaseWithoutCameraContact() {
        InMemoryCameraSessionLeaseManager manager = manager();
        AtomicReference<Runnable> scheduled = new AtomicReference<>();
        StackingJobService service = service(manager, scheduled::set);

        service.startJob("job-1");
        assertThat(manager.metrics().activeLeases()).isEqualTo(1);

        service.cancelJob("job-1");

        assertThat(manager.metrics().activeLeases()).isZero();
        assertThat(persistedJob.get().getStatus()).isEqualTo(JobStatus.CANCELLED);
        scheduled.get().run();

        verify(backendFactory, never()).getBackend(any());
        assertThat(persistedJob.get().getStatus()).isEqualTo(JobStatus.CANCELLED);
    }

    @Test
    void pauseResumeSupersedesOldQueuedTask() {
        InMemoryCameraSessionLeaseManager manager = manager();
        List<Runnable> scheduled = new CopyOnWriteArrayList<>();
        StackingJobService service = service(manager, scheduled::add);

        service.startJob("job-1");
        service.pauseJob("job-1");
        assertThat(manager.metrics().activeLeases()).isZero();
        assertThat(persistedJob.get().getStatus()).isEqualTo(JobStatus.PAUSED);

        service.startJob("job-1");
        assertThat(scheduled).hasSize(2);
        assertThat(manager.metrics().activeLeases()).isEqualTo(1);

        scheduled.get(0).run();
        verify(backendFactory, never()).getBackend(any());

        when(backendFactory.getBackend(PROFILE))
                .thenThrow(new CameraException("second attempt completed test path"));
        scheduled.get(1).run();

        verify(backendFactory, times(1)).getBackend(PROFILE);
        assertThat(manager.metrics().activeLeases()).isZero();
        assertThat(persistedJob.get().getStatus()).isEqualTo(JobStatus.FAILED);
    }

    @Test
    void secondStartWhileFirstAttemptIsQueuedIsRejected() {
        InMemoryCameraSessionLeaseManager manager = manager();
        List<Runnable> scheduled = new CopyOnWriteArrayList<>();
        StackingJobService service = service(manager, scheduled::add);

        service.startJob("job-1");

        assertThatThrownBy(() -> service.startJob("job-1"))
                .isInstanceOf(CameraException.class)
                .hasMessageContaining("state: RUNNING");
        assertThat(scheduled).hasSize(1);

        service.cancelJob("job-1");
        assertThat(manager.metrics().activeLeases()).isZero();
    }

    @Test
    void runningAttemptRetainsLeaseAndBlocksResumeUntilItStops() throws Exception {
        InMemoryCameraSessionLeaseManager manager = manager();
        AtomicReference<Runnable> scheduled = new AtomicReference<>();
        StackingJobService service = service(manager, scheduled::set);
        CountDownLatch backendEntered = new CountDownLatch(1);
        CountDownLatch releaseBackend = new CountDownLatch(1);
        when(backendFactory.getBackend(PROFILE)).thenAnswer(invocation -> {
            backendEntered.countDown();
            if (!releaseBackend.await(5, TimeUnit.SECONDS)) {
                throw new CameraException("backend test latch timed out");
            }
            throw new CameraException("synthetic stop completion");
        });

        service.startJob("job-1");
        Thread worker = new Thread(scheduled.get(), "stacking-lease-test");
        worker.start();
        assertThat(backendEntered.await(5, TimeUnit.SECONDS)).isTrue();

        service.pauseJob("job-1");

        assertThat(manager.metrics().activeLeases()).isEqualTo(1);
        assertThatThrownBy(() -> service.startJob("job-1"))
                .isInstanceOf(CameraException.class)
                .hasMessageContaining("still stopping");
        assertThat(persistedJob.get().getStatus()).isEqualTo(JobStatus.PAUSED);

        releaseBackend.countDown();
        worker.join(5_000);
        assertThat(worker.isAlive()).isFalse();
        assertThat(manager.metrics().activeLeases()).isZero();
        assertThat(persistedJob.get().getStatus()).isEqualTo(JobStatus.PAUSED);
    }

    @Test
    void shutdownCancelsQueuedAttemptPersistentlyWithoutCameraContact() {
        InMemoryCameraSessionLeaseManager manager = manager();
        AtomicReference<Runnable> scheduled = new AtomicReference<>();
        StackingJobService service = service(manager, scheduled::set);

        service.startJob("job-1");
        service.requestCameraShutdown();

        assertThat(manager.metrics().activeLeases()).isZero();
        assertThat(service.activeCameraExecutions()).isZero();
        assertThat(persistedJob.get().getStatus()).isEqualTo(JobStatus.CANCELLED);
        assertThat(persistedJob.get().getStopReason())
                .isEqualTo("Cancelled during application shutdown");
        scheduled.get().run();
        verify(backendFactory, never()).getBackend(any());
        assertThat(persistedJob.get().getStatus()).isEqualTo(JobStatus.CANCELLED);
    }

    @Test
    void shutdownRejectsLaterStartBeforeLeaseOrBackendContact() {
        InMemoryCameraSessionLeaseManager manager = manager();
        StackingJobService service = service(manager, Runnable::run);
        service.requestCameraShutdown();

        assertThatThrownBy(() -> service.startJob("job-1"))
                .isInstanceOf(CameraServiceUnavailableException.class)
                .hasMessage("Camera services are shutting down");

        assertThat(manager.metrics().activeLeases()).isZero();
        assertThat(persistedJob.get().getStatus()).isEqualTo(JobStatus.CREATED);
        verify(backendFactory, never()).getBackend(any());
    }

    private StackingJobService service(
            InMemoryCameraSessionLeaseManager manager,
            TaskExecutor taskExecutor) {
        return new StackingJobService(
                backendFactory,
                profileService,
                repository,
                messagingTemplate,
                stackingAdvisor,
                stackMerger,
                new FrameDataStore(tempDir.toString(), 1_073_741_824L),
                plainWebSocketBridge,
                manager,
                new DefaultCameraLeaseIdentityProvider(),
                taskExecutor,
                "adaptive",
                Duration.ofHours(2));
    }

    private static InMemoryCameraSessionLeaseManager manager() {
        return new InMemoryCameraSessionLeaseManager(
                Duration.ofHours(6), 1024);
    }
}
