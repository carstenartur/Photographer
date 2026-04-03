package com.photographer.focusstack.camera.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CameraNotFoundException.class)
    public ProblemDetail handleCameraNotFound(CameraNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Camera Not Found");
        return problem;
    }

    @ExceptionHandler(CameraException.class)
    public ProblemDetail handleCameraException(CameraException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Camera Error");
        return problem;
    }
}
