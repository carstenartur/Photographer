package com.photographer.focusstack.app.web.exception;

import com.photographer.focusstack.camera.exception.CameraAuthenticationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import static com.photographer.focusstack.camera.exception.CameraAuthenticationException.Reason.CREDENTIALS_REJECTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GlobalExceptionHandlerAuthenticationTest {

    @Test
    void mapsAuthenticationFailureToSanitizedUnauthorizedProblem() {
        CameraAuthenticationException failure =
                new CameraAuthenticationException("camera-profile", CREDENTIALS_REJECTED);

        ProblemDetail problem = new GlobalExceptionHandler()
                .handleCameraAuthentication(failure);

        assertEquals(HttpStatus.UNAUTHORIZED.value(), problem.getStatus());
        assertEquals("Camera Authentication Failed", problem.getTitle());
        assertEquals("CREDENTIALS_REJECTED", problem.getProperties().get("code"));
        assertEquals("camera-profile", problem.getProperties().get("cameraProfileId"));
        assertFalse(problem.getDetail().contains("nonce"));
        assertFalse(problem.getDetail().contains("Authorization"));
        assertFalse(problem.getDetail().contains("password"));
    }
}
