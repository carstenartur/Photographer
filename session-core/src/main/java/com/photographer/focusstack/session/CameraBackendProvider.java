package com.photographer.focusstack.session;

import com.photographer.focusstack.common.camera.CameraBackend;

/**
 * Resolves a {@link CameraBackend} for a given camera profile ID.
 *
 * <p>This interface decouples session-core from the application-level
 * {@code CameraBackendFactory}, allowing callers to supply any backend
 * implementation (including mocks in tests).</p>
 */
@FunctionalInterface
public interface CameraBackendProvider {

    /**
     * Returns the {@link CameraBackend} for the given camera profile ID.
     *
     * @param cameraProfileId the ID of the configured camera profile
     * @return the resolved backend
     * @throws com.photographer.focusstack.session.SessionException if the profile cannot be found
     */
    CameraBackend getBackend(String cameraProfileId);
}
