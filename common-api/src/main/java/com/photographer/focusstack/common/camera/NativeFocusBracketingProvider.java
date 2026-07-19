package com.photographer.focusstack.common.camera;

/** Optional camera-backend extension for camera-owned focus-bracketing sequences. */
public interface NativeFocusBracketingProvider {

    NativeFocusBracketingCapabilities getNativeFocusBracketingCapabilities();

    /**
     * Validates and applies the requested settings transactionally.
     *
     * <p>The caller must close the returned session after the native capture has
     * completed or failed. Preparation does not trigger the shutter.</p>
     */
    NativeFocusBracketingSession prepareNativeFocusBracketing(
            NativeFocusBracketingRequest request);
}
