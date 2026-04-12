package com.photographer.focusstack.camera.service;

import com.photographer.focusstack.analysis.service.StackMerger;
import com.photographer.focusstack.analysis.service.StackingAdvisor;
import com.photographer.focusstack.camera.backend.CameraBackendFactory;
import com.photographer.focusstack.camera.entity.StackingJobEntity;
import com.photographer.focusstack.camera.repository.StackingJobRepository;
import com.photographer.focusstack.common.analysis.AnalysisResult;
import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.camera.CaptureResult;
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
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StackingJobServiceTest {

    @Mock private CameraBackend cameraBackend;
    @Mock private CameraBackendFactory backendFactory;
    @Mock private CameraProfileService profileService;
    @Mock private StackingJobRepository repository;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private StackingAdvisor stackingAdvisor;
    @Mock private StackMerger stackMerger;

    @TempDir Path tempDir;

    private StackingJobService service;

    private static final CameraProfile PROFILE =
            new CameraProfile("cam-1", "Test Cam", "mock", "localhost", 10, 30, 0.1);

    private static final StackingParameters PARAMS =
            new StackingParameters(0, 5, 3, 0.01, false, QualityMode.BALANCED);

    @BeforeEach
    void setUp() {
        // Wire the factory to return our mocked CameraBackend for "cam-1"
        lenient().when(profileService.getProfile("cam-1")).thenReturn(PROFILE);
        lenient().when(backendFactory.getBackend(PROFILE)).thenReturn(cameraBackend);

        // SyncTaskExecutor runs tasks on the calling thread — keeps tests deterministic
        service = new StackingJobService(
                backendFactory, profileService, repository, messagingTemplate,
                stackingAdvisor, stackMerger, new FrameDataStore(tempDir.toString()),
                new SyncTaskExecutor(), "adaptive");
    }

    @Test
    void createJobShouldPersistWithCreatedStatus() {
        service.createJob("cam-1", PARAMS);
        verify(repository).save(argThat(e -> e.getStatus() == JobStatus.CREATED));
    }

    @Test
    void captureLoopShouldCompleteAllFramesWhenAdvisorRecommendsContinue() throws InterruptedException {
        AnalysisResult continueResult = new AnalysisResult(
                "r1", 0.5, 0.4, false, 0.0, 0.3, true);
        when(stackingAdvisor.analyzeFrame(any(), any(), any(), any()))
                .thenReturn(continueResult);

        when(cameraBackend.capture()).thenReturn(
                new CaptureResult("img-1", "IMG_0.jpg", "/mock/img1.jpg",
                        Instant.now(), 1, 100L, new byte[]{1, 2, 3}),
                new CaptureResult("img-2", "IMG_1.jpg", "/mock/img2.jpg",
                        Instant.now(), 2, 100L, new byte[]{4, 5, 6}),
                new CaptureResult("img-3", "IMG_2.jpg", "/mock/img3.jpg",
                        Instant.now(), 3, 100L, new byte[]{7, 8, 9})
        );

        StackingJob job = new StackingJob("job-1", "cam-1", JobStatus.RUNNING,
                Instant.now(), null, PARAMS, Collections.emptyList());

        // Run synchronously for test
        service.executeStackingAsync(job);

        // Should have captured exactly 3 frames and saved COMPLETED status
        verify(cameraBackend, times(3)).capture();
        verify(repository, atLeastOnce()).save(argThat(e -> e.getStatus() == JobStatus.COMPLETED));
    }

    @Test
    void captureLoopShouldStopEarlyWhenAnalysisRecommendsStop() throws InterruptedException {
        // Second frame analysis recommends stopping
        AnalysisResult continueResult = new AnalysisResult(
                "r1", 0.5, 0.4, false, 0.0, 0.3, true);
        AnalysisResult stopResult = new AnalysisResult(
                "r2", 0.4, 0.3, true, 0.8, 0.0, false); // motion detected

        when(stackingAdvisor.analyzeFrame(any(), any(), any(), any()))
                .thenReturn(continueResult)
                .thenReturn(stopResult);

        when(cameraBackend.capture()).thenReturn(
                new CaptureResult("img-1", "IMG_0.jpg", "/mock/img1.jpg",
                        Instant.now(), 1, 100L, new byte[]{1, 2, 3}),
                new CaptureResult("img-2", "IMG_1.jpg", "/mock/img2.jpg",
                        Instant.now(), 2, 100L, new byte[]{4, 5, 6}),
                new CaptureResult("img-3", "IMG_2.jpg", "/mock/img3.jpg",
                        Instant.now(), 3, 100L, new byte[]{7, 8, 9})
        );

        StackingJob job = new StackingJob("job-1", "cam-1", JobStatus.RUNNING,
                Instant.now(), null, PARAMS, Collections.emptyList());

        service.executeStackingAsync(job);

        // Should stop after frame 2 (analysis said stop after second frame)
        verify(cameraBackend, times(2)).capture();
        verify(repository, atLeastOnce()).save(argThat(e -> e.getStatus() == JobStatus.COMPLETED));
    }

    @Test
    void captureLoopShouldSkipAnalysisWhenImageDataIsNull() throws InterruptedException {
        // Frames with null imageData (e.g. CCAPI backend)
        when(cameraBackend.capture()).thenReturn(
                new CaptureResult("img-1", "IMG_0.jpg", "/ccapi/img1.jpg",
                        Instant.now(), 1, 0L, null),
                new CaptureResult("img-2", "IMG_1.jpg", "/ccapi/img2.jpg",
                        Instant.now(), 2, 0L, null),
                new CaptureResult("img-3", "IMG_2.jpg", "/ccapi/img3.jpg",
                        Instant.now(), 3, 0L, null)
        );

        StackingJob job = new StackingJob("job-1", "cam-1", JobStatus.RUNNING,
                Instant.now(), null, PARAMS, Collections.emptyList());

        service.executeStackingAsync(job);

        // Advisor should never be called when imageData is null
        verify(stackingAdvisor, never()).analyzeFrame(any(), any(), any(), any());
        verify(repository, atLeastOnce()).save(argThat(e -> e.getStatus() == JobStatus.COMPLETED));
    }

    @Test
    void cancelledJobShouldNotProceed() {
        StackingJob runningJob = new StackingJob("job-1", "cam-1", JobStatus.RUNNING,
                Instant.now(), null, PARAMS, Collections.emptyList());
        when(repository.findById("job-1"))
                .thenReturn(Optional.of(StackingJobEntity.fromRecord(runningJob)));

        service.cancelJob("job-1");
        // Repository must have saved a CANCELLED entity
        verify(repository, atLeastOnce()).save(argThat(e ->
                e.getStatus() == JobStatus.CANCELLED));
    }
}