package com.photographer.focusstack.fresnel;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice(basePackageClasses = FresnelProblem.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class FresnelErrors {
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, Object>> invalidRequest() {
        return protocolError(new FresnelProblem(400, "INVALID_REQUEST"));
    }

    @ExceptionHandler(FresnelProblem.class)
    ResponseEntity<Map<String, Object>> protocolError(FresnelProblem error) {
        var response = ResponseEntity.status(error.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store");
        if (error.status() == 401) response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return response.body(error.body());
    }
}
