package com.photographer.focusstack.app.web.exception;

import com.photographer.focusstack.camera.exception.CameraServiceUnavailableException;
import com.photographer.focusstack.session.SessionUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerShutdownTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void cameraAndSessionShutdownUseTheSameFixed503Contract() {
        for (RuntimeException failure : new RuntimeException[]{
                new CameraServiceUnavailableException(),
                new SessionUnavailableException()}) {
            ProblemDetail problem = handler.handleCameraServiceUnavailable(failure);
            assertThat(problem.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
            assertThat(problem.getTitle()).isEqualTo("Camera Service Unavailable");
            assertThat(problem.getDetail()).isEqualTo("Camera services are shutting down");
            assertThat(problem.getProperties())
                    .containsEntry("code", "CAMERA_SERVICE_SHUTTING_DOWN")
                    .containsEntry("retryable", false);
            assertThat(problem.toString())
                    .doesNotContain("camera.example")
                    .doesNotContain("owner");
        }
    }
}
