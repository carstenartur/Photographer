package com.photographer.focusstack.session;

import com.photographer.focusstack.common.camera.CameraBackend;

/**
 * Resolves a {@link CameraBackend} for a given camera profile ID.
 *
 * <p>This interface decouples session-core from the application-level
 * {@code CameraBackendFactory}, allowing callers to supply any backend
 * implementation (including mocks in tests). The default
 * {@link #openSession(String, CameraSessionPurpose, String)} implementation keeps
 * the interface functional and preserves existing test lambdas while production
 * applications may override it with an exclusive ownership handle.</p>
 */
@FunctionalInterface
public interface CameraBackendProvider {

    /**
     * Returns the {@link CameraBackend} for the given camera profile ID.
     *
     * @param cameraProfileId the ID of the configured camera profile
     * @return the resolved backend
     * @throws SessionException if the profile cannot be found
     */
    CameraBackend getBackend(String cameraProfileId);

    /**
     * Opens one logical camera-owning session.
     *
     * <p>The default adapter is unmanaged for backward compatibility. Production
     * wiring should override this method and acquire ownership before resolving
     * or contacting the backend.</p>
     */
    default CameraBackendSession openSession(
            String cameraProfileId,
            CameraSessionPurpose purpose,
            String workflowId) {
        return CameraBackendSession.unmanaged(getBackend(cameraProfileId));
    }
}
