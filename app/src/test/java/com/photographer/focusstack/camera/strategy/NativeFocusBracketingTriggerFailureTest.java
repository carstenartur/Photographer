package com.photographer.focusstack.camera.strategy;

import com.photographer.focusstack.common.camera.CameraBackend;
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
import java.util.List;
import java.util.Optional;

import static com.photographer.focusstack.common.camera.NativeFocusBracketingExecutionResult.CompletionState.FAILED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeFocusBracketingTriggerFailureTest {

    @Test
    void failedTriggerIsNotReportedAsSuccessfullyTriggered() {
        FailingTriggerBackend backend = new FailingTriggerBackend();
        NativeFocusBracketingRequest request =
                new NativeFocusBracketingRequest(3, 1, false, false, false);

        NativeFocusBracketingExecutionResult result =
                new NativeFocusBracketingExecutor().execute(
                        backend,
                        request,
                        Duration.ofSeconds(1),
                        () -> false,
                        (media, reference) -> "unused");

        assertEquals(FAILED, result.completionState());
        assertTrue(result.partialFailureReason().contains("synthetic trigger failure"));
        assertTrue(result.partialFailureReason().contains("was not triggered"));
        assertEquals(1, backend.closeCount);
    }

    private static final class FailingTriggerBackend implements
            CameraBackend,
            NativeFocusBracketingExecutionProvider,
            CameraMediaProvider {

        private int closeCount;

        @Override
        public EventMode preflightNativeFocusBracketingTransport() {
            return EventMode.POLLING;
        }

        @Override
        public NativeFocusBracketingCapabilities getNativeFocusBracketingCapabilities() {
            return new NativeFocusBracketingCapabilities(
                    true,
                    Optional.of(new IntegerSettingRange(2, 10, 1)),
                    Optional.of(new IntegerSettingRange(1, 10, 1)),
                    false, false, false);
        }

        @Override
        public NativeFocusBracketingSession prepareNativeFocusBracketing(
                NativeFocusBracketingRequest request) {
            return new NativeFocusBracketingSession() {
                @Override
                public NativeFocusBracketingRequest request() {
                    return request;
                }

                @Override
                public void close() {
                    closeCount++;
                }
            };
        }

        @Override
        public void triggerNativeFocusBracketing(NativeFocusBracketingSession session) {
            throw new IllegalStateException("synthetic trigger failure");
        }

        @Override
        public MediaPage listMedia(MediaQuery query) {
            return new MediaPage(List.of(), 0, false);
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
            return Optional.empty();
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
