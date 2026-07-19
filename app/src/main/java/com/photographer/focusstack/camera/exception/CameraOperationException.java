package com.photographer.focusstack.camera.exception;

import java.util.Objects;

/**
 * Sanitized camera-operation failure with stable machine-readable semantics.
 *
 * <p>The exception deliberately contains no camera URL, downstream response body,
 * authentication challenge, user name, password, nonce or opaque value.</p>
 */
public final class CameraOperationException extends CameraException {

    /** Broad failure class used by API clients without depending on a camera vendor. */
    public enum Category {
        CONTRACT,
        TEMPORARY_STATE,
        CANCELLATION,
        TRANSPORT,
        REJECTION
    }

    /**
     * Stable operation reasons. Retryability is intentionally explicit and
     * conservative; corrective-action and transport failures are not treated
     * as blind retries.
     */
    public enum Reason {
        ENDPOINT_NOT_FOUND(Category.CONTRACT, false, "The camera endpoint is unavailable"),
        METHOD_NOT_ALLOWED(Category.CONTRACT, false, "The camera rejected the HTTP method"),

        DEVICE_BUSY(Category.TEMPORARY_STATE, true, "The camera is temporarily busy"),
        DURING_SHOOTING_OR_RECORDING(
                Category.TEMPORARY_STATE, true, "The camera is shooting or recording"),
        TAKEN_IN_PREPARATION(
                Category.TEMPORARY_STATE, true, "The camera is preparing the requested service"),
        MODE_NOT_SUPPORTED(
                Category.TEMPORARY_STATE, false, "The operation is unavailable in the current camera mode"),
        LIVE_VIEW_NOT_STARTED(
                Category.TEMPORARY_STATE, false, "Live view must be started before this operation"),
        ALREADY_STARTED(
                Category.TEMPORARY_STATE, false, "The requested camera operation is already active"),
        OUT_OF_FOCUS(
                Category.TEMPORARY_STATE, false, "The camera could not acquire focus"),
        CANNOT_WRITE_TO_CARD(
                Category.TEMPORARY_STATE, false, "The camera cannot write to the recording media"),
        NOT_STARTED(
                Category.TEMPORARY_STATE, false, "The requested camera operation has not been started"),
        SERVICE_UNAVAILABLE(
                Category.TEMPORARY_STATE, false, "The camera service is unavailable"),

        CANCELLED(Category.CANCELLATION, false, "The camera operation was cancelled"),
        TIMEOUT(Category.TRANSPORT, false, "The camera operation timed out"),
        CONNECTION_FAILED(Category.TRANSPORT, false, "The camera connection failed"),
        CAMERA_REJECTED(Category.REJECTION, false, "The camera rejected the operation");

        private final Category category;
        private final boolean retryable;
        private final String detail;

        Reason(Category category, boolean retryable, String detail) {
            this.category = category;
            this.retryable = retryable;
            this.detail = detail;
        }

        public Category category() {
            return category;
        }

        public boolean retryable() {
            return retryable;
        }

        String detail() {
            return detail;
        }
    }

    private final String profileId;
    private final Reason reason;
    private final Integer downstreamStatus;

    public CameraOperationException(String profileId, Reason reason, Integer downstreamStatus) {
        this(profileId, reason, downstreamStatus, null);
    }

    public CameraOperationException(
            String profileId,
            Reason reason,
            Integer downstreamStatus,
            Throwable cause) {
        super(message(profileId, reason), cause);
        this.profileId = requireProfileId(profileId);
        this.reason = Objects.requireNonNull(reason, "reason");
        this.downstreamStatus = downstreamStatus;
    }

    public String profileId() {
        return profileId;
    }

    public Reason reason() {
        return reason;
    }

    public Category category() {
        return reason.category();
    }

    public boolean retryable() {
        return reason.retryable();
    }

    public Integer downstreamStatus() {
        return downstreamStatus;
    }

    private static String message(String profileId, Reason reason) {
        return Objects.requireNonNull(reason, "reason").detail()
                + " for camera profile '" + requireProfileId(profileId) + "'";
    }

    private static String requireProfileId(String profileId) {
        Objects.requireNonNull(profileId, "profileId");
        if (profileId.isBlank()) {
            throw new IllegalArgumentException("profileId must not be blank");
        }
        return profileId;
    }
}
