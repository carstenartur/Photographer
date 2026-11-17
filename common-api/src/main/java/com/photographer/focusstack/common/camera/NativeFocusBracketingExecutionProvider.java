package com.photographer.focusstack.common.camera;

/**
 * Optional backend extension that can trigger one camera-owned focus-bracketing sequence.
 *
 * <p>The caller must first obtain a prepared {@link NativeFocusBracketingSession}
 * from the same provider. Implementations must reject foreign sessions and repeated
 * trigger attempts.</p>
 */
public interface NativeFocusBracketingExecutionProvider
        extends NativeFocusBracketingProvider {

    /**
     * Verifies the shutter, media listing and event transport without changing camera state.
     * Returns the event mode to use for this sequence.
     * @throws NativeFocusBracketingTransportException if a required operation is unavailable
     */
    default CameraMediaProvider.EventMode preflightNativeFocusBracketingTransport() {
        throw new NativeFocusBracketingTransportException(
                NativeFocusBracketingTransportException.Reason.TRANSPORT_UNVERIFIED);
    }

    /** Triggers exactly one native sequence for the already prepared session. */
    void triggerNativeFocusBracketing(NativeFocusBracketingSession session);
}
