package com.photographer.focusstack.session;

/** Fixed, identity-free signal for session starts racing with application shutdown. */
public final class SessionUnavailableException extends SessionException {
    public SessionUnavailableException() {
        super("Camera services are shutting down");
    }
}
