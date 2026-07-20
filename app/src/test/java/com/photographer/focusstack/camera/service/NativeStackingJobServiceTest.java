package com.photographer.focusstack.camera.service;

import com.photographer.focusstack.analysis.service.StackMerger;
import com.photographer.focusstack.analysis.service.StackingAdvisor;
import com.photographer.focusstack.camera.backend.CameraBackendFactory;
import com.photographer.focusstack.camera.config.PlainWebSocketBridge;
import com.photographer.focusstack.camera.entity.StackingJobEntity;
import com.photographer.focusstack.camera.repository.StackingJobRepository;
import com.photographer.focusstack.common.analysis.AnalysisResult;
import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.camera.CameraCapabilityProvider;
import com.photographer.focusstack.common.camera.CameraCapabilitySnapshot;
import com.photographer.focusstack.common.camera.CameraInfo;
import com.photographer.focusstack.common.camera.CameraMediaProvider;
import com.photographer.focusstack.common.camera.CameraMediaReference;
import com.photographer.focusstack.common.camera.CameraState;
import com.photographer.focusstack.common.camera.CaptureResult;
import com.photographer.focusstack.common.camera.FocusCommand;
import com.photographer.focusstack.common.camera.IntegerSettingRange;
import com.photographer.focusstack.common.camera.LiveViewFrame;
import com.photographer.focusstack.common.camera.NativeFocusBracketingCapabilities;
import com.photographer.focusstack.common.camera.NativeFocusBracketingExecutionProvider;
import com.photographer.focusstack.common.camera.NativeFocusBracketingRequest;
import com.photographer.focusstack.common.camera.NativeFocusBracketingSession;
import com.photographer.focusstack.common.camera.Operation;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NativeStackingJobServiceTest {

    private static final CameraProfile PROFILE =
            new CameraProfile("cam-1", "Test Cam", "canon-ccapi",
                    "http://camera.example:8080", 10, 30, 0.1);

    @TempDir
    Path tempDir;

    @Test
    void nativeStrategyPersistsFramesAndEffectiveExecutionMetadata() {
        NativeBackend backend = new NativeBackend();
        Fixture fixture = fixture(backend);
        NativeFocusBracketingRequest request =
                new NativeFocusBracketingRequest(3, 2, true, false, false);
        StackingParameters parameters = new StackingParameters(
                0, 5, 3, 0.01, false, QualityMode.BALANCED,
                StackingCaptureStrategy.NATIVE, request);
        StackingJob job = new StackingJob(
                "job-native", "cam-1", JobStatus.RUNNING,
                Instant.now(), null, parameters, List.of(), null);

        fixture.service.executeStackingAsync(job);

        verify(fixture.repository, atLeastOnce()).save(any(StackingJobEntity.class));
        StackingJob completed = fixture.lastSaved(JobStatus.COMPLETED);
        assertEquals(3, completed.frames().size());
        assertNotNull(completed.executionMetadata());
        assertEquals(StackingCaptureStrategy.NATIVE,
                completed.executionMetadata().requestedStrategy());
        assertEquals(StackingCaptureStrategy.NATIVE,
                completed.executionMetadata().selectedStrategy());
        assertEquals(3, completed.executionMetadata().observedMediaCount());
        assertEquals(3, completed.executionMetadata().ingestedFrameCount());
        assertTrue(completed.executionMetadata().restorationComplete());
        assertEquals(1, backend.prepareCount.get());
        assertEquals(1, backend.triggerCount.get());
        assertEquals(1, backend.closeCount.get());
    }

    @Test
    void autoStrategyFallsBackToManualForBackendWithoutNativeContract() {
        CameraBackend manualBackend = mock(CameraBackend.class);
        when(manualBackend.capture()).thenReturn(
                capture("manual-1", 1), capture("manual-2", 2));
        StackingAdvisor advisor = mock(StackingAdvisor.class);
        when(advisor.analyzeFrame(any(), any(), any(), any()))
                .thenReturn(new AnalysisResult(
                        "r", 0.5, 0.4, false, 0.0, 0.3, true));
        Fixture fixture = fixture(manualBackend, advisor);
        StackingParameters parameters = new StackingParameters(
                0, 1, 2, 0.01, false, QualityMode.BALANCED,
                StackingCaptureStrategy.AUTO,
                new NativeFocusBracketingRequest(2, 1, false, false, false));
        StackingJob job = new StackingJob(
                "job-auto", "cam-1", JobStatus.RUNNING,
                Instant.now(), null, parameters, List.of(), null);

        fixture.service.executeStackingAsync(job);

        StackingJob completed = fixture.lastSaved(JobStatus.COMPLETED);
        assertEquals(StackingCaptureStrategy.AUTO,
                completed.executionMetadata().requestedStrategy());
        assertEquals(StackingCaptureStrategy.MANUAL,
                completed.executionMetadata().selectedStrategy());
        assertTrue(completed.executionMetadata().detail().contains("manual fallback"));
        verify(manualBackend, never()).getInfo();
    }

    private Fixture fixture(CameraBackend backend) {
        return fixture(backend, mock(StackingAdvisor.class));
    }

    private Fixture fixture(CameraBackend backend, StackingAdvisor advisor) {
        CameraBackendFactory backendFactory = mock(CameraBackendFactory.class);
        CameraProfileService profileService = mock(CameraProfileService.class);
        StackingJobRepository repository = mock(StackingJobRepository.class);
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        StackMerger stackMerger = mock(StackMerger.class);
        PlainWebSocketBridge bridge = mock(PlainWebSocketBridge.class);
        when(profileService.getProfile("cam-1")).thenReturn(PROFILE);
        when(backendFactory.getBackend(PROFILE)).thenReturn(backend);
        FrameDataStore store = new FrameDataStore(tempDir.toString(), 1_073_741_824L);
        StackingJobService service = new StackingJobService(
                backendFactory, profileService, repository, messagingTemplate,
                advisor, stackMerger, store, bridge,
                new SyncTaskExecutor(), "adaptive");
        return new Fixture(service, repository);
    }

    private static CaptureResult capture(String id, int position) {
        return new CaptureResult(
                id, id + ".jpg", "/mock/" + id,
                Instant.now(), position, 3L, new byte[]{1, 2, 3});
    }

    private record Fixture(
            StackingJobService service,
            StackingJobRepository repository) {

        private StackingJob lastSaved(JobStatus status) {
            org.mockito.ArgumentCaptor<StackingJobEntity> captor =
                    org.mockito.ArgumentCaptor.forClass(StackingJobEntity.class);
            verify(repository, atLeastOnce()).save(captor.capture());
            return captor.getAllValues().stream()
                    .map(StackingJobEntity::toRecord)
                    .filter(job -> job.status() == status)
                    .reduce((first, second) -> second)
                    .orElseThrow();
        }
    }

    private static final class NativeBackend implements
            CameraBackend,
            NativeFocusBracketingExecutionProvider,
            CameraMediaProvider,
            CameraCapabilityProvider {

        private final AtomicInteger prepareCount = new AtomicInteger();
        private final AtomicInteger triggerCount = new AtomicInteger();
        private final AtomicInteger closeCount = new AtomicInteger();
        private boolean triggered;

        @Override
        public NativeFocusBracketingCapabilities getNativeFocusBracketingCapabilities() {
            return new NativeFocusBracketingCapabilities(
                    true,
                    Optional.of(new IntegerSettingRange(2, 10, 1)),
                    Optional.of(new IntegerSettingRange(1, 10, 1)),
                    true, false, false);
        }

        @Override
        public NativeFocusBracketingSession prepareNativeFocusBracketing(
                NativeFocusBracketingRequest request) {
            prepareCount.incrementAndGet();
            return new NativeFocusBracketingSession() {
                @Override
                public NativeFocusBracketingRequest request() {
                    return request;
                }

                @Override
                public void close() {
                    closeCount.incrementAndGet();
                }
            };
        }

        @Override
        public void triggerNativeFocusBracketing(NativeFocusBracketingSession session) {
            triggerCount.incrementAndGet();
            triggered = true;
        }

        @Override
        public MediaPage listMedia(MediaQuery query) {
            if (!triggered) {
                return new MediaPage(List.of(old()), 1, false);
            }
            List<CameraMediaReference> items = new ArrayList<>();
            items.add(old());
            items.add(reference(1));
            items.add(reference(2));
            items.add(reference(3));
            return new MediaPage(items, items.size(), false);
        }

        @Override
        public MediaChunk downloadMedia(
                CameraMediaReference reference,
                long offset,
                int maxBytes) {
            byte[] data = reference.fileName().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return new MediaChunk(reference, offset, data, (long) data.length, true);
        }

        @Override
        public Optional<CameraMediaReference> awaitAddedMedia(
                EventMode mode,
                Duration timeout) {
            return Optional.of(reference(1));
        }

        @Override
        public CameraCapabilitySnapshot getCapabilitySnapshot() {
            CameraCapabilitySnapshot.Capability monitoring =
                    new CameraCapabilitySnapshot.Capability(
                            "event-monitoring", true, true,
                            CameraCapabilitySnapshot.Availability.AVAILABLE,
                            Set.of("GET", "DELETE"), Set.of("GET", "DELETE"), true, null);
            return new CameraCapabilitySnapshot(
                    "Synthetic", "1.0", true,
                    List.of(monitoring), List.of(), null);
        }

        private static CameraMediaReference old() {
            return new CameraMediaReference(
                    "card1",
                    "/ccapi/ver140/contents/card1/DCIM/100CANON/OLD.CR3",
                    "OLD.CR3", "CR3", "ver140");
        }

        private static CameraMediaReference reference(int index) {
            String fileName = "NATIVE_" + index + ".CR3";
            return new CameraMediaReference(
                    "card1",
                    "/ccapi/ver140/contents/card1/DCIM/100CANON/" + fileName,
                    fileName, "CR3", "ver140");
        }

        @Override
        public CameraInfo getInfo() {
            throw new UnsupportedOperationException();
        }

        @Override
        public LiveViewFrame getLiveView() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CaptureResult capture() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setFocus(FocusCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CameraState getState() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean supports(Operation operation) {
            return false;
        }
    }
}
