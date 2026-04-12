package com.photographer.focusstack.cr3;

/**
 * Thrown when a CR3 file has an invalid or unsupported structure.
 */
public class Cr3FormatException extends Cr3Exception {

    public Cr3FormatException(String message) {
        super(message);
    }

    public Cr3FormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
