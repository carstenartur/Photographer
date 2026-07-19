package com.photographer.focusstack.camera.service;

import com.photographer.focusstack.analysis.service.StackMerger;
import com.photographer.focusstack.analysis.service.StackingAdvisor;
import com.photographer.focusstack.camera.backend.CameraBackendFactory;
import com.photographer.focusstack.camera.config.PlainWebSocketBridge;
import com.photographer.focusstack.camera.entity.StackingJobEntity;
import com.photographer.focusstack.camera.repository.StackingJobRepository;
import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.camera.NativeFocusBracketingRequest;
import com.photographer.focusstack.common.model.CameraProfile;
import com.photographer.focusstack.common.model.JobStatus;
import com.photographer.focusstack.common.model.QualityMode;
import com.photographer.focusstack.common.model.StackingCaptureStrategy;
import com.photographer.focusstack.common.model.StackingJob;
import com.photographer.focusstack.common.model.StackingParameters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NativeStrategyFailurePersistenceTest {

    @TempDir
    Path tempDir;

    @Test
    void requiredNativeCapabilityFailureTransitionsJobToFailed() {
        CameraBackendFactory backendFactory = mock(CameraBackendFactory.class);
        CameraProfileService profileService = mock(CameraProfileService.class);
        StackingJobRepository repository = mock(StackingJobRepository.class);
        CameraBackend manualOnlyBackend = mock(CameraBackend.class);
        CameraProfile profile = new CameraProfile(
                "cam-1", "Manual only", "mock", "localhost", 1, 10, 0.1);
        when(profileService.getProfile("cam-1")).thenReturn(profile);
        when(backendFactory.getBackend(profile)).thenReturn(manualOnlyBackend);
        StackingJobService service = new StackingJobService(
                backendFactory,
                profileService,
                repository,
                mock(SimpMessagingTemplate.class),
                mock(StackingAdvisor.class),
                mock(StackMerger.class),
                new FrameDataStore(tempDir.toString(), 1_073_741_824L),
                mock(PlainWebSocketBridge.class),
                new SyncTaskExecutor(),
                "adaptive");
        StackingParameters parameters = new StackingParameters(
                0, 1, 3, 0.01, false, QualityMode.BALANCED,
                StackingCaptureStrategy.NATIVE,
                new NativeFocusBracketingRequest(3, 1, false, false, false));
        StackingJob job = new StackingJob(
                "job-native-unavailable", "cam-1", JobStatus.RUNNING,
                Instant.now(), null, parameters, List.of(), null);

        service.executeStackingAsync(job);

        verify(repository, atLeastOnce()).save(argThat(entity ->
                entity.getStatus() == JobStatus.FAILED
                        && entity.getStopReason() != null
                        && entity.getStopReason().contains("Native focus bracketing is unavailable")));
    }
}
