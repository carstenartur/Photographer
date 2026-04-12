package com.photographer.focusstack.session;

/**
 * Thrown when a session or profile job is not found.
 * Maps to HTTP 404 in the exception handler.
 */
public class SessionNotFoundException extends SessionException {

    public SessionNotFoundException(String message) {
        super(message);
    }
}
