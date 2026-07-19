package com.photographer.focusstack.camera.exception;

import java.util.Objects;

/**
 * Sanitized final CCAPI authentication failure.
 *
 * <p>The exception deliberately contains no downstream challenge header, realm,
 * nonce, user name, password or camera URL.</p>
 */
public final class CameraAuthenticationException extends CameraException {

    public enum Reason {
        CREDENTIALS_REQUIRED,
        CREDENTIALS_REJECTED
    }

    private final String profileId;
    private final Reason reason;

    public CameraAuthenticationException(String profileId, Reason reason) {
        super(message(profileId, reason));
        this.profileId = Objects.requireNonNull(profileId, "profileId");
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public String profileId() {
        return profileId;
    }

    public Reason reason() {
        return reason;
    }

    private static String message(String profileId, Reason reason) {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(reason, "reason");
        return switch (reason) {
            case CREDENTIALS_REQUIRED ->
                    "CCAPI authentication is required for camera profile '" + profileId + "'";
            case CREDENTIALS_REJECTED ->
                    "CCAPI authentication was rejected for camera profile '" + profileId + "'";
        };
    }
}
