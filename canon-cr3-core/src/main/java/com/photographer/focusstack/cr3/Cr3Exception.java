package com.photographer.focusstack.cr3;

/**
 * Base exception for all CR3 decoding errors.
 */
public class Cr3Exception extends Exception {

    public Cr3Exception(String message) {
        super(message);
    }

    public Cr3Exception(String message, Throwable cause) {
        super(message, cause);
    }
}
