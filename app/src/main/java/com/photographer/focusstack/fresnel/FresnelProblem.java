package com.photographer.focusstack.fresnel;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** Public protocol error; its code is safe to report to a paired client. */
public final class FresnelProblem extends RuntimeException {
    private final int status;
    private final String code;

    public FresnelProblem(int status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }

    Map<String, Object> body() {
        String title = switch (status) {
            case 400 -> "Invalid request";
            case 401 -> "Authorization required";
            case 403 -> "Operation forbidden";
            case 404 -> "Resource unavailable";
            case 409 -> "Request conflict";
            case 416 -> "Range unavailable";
            case 422 -> "Request cannot be applied";
            default -> HttpStatus.valueOf(status).getReasonPhrase();
        };
        return Map.of("type", "urn:fresnel:problem:" + code.toLowerCase(java.util.Locale.ROOT).replace('_', '-'), "title", title,
                "status", status, "code", code, "messageCode", code, "retryable",
                status == 429 || status == 503);
    }
}
