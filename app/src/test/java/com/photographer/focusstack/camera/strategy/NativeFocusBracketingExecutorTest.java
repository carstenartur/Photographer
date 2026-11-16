package com.photographer.focusstack.camera.strategy;

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
import com.photographer.focusstack.common.camera.NativeFocusBracketingExecutionResult;
import com.photographer.focusstack.common.camera.NativeFocusBracketingRequest;
import com.photographer.focusstack.common.camera.NativeFocusBracketingSession;
import com.photographer.focusstack.common.camera.Operation;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.photographer.focusstack.common.camera.NativeFocusBracketingExecutionResult.CompletionState.CANCELLED;
import static com.photographer.focusstack.common.camera.NativeFocusBracketingExecutionResult.CompletionState.COMPLETE;
import static com.photographer.focusstack.common.camera.NativeFocusBracketingExecutionResult.CompletionState.FAILED;
import static com.photographer.focusstack.common.camera.NativeFocusBracketingExecutionResult.CompletionState.PARTIAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeFocusBracketingExecutorTest {

    private static final NativeFocusBracketingRequest REQUEST =
            new NativeFocusBracketingRequest(3, 2, false, false, false);

    @Test
    void triggersExactlyOnceAndIngestsAllFilesFromOneGroupedCameraUpdate() {
        FakeBackend backend = new FakeBackend(3, false);
        AtomicInteger ingested = new AtomicInteger();

        NativeFocusBracketingExecutionResult result = new NativeFocusBracketingExecutor().execute(
                backend,
                REQUEST,
                Duration.ofSeconds(1),
                () -> false,
                (media, reference) -> "frame-" + ingested.incrementAndGet());

        assertEquals(COMPLETE, result.completionState());
        assertEquals(3, result.observedMedia().size());
        assertEquals(List.of("frame-1", "frame-2", "frame-3"), result.ingestedFrameIds());
        assertTrue(result.restorationComplete());
        assertEquals(1, backend.prepareCount.get());
        assertEquals(1, backend.triggerCount.get());
        assertEquals(1, backend.closeCount.get());
        assertEquals(1, backend.eventCount.get(),
                "one event must be sufficient to discover several generated files");
    }

    @Test
    void pairsRawAndJpegAsOneShotEvenWhenJpegArrivesFirst() {
        FakeBackend backend = new FakeBackend(3, false, true);
        backend.delayedOldCompanion = true;
        List<String> transfers = new ArrayList<>();

        NativeFocusBracketingExecutionResult result = new NativeFocusBracketingExecutor().execute(
                backend,
                REQUEST,
                Duration.ofSeconds(2),
                () -> false,
                (media, reference) -> {
                    transfers.add(reference.fileName());
                    return reference.fileName();
                });

        assertEquals(COMPLETE, result.completionState());
        assertEquals(List.of("NEW_1.CR3", "NEW_2.CR3", "NEW_3.CR3"), transfers);
        assertEquals(3, result.observedMedia().size());
        assertEquals(transfers, result.ingestedFrameIds());
    }

    @Test
    void fallsBackToThePairedJpegIfTheRawTransferFails() {
        FakeBackend backend = new FakeBackend(3, false, true);

        NativeFocusBracketingExecutionResult result = new NativeFocusBracketingExecutor().execute(
                backend,
                REQUEST,
                Duration.ofSeconds(2),
                () -> false,
                (media, reference) -> {
                    if (reference.fileName().equals("NEW_1.CR3")) {
                        throw new IllegalStateException("CR3 not available");
                    }
                    return reference.fileName();
                });

        assertEquals(COMPLETE, result.completionState());
        assertEquals(List.of("NEW_1.JPG", "NEW_2.CR3", "NEW_3.CR3"), result.ingestedFrameIds());
    }

    @Test
    void preservesPartialSequenceWhenFewerFilesArriveBeforeTimeout() {
        FakeBackend backend = new FakeBackend(1, false);

        NativeFocusBracketingExecutionResult result = new NativeFocusBracketingExecutor().execute(
                backend,
                REQUEST,
                Duration.ofMillis(30),
                () -> false,
                (media, reference) -> "partial-" + reference.fileName());

        assertEquals(PARTIAL, result.completionState());
        assertEquals(1, result.observedMedia().size());
        assertEquals(1, result.ingestedFrameIds().size());
        assertTrue(result.partialFailureReason().contains("Timed out"));
        assertTrue(result.restorationComplete());
        assertEquals(1, backend.triggerCount.get());
        assertEquals(1, backend.closeCount.get());
    }

    @Test
    void cancellationBeforeTriggerStillRestoresPreparedSettings() {
        FakeBackend backend = new FakeBackend(3, false);

        NativeFocusBracketingExecutionResult result = new NativeFocusBracketingExecutor().execute(
                backend,
                REQUEST,
                Duration.ofSeconds(1),
                () -> true,
                (media, reference) -> "unused");

        assertEquals(CANCELLED, result.completionState());
        assertTrue(result.observedMedia().isEmpty());
        assertEquals(0, backend.triggerCount.get());
        assertEquals(1, backend.closeCount.get());
        assertTrue(result.restorationComplete());
    }

    @Test
    void incompleteRestorationIsAnExplicitTerminalFailure() {
        FakeBackend backend = new FakeBackend(3, true);

        NativeFocusBracketingExecutionResult result = new NativeFocusBracketingExecutor().execute(
                backend,
                REQUEST,
                Duration.ofSeconds(1),
                () -> false,
                (media, reference) -> "frame-" + reference.fileName());

        assertEquals(FAILED, result.completionState());
        assertFalse(result.restorationComplete());
        assertTrue(result.partialFailureReason().contains("restoration failed"));
        assertEquals(3, result.ingestedFrameIds().size(),
                "successfully ingested frames must survive restoration failure");
    }

    private static final class FakeBackend implements
            CameraBackend,
            NativeFocusBracketingExecutionProvider,
            CameraMediaProvider,
            CameraCapabilityProvider {

        private final int generatedFrames;
        private final boolean failClose;
        private final boolean paired;
        private final AtomicInteger prepareCount = new AtomicInteger();
        private final AtomicInteger triggerCount = new AtomicInteger();
        private final AtomicInteger closeCount = new AtomicInteger();
        private final AtomicInteger eventCount = new AtomicInteger();
        private boolean delayedOldCompanion;
        private boolean triggered;

        private FakeBackend(int generatedFrames, boolean failClose) {
            this(generatedFrames, failClose, false);
        }

        private FakeBackend(int generatedFrames, boolean failClose, boolean paired) {
            this.generatedFrames = generatedFrames;
            this.failClose = failClose;
            this.paired = paired;
        }

        @Override
        public NativeFocusBracketingCapabilities getNativeFocusBracketingCapabilities() {
            return new NativeFocusBracketingCapabilities(
                    true,
                    Optional.of(new IntegerSettingRange(2, 10, 1)),
                    Optional.of(new IntegerSettingRange(1, 10, 1)),
                    true,
                    false,
                    false);
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
                    if (failClose) {
                        throw new IllegalStateException("synthetic restoration failure");
                    }
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
                return new MediaPage(List.of(oldReference()), 1, false);
            }
            List<CameraMediaReference> media = new ArrayList<>();
            media.add(oldReference());
            if (delayedOldCompanion) {
                media.add(new CameraMediaReference("card1",
                        "/ccapi/ver140/contents/card1/DCIM/100CANON/OLD.JPG",
                        "OLD.JPG", "JPEG", "ver140"));
            }
            for (int index = 1; index <= generatedFrames; index++) {
                if (paired) {
                    media.add(reference(index, "JPG"));
                }
                if (!paired || eventCount.get() > 1) {
                    media.add(reference(index, "CR3"));
                }
            }
            return new MediaPage(media, media.size(), false);
        }

        @Override
        public MediaChunk downloadMedia(
                CameraMediaReference reference,
                long offset,
                int maxBytes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CameraMediaReference> awaitAddedMedia(
                EventMode mode,
                Duration timeout) {
            eventCount.incrementAndGet();
            return generatedFrames == 0
                    ? Optional.empty()
                    : Optional.of(reference(1, paired ? "JPG" : "CR3"));
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

        private static CameraMediaReference oldReference() {
            return new CameraMediaReference(
                    "card1",
                    "/ccapi/ver140/contents/card1/DCIM/100CANON/OLD.CR3",
                    "OLD.CR3", "CR3", "ver140");
        }

        private static CameraMediaReference reference(int index, String format) {
            String fileName = "NEW_" + index + "." + format;
            return new CameraMediaReference(
                    "card1",
                    "/ccapi/ver140/contents/card1/DCIM/100CANON/" + fileName,
                    fileName, format, "ver140");
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
