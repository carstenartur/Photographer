package com.photographer.focusstack.common.camera;

/**
 * Prepared native focus-bracketing settings that must be restored after use.
 *
 * <p>Closing the session is idempotent and restores the camera settings captured
 * before preparation. The session does not itself trigger a shutter action.</p>
 */
public interface NativeFocusBracketingSession extends AutoCloseable {

    NativeFocusBracketingRequest request();

    @Override
    void close();
}
