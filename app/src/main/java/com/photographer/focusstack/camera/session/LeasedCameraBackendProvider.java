package com.photographer.focusstack.camera.session;

import com.photographer.focusstack.camera.backend.CameraBackendFactory;
import com.photographer.focusstack.camera.service.CameraProfileService;
import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.model.CameraProfile;
import com.photographer.focusstack.session.CameraBackendProvider;
import com.photographer.focusstack.session.CameraBackendSession;
import com.photographer.focusstack.session.CameraSessionPurpose;
import com.photographer.focusstack.session.SessionException;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Application adapter that acquires logical camera ownership before backend resolution.
 *
 * <p>The backend cache remains owned by {@link CameraBackendFactory}; closing a
 * {@link CameraBackendSession} releases only logical workflow ownership.</p>
 */
public final class LeasedCameraBackendProvider implements CameraBackendProvider {

    private final CameraBackendFactory backendFactory;
    private final CameraProfileService profileService;
    private final CameraSessionLeaseManager leaseManager;
    private final CameraLeaseIdentityProvider identityProvider;
    private final Map<CameraSessionPurpose, Duration> lifetimes;

    public LeasedCameraBackendProvider(
            CameraBackendFactory backendFactory,
            CameraProfileService profileService,
            CameraSessionLeaseManager leaseManager,
            CameraLeaseIdentityProvider identityProvider,
            Map<CameraSessionPurpose, Duration> lifetimes) {
        this.backendFactory = Objects.requireNonNull(backendFactory, "backendFactory");
        this.profileService = Objects.requireNonNull(profileService, "profileService");
        this.leaseManager = Objects.requireNonNull(leaseManager, "leaseManager");
        this.identityProvider = Objects.requireNonNull(identityProvider, "identityProvider");
        this.lifetimes = Map.copyOf(Objects.requireNonNull(lifetimes, "lifetimes"));
        for (CameraSessionPurpose purpose : CameraSessionPurpose.values()) {
            Duration duration = this.lifetimes.get(purpose);
            if (duration == null || duration.isZero() || duration.isNegative()) {
                throw new IllegalArgumentException(
                        "A positive camera session lifetime is required for " + purpose);
            }
        }
    }

    @Override
    public CameraBackend getBackend(String cameraProfileId) {
        CameraProfile profile = resolveProfile(cameraProfileId);
        try {
            return backendFactory.getBackend(profile);
        } catch (RuntimeException failure) {
            throw new SessionException("Cannot resolve camera backend");
        }
    }

    @Override
    public CameraBackendSession openSession(
            String cameraProfileId,
            CameraSessionPurpose purpose,
            String workflowId) {
        Objects.requireNonNull(purpose, "purpose");
        requireText(workflowId, "workflowId");
        CameraProfile profile = resolveProfile(cameraProfileId);
        Optional<CameraLeaseKey> key = identityProvider.resolve(profile);
        if (key.isEmpty()) {
            return CameraBackendSession.unmanaged(resolveBackend(profile));
        }

        CameraSessionLease lease = leaseManager.acquire(new CameraLeaseRequest(
                key.get(),
                workflowType(purpose),
                workflowId,
                "session-attempt:" + UUID.randomUUID(),
                lifetimes.get(purpose)));
        try {
            CameraBackend backend = resolveBackend(profile);
            return CameraBackendSession.managed(backend, lease::close);
        } catch (RuntimeException failure) {
            lease.close();
            throw failure;
        }
    }

    private CameraProfile resolveProfile(String cameraProfileId) {
        requireText(cameraProfileId, "cameraProfileId");
        try {
            return profileService.getProfile(cameraProfileId);
        } catch (RuntimeException failure) {
            throw new SessionException("Cannot resolve camera profile");
        }
    }

    private CameraBackend resolveBackend(CameraProfile profile) {
        try {
            return backendFactory.getBackend(profile);
        } catch (RuntimeException failure) {
            throw new SessionException("Cannot resolve camera backend");
        }
    }

    private static CameraWorkflowType workflowType(CameraSessionPurpose purpose) {
        return switch (purpose) {
            case HDR_TIMELAPSE -> CameraWorkflowType.HDR_TIMELAPSE;
            case FACE_ORBIT -> CameraWorkflowType.FACE_ORBIT;
            case ASTRO -> CameraWorkflowType.ASTRO;
        };
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
