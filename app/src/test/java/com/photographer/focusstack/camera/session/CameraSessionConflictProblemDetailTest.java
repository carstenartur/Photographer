package com.photographer.focusstack.camera.session;

import com.photographer.focusstack.app.web.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CameraSessionConflictProblemDetailTest {

    @Test
    void exposesStableCodeWithoutPrivateCameraOrOwnerIdentity() {
        CameraSessionConflictException conflict = new CameraSessionConflictException(
                "corr00000001", "FOCUS_STACKING", Duration.ofSeconds(12));

        ProblemDetail detail = new GlobalExceptionHandler()
                .handleCameraSessionConflict(conflict);

        assertThat(detail.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(detail.getTitle()).isEqualTo("Camera Session Conflict");
        assertThat(detail.getDetail()).isEqualTo(
                "Camera is already controlled by another workflow");
        assertThat(detail.getProperties())
                .containsEntry("code", "CAMERA_SESSION_CONFLICT")
                .containsEntry("ownerCorrelationId", "corr00000001")
                .containsEntry("workflowType", "FOCUS_STACKING")
                .containsEntry("retryable", true)
                .containsEntry("retryAfterSeconds", 12L)
                .doesNotContainKeys("cameraProfileId", "cameraUrl", "host", "ownerToken");
        assertThat(detail.toString())
                .doesNotContain("private-camera")
                .doesNotContain("secret");
    }
}
