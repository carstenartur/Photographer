package com.photographer.focusstack.common.pipeline;

/**
 * Classifies the reason a pipeline step was blocked or failed.
 *
 * <p>Used by the observer pipeline (and future pipelines) to communicate
 * structured failure reasons to the frontend. The frontend can render
 * consistent icons and colours per kind instead of substring-matching on
 * free-form reason strings.</p>
 */
public enum PipelineBlockerKind {

    /** A hard emergency-stop was requested by the operator. */
    EMERGENCY_STOP,

    /** The pipeline is in a mandatory cool-down period between captures. */
    COOLDOWN,

    /** The caller does not have the required permission for this action. */
    ACCESS_DENIED,

    /** The camera or a required service is not connected. */
    NOT_CONNECTED,

    /** The remote API or camera imposed a rate limit. */
    RATE_LIMITED,

    /** A quality gate rejected the result (e.g. sharpness below threshold). */
    QUALITY_GATE,

    /** The AI model is loading or warming up; the call must be retried. */
    MODEL_WAIT,

    /** A permission check inside the AI gateway rejected the call. */
    PERMISSION_DENIED,

    /** The camera is busy (e.g. writing to card, autofocus running). */
    CAMERA_BUSY,

    /** The requested mode or operation is not supported by this camera. */
    MODE_NOT_SUPPORTED,

    /** The capture attempt failed (hardware or communication error). */
    CAPTURE_FAILED;

    /**
     * Infers the most appropriate {@code PipelineBlockerKind} from an exception.
     *
     * <p>The mapping is intentionally coarse-grained and driven by HTTP status
     * codes embedded in exception messages, as well as well-known exception
     * class names. Callers that have richer context (e.g. a specific HTTP status)
     * should map the kind themselves rather than relying solely on this helper.</p>
     *
     * <p>Passing {@code null} is allowed and returns {@link #CAPTURE_FAILED}.</p>
     *
     * @param t the exception to inspect; may be {@code null}
     * @return the closest matching kind; defaults to {@link #CAPTURE_FAILED}
     */
    public static PipelineBlockerKind blockerKindFor(Throwable t) {
        if (t == null) {
            return CAPTURE_FAILED;
        }
        String msg = t.getMessage() != null ? t.getMessage().toLowerCase() : "";
        String cls = t.getClass().getSimpleName().toLowerCase();

        // HTTP 401 / 403 → permission / auth issue
        if (msg.contains("401") || msg.contains("403")
                || msg.contains("unauthorized") || msg.contains("forbidden")
                || cls.contains("access") || cls.contains("permission")) {
            return PERMISSION_DENIED;
        }

        // HTTP 429 → rate limited
        if (msg.contains("429") || msg.contains("rate limit") || msg.contains("too many requests")) {
            return RATE_LIMITED;
        }

        // HTTP 503 / camera busy indicators
        if (msg.contains("503") || msg.contains("busy") || msg.contains("camera busy")) {
            return CAMERA_BUSY;
        }

        // HTTP 502 / 504 service unavailable / network failures → not connected.
        // (HTTP 503 is intentionally handled above as CAMERA_BUSY: Canon CCAPI
        // returns 503 specifically when the body is mid-action.)
        if (msg.contains("502") || msg.contains("504")
                || msg.contains("not connected") || msg.contains("connection refused")
                || cls.contains("connect") || cls.contains("timeout")
                || cls.contains("socket")) {
            return NOT_CONNECTED;
        }

        // Model loading
        if (msg.contains("model") && (msg.contains("loading") || msg.contains("warmup") || msg.contains("wait"))) {
            return MODEL_WAIT;
        }

        // Not supported
        if (msg.contains("not supported") || msg.contains("unsupported")) {
            return MODE_NOT_SUPPORTED;
        }

        // Emergency stop (require both words to avoid false positives)
        if (msg.contains("emergency") && msg.contains("stop")) {
            return EMERGENCY_STOP;
        }

        return CAPTURE_FAILED;
    }
}
