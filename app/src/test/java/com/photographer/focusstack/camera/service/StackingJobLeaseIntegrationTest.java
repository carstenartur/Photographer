package com.photographer.focusstack.camera.service;

import com.photographer.focusstack.analysis.service.StackMerger;
import com.photographer.focusstack.analysis.service.StackingAdvisor;
import com.photographer.focusstack.camera.backend.CameraBackendFactory;
import com.photographer.focusstack.camera.config.PlainWebSocketBridge;
import com.photographer.focusstack.camera.entity.StackingJobEntity;
import com.photographer.focusstack.camera.exception.CameraException;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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

    @BeforeEach
    void setUpProfileAndJob() {
        when(repository.findById("job-1"))
                .thenReturn(Optional.of(StackingJobEntity.fromRecord(CREATED_JOB)));
        when(profileService.getProfile("cam-1")).thenReturn(PROFILE);
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
        verify(repository).save(org.mockito.ArgumentMatchers.argThat(
                entity -> entity.getStatus() == JobStatus.FAILED));
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
        verify(repository, never()).save(org.mockito.ArgumentMatchers.argThat(
                entity -> entity.getStatus() == JobStatus.RUNNING));
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
        verify(repository).save(org.mockito.ArgumentMatchers.argThat(
                entity -> entity.getStatus() == JobStatus.FAILED));

        CameraLeaseKey key = CameraLeaseKey.fromCcapiProfile(PROFILE);
        try (CameraSessionLease retry = manager.acquire(new CameraLeaseRequest(
                key, CameraWorkflowType.DIRECT_CONTROL,
                "direct-2", "different-owner", Duration.ofMinutes(30)))) {
            assertThat(retry).isNotNull();
        }
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
