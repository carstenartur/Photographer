package com.photographer.focusstack.app.web.exception;

import com.photographer.focusstack.camera.exception.CameraAuthenticationException;
import com.photographer.focusstack.camera.exception.CameraException;
import com.photographer.focusstack.camera.exception.CameraNotFoundException;
import com.photographer.focusstack.camera.exception.CameraOperationException;
import com.photographer.focusstack.camera.session.CameraSessionConflictException;
import com.photographer.focusstack.session.SessionException;
import com.photographer.focusstack.session.SessionNotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.io.IOException;
import java.util.stream.Collectors;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(CameraNotFoundException.class)
    public ProblemDetail handleCameraNotFound(CameraNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Camera Not Found");
        return problem;
    }

    @ExceptionHandler(CameraAuthenticationException.class)
    public ProblemDetail handleCameraAuthentication(CameraAuthenticationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, ex.getMessage());
        problem.setTitle("Camera Authentication Failed");
        problem.setProperty("code", ex.reason().name());
        problem.setProperty("cameraProfileId", ex.profileId());
        return problem;
    }

    @ExceptionHandler(CameraSessionConflictException.class)
    public ProblemDetail handleCameraSessionConflict(CameraSessionConflictException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Camera Session Conflict");
        problem.setProperty("code", ex.code());
        problem.setProperty("ownerCorrelationId", ex.ownerCorrelationId());
        problem.setProperty("workflowType", ex.workflowType());
        problem.setProperty("retryable", ex.retryable());
        if (ex.retryAfter() != null) {
            problem.setProperty("retryAfterSeconds", ex.retryAfter().toSeconds());
        }
        return problem;
    }

    @ExceptionHandler(CameraOperationException.class)
    public ProblemDetail handleCameraOperation(CameraOperationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                publicStatus(ex), ex.getMessage());
        problem.setTitle(switch (ex.category()) {
            case CONTRACT -> "Camera Capability Mismatch";
            case TEMPORARY_STATE -> "Camera Temporarily Unavailable";
            case CANCELLATION -> "Camera Operation Cancelled";
            case TRANSPORT -> "Camera Connection Failure";
            case REJECTION -> "Camera Operation Rejected";
        });
        problem.setProperty("code", ex.reason().name());
        problem.setProperty("category", ex.category().name());
        problem.setProperty("retryable", ex.retryable());
        problem.setProperty("cameraProfileId", ex.profileId());
        if (ex.downstreamStatus() != null) {
            problem.setProperty("downstreamStatus", ex.downstreamStatus());
        }
        return problem;
    }

    private static HttpStatus publicStatus(CameraOperationException ex) {
        return switch (ex.category()) {
            case TEMPORARY_STATE -> HttpStatus.SERVICE_UNAVAILABLE;
            case CANCELLATION -> HttpStatus.CONFLICT;
            case TRANSPORT -> ex.reason() == CameraOperationException.Reason.TIMEOUT
                    ? HttpStatus.GATEWAY_TIMEOUT
                    : HttpStatus.BAD_GATEWAY;
            case CONTRACT, REJECTION -> HttpStatus.BAD_GATEWAY;
        };
    }

    @ExceptionHandler(CameraException.class)
    public ProblemDetail handleCameraException(CameraException ex) {
        return ex.operationFailure()
                .map(this::handleCameraOperation)
                .orElseGet(() -> {
                    ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                            HttpStatus.BAD_REQUEST, ex.getMessage());
                    problem.setTitle("Camera Error");
                    return problem;
                });
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationErrors(MethodArgumentNotValidException ex) {
        String details = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining(", "));
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, details);
        problem.setTitle("Validation Error");
        return problem;
    }

    @ExceptionHandler(SessionNotFoundException.class)
    public ProblemDetail handleSessionNotFound(SessionNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Session Not Found");
        return problem;
    }

    @ExceptionHandler(SessionException.class)
    public ProblemDetail handleSessionException(SessionException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Session Error");
        return problem;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        String details = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining(", "));
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, details);
        problem.setTitle("Constraint Violation");
        return problem;
    }

    @ExceptionHandler(IOException.class)
    public ProblemDetail handleIOException(IOException ex) {
        log.warn("I/O error processing request: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, "Failed to read or process the uploaded file");
        problem.setTitle("File Processing Error");
        return problem;
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ProblemDetail handleIllegalArgument(RuntimeException ex) {
        log.debug("Bad request: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Invalid request parameters");
        problem.setTitle("Invalid Request");
        return problem;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGenericException(Exception ex) {
        log.error("Unhandled exception", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        problem.setTitle("Internal Server Error");
        return problem;
    }
}
