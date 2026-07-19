package com.photographer.focusstack.app.web.exception;

import com.photographer.focusstack.camera.exception.CameraException;
import com.photographer.focusstack.camera.exception.CameraOperationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.DEVICE_BUSY;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.METHOD_NOT_ALLOWED;
import static com.photographer.focusstack.camera.exception.CameraOperationException.Reason.TIMEOUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GlobalExceptionHandlerCameraOperationTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsTemporaryStateToStableServiceUnavailableProblem() {
        CameraOperationException failure = new CameraOperationException(
                "camera-profile", DEVICE_BUSY, 503);

        ProblemDetail problem = handler.handleCameraOperation(failure);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), problem.getStatus());
        assertEquals("DEVICE_BUSY", problem.getProperties().get("code"));
        assertEquals("TEMPORARY_STATE", problem.getProperties().get("category"));
        assertEquals(true, problem.getProperties().get("retryable"));
        assertEquals("camera-profile", problem.getProperties().get("cameraProfileId"));
        assertEquals(503, problem.getProperties().get("downstreamStatus"));
    }

    @Test
    void unwrapsTypedCauseFromLegacyCameraException() {
        CameraOperationException typed = new CameraOperationException(
                "camera-profile", METHOD_NOT_ALLOWED, 405);
        CameraException legacyWrapper = new CameraException(
                "legacy text containing http://secret-camera and password=secret", typed);

        ProblemDetail problem = handler.handleCameraException(legacyWrapper);

        assertEquals(HttpStatus.BAD_GATEWAY.value(), problem.getStatus());
        assertEquals("METHOD_NOT_ALLOWED", problem.getProperties().get("code"));
        assertFalse(problem.getDetail().contains("secret-camera"));
        assertFalse(problem.getDetail().contains("password=secret"));
    }

    @Test
    void mapsTimeoutWithoutReflectingCauseMessage() {
        CameraOperationException timeout = new CameraOperationException(
                "camera-profile",
                TIMEOUT,
                null,
                new java.net.SocketTimeoutException("secret-host password secret"));

        ProblemDetail problem = handler.handleCameraOperation(timeout);

        assertEquals(HttpStatus.GATEWAY_TIMEOUT.value(), problem.getStatus());
        assertEquals("TIMEOUT", problem.getProperties().get("code"));
        assertFalse(problem.getDetail().contains("secret-host"));
        assertFalse(problem.getDetail().contains("password"));
    }
}
