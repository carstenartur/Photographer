package com.photographer.focusstack.camera.entity;

import com.photographer.focusstack.common.camera.NativeFocusBracketingExecutionResult;
import com.photographer.focusstack.common.camera.NativeFocusBracketingRequest;
import com.photographer.focusstack.common.model.JobStatus;
import com.photographer.focusstack.common.model.QualityMode;
import com.photographer.focusstack.common.model.StackingCaptureStrategy;
import com.photographer.focusstack.common.model.StackingExecutionMetadata;
import com.photographer.focusstack.common.model.StackingJob;
import com.photographer.focusstack.common.model.StackingParameters;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StackingJobExecutionMetadataTest {

    @Test
    void entityRoundTripRetainsRequestedSelectedAndEffectiveNativeState() {
        NativeFocusBracketingRequest nativeRequest =
                new NativeFocusBracketingRequest(12, 3, true, true, false);
        StackingParameters parameters = new StackingParameters(
                0, 1, 12, 0.01, false, QualityMode.HIGH,
                StackingCaptureStrategy.AUTO, nativeRequest);
        StackingExecutionMetadata metadata = new StackingExecutionMetadata(
                StackingCaptureStrategy.AUTO,
                StackingCaptureStrategy.NATIVE,
                12,
                3,
                true,
                true,
                false,
                NativeFocusBracketingExecutionResult.CompletionState.PARTIAL,
                10,
                9,
                true,
                "one media transfer failed");
        StackingJob source = new StackingJob(
                "job-1", "camera-1", JobStatus.FAILED,
                Instant.parse("2026-07-19T12:00:00Z"),
                Instant.parse("2026-07-19T12:01:00Z"),
                parameters,
                List.of("frame-1", "frame-2"),
                "partial native sequence",
                metadata);

        StackingJob restored = StackingJobEntity.fromRecord(source).toRecord();

        assertEquals(source, restored);
    }

    @Test
    void legacyParameterConstructorRemainsManual() {
        StackingParameters parameters = new StackingParameters(
                0, 2, 5, 0.01, false, QualityMode.BALANCED);

        assertEquals(StackingCaptureStrategy.MANUAL, parameters.captureStrategy());
        assertEquals(null, parameters.nativeFocusBracketing());
    }
}
