package com.photographer.focusstack.common.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PipelineBlockerKindTest {

    @Test
    void blockerKindFor_null_returnsCaptureFailedDefault() {
        assertEquals(PipelineBlockerKind.CAPTURE_FAILED, PipelineBlockerKind.blockerKindFor(null));
    }

    @Test
    void blockerKindFor_401InMessage_returnsPermissionDenied() {
        RuntimeException e = new RuntimeException("HTTP 401 Unauthorized");
        assertEquals(PipelineBlockerKind.PERMISSION_DENIED, PipelineBlockerKind.blockerKindFor(e));
    }

    @Test
    void blockerKindFor_403InMessage_returnsPermissionDenied() {
        RuntimeException e = new RuntimeException("HTTP 403 Forbidden");
        assertEquals(PipelineBlockerKind.PERMISSION_DENIED, PipelineBlockerKind.blockerKindFor(e));
    }

    @Test
    void blockerKindFor_429InMessage_returnsRateLimited() {
        RuntimeException e = new RuntimeException("429 Too Many Requests");
        assertEquals(PipelineBlockerKind.RATE_LIMITED, PipelineBlockerKind.blockerKindFor(e));
    }

    @Test
    void blockerKindFor_rateLimitMessage_returnsRateLimited() {
        RuntimeException e = new RuntimeException("rate limit exceeded");
        assertEquals(PipelineBlockerKind.RATE_LIMITED, PipelineBlockerKind.blockerKindFor(e));
    }

    @Test
    void blockerKindFor_503InMessage_returnsCameraBusy() {
        RuntimeException e = new RuntimeException("503 Camera Busy");
        assertEquals(PipelineBlockerKind.CAMERA_BUSY, PipelineBlockerKind.blockerKindFor(e));
    }

    @Test
    void blockerKindFor_busyInMessage_returnsCameraBusy() {
        RuntimeException e = new RuntimeException("camera busy writing to card");
        assertEquals(PipelineBlockerKind.CAMERA_BUSY, PipelineBlockerKind.blockerKindFor(e));
    }

    @Test
    void blockerKindFor_connectionRefused_returnsNotConnected() {
        RuntimeException e = new RuntimeException("connection refused");
        assertEquals(PipelineBlockerKind.NOT_CONNECTED, PipelineBlockerKind.blockerKindFor(e));
    }

    @Test
    void blockerKindFor_502InMessage_returnsNotConnected() {
        RuntimeException e = new RuntimeException("HTTP 502 Bad Gateway");
        assertEquals(PipelineBlockerKind.NOT_CONNECTED, PipelineBlockerKind.blockerKindFor(e));
    }

    @Test
    void blockerKindFor_unsupportedMessage_returnsModeNotSupported() {
        RuntimeException e = new RuntimeException("not supported by this camera");
        assertEquals(PipelineBlockerKind.MODE_NOT_SUPPORTED, PipelineBlockerKind.blockerKindFor(e));
    }

    @Test
    void blockerKindFor_emergencyMessage_returnsEmergencyStop() {
        RuntimeException e = new RuntimeException("emergency stop requested");
        assertEquals(PipelineBlockerKind.EMERGENCY_STOP, PipelineBlockerKind.blockerKindFor(e));
    }

    @Test
    void blockerKindFor_genericException_returnsCaptureFailedDefault() {
        RuntimeException e = new RuntimeException("something went wrong");
        assertEquals(PipelineBlockerKind.CAPTURE_FAILED, PipelineBlockerKind.blockerKindFor(e));
    }

    @Test
    void blockerKindFor_noMessage_returnsCaptureFailedDefault() {
        RuntimeException e = new RuntimeException((String) null);
        assertEquals(PipelineBlockerKind.CAPTURE_FAILED, PipelineBlockerKind.blockerKindFor(e));
    }
}
