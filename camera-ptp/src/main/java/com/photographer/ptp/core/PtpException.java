package com.photographer.ptp.core;

public class PtpException extends RuntimeException {
    private final int responseCode;

    public PtpException(String message) {
        super(message);
        this.responseCode = 0;
    }

    public PtpException(String message, Throwable cause) {
        super(message, cause);
        this.responseCode = 0;
    }

    public PtpException(int responseCode, String message) {
        super(message);
        this.responseCode = responseCode;
    }

    public int getResponseCode() {
        return responseCode;
    }
}
