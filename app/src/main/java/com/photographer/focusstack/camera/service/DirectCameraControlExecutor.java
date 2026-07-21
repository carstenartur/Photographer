package com.photographer.focusstack.camera.service;

import com.photographer.focusstack.camera.backend.CameraBackendFactory;
import com.photographer.focusstack.camera.session.CameraLeaseIdentityProvider;
import com.photographer.focusstack.camera.session.CameraLeaseRequest;
import com.photographer.focusstack.camera.session.CameraSessionLease;
import com.photographer.focusstack.camera.session.CameraSessionLeaseManager;
import com.photographer.focusstack.camera.session.CameraWorkflowType;
import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.model.CameraProfile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Executes one synchronous camera-control or profile-maintenance operation while
 * holding exclusive logical ownership of the selected CCAPI origin.
 *
 * <p>The lease is acquired from the existing profile before backend resolution,
 * cache eviction or profile mutation. A conflicting workflow therefore prevents
 * the request from contacting or closing the camera transport.</p>
 */
@Service
public final class DirectCameraControlExecutor {

    private final CameraProfileService profileService;
    private final CameraBackendFactory backendFactory;
    private final CameraSessionLeaseManager leaseManager;
    private final CameraLeaseIdentityProvider identityProvider;
    private final Duration operationLifetime;

    public DirectCameraControlExecutor(
            CameraProfileService profileService,
            CameraBackendFactory backendFactory,
            CameraSessionLeaseManager leaseManager,
            CameraLeaseIdentityProvider identityProvider,
            @Value("${camera.session-lease.direct-control-lifetime:PT5M}")
            Duration operationLifetime) {
        this.profileService = Objects.requireNonNull(profileService, "profileService");
        this.backendFactory = Objects.requireNonNull(backendFactory, "backendFactory");
        this.leaseManager = Objects.requireNonNull(leaseManager, "leaseManager");
        this.identityProvider = Objects.requireNonNull(identityProvider, "identityProvider");
        if (operationLifetime == null
                || operationLifetime.isZero()
                || operationLifetime.isNegative()) {
            throw new IllegalArgumentException("operationLifetime must be positive");
        }
        this.operationLifetime = operationLifetime;
    }

    public <T> T withBackend(
            String profileId,
            Function<CameraBackend, T> operation) {
        Objects.requireNonNull(operation, "operation");
        CameraProfile profile = profileService.getProfile(profileId);
        try (CameraSessionLease ignored = acquire(profile)) {
            return operation.apply(backendFactory.getBackend(profile));
        }
    }

    public void withBackend(
            String profileId,
            java.util.function.Consumer<CameraBackend> operation) {
        Objects.requireNonNull(operation, "operation");
        withBackend(profileId, backend -> {
            operation.accept(backend);
            return null;
        });
    }

    /**
     * Runs profile/cache maintenance under the existing camera identity without
     * resolving a new backend or contacting the camera.
     */
    public <T> T withProfileLease(
            String profileId,
            Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation");
        CameraProfile profile = profileService.getProfile(profileId);
        try (CameraSessionLease ignored = acquire(profile)) {
            return operation.get();
        }
    }

    public void withProfileLease(String profileId, Runnable operation) {
        Objects.requireNonNull(operation, "operation");
        withProfileLease(profileId, () -> {
            operation.run();
            return null;
        });
    }

    private CameraSessionLease acquire(CameraProfile profile) {
        return identityProvider.resolve(profile)
                .map(key -> {
                    String requestId = UUID.randomUUID().toString();
                    return leaseManager.acquire(new CameraLeaseRequest(
                            key,
                            CameraWorkflowType.DIRECT_CONTROL,
                            requestId,
                            "direct-control:" + requestId,
                            operationLifetime));
                })
                .orElseGet(CameraSessionLease::noop);
    }
}
