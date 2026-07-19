package com.photographer.focusstack.camera.exception;

import java.util.Optional;

public class CameraException extends RuntimeException {

    public CameraException(String message) {
        super(message);
    }

    public CameraException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Returns a typed camera-operation failure retained anywhere in the cause chain.
     *
     * <p>This keeps legacy backend wrappers source-compatible while allowing callers
     * to inspect stable category, reason and retryability without parsing messages.</p>
     */
    public final Optional<CameraOperationException> operationFailure() {
        Throwable current = this;
        while (current != null) {
            if (current instanceof CameraOperationException operation) {
                return Optional.of(operation);
            }
            current = current.getCause();
        }
        return Optional.empty();
    }
}
