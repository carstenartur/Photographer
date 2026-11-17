package com.photographer.focusstack.common.camera;

/** A missing transport operation detected before native settings or the shutter are changed. */
public final class NativeFocusBracketingTransportException extends IllegalStateException {

    public enum Reason {
        TRANSPORT_UNVERIFIED("the backend does not advertise a complete native capture transport"),
        SHUTTER_UNAVAILABLE("the native shutter operation is unavailable"),
        MEDIA_LISTING_UNAVAILABLE("the camera media listing is unavailable"),
        EVENT_TRANSPORT_UNAVAILABLE("no supported added-media event operation is available");

        private final String description;

        Reason(String description) {
            this.description = description;
        }
    }

    private final Reason reason;

    public NativeFocusBracketingTransportException(Reason reason) {
        super("Native focus bracketing is unavailable: " + reason.description + " (" + reason.name() + ")");
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
