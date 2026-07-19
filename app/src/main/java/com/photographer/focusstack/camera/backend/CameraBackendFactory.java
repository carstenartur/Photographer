package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.app.config.CcapiAuthenticationProperties;
import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.model.CameraProfile;
import com.photographer.focusstack.plugin.CameraBackendPlugin;
import com.photographer.ptp.backend.PtpCameraBackendFactory;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Resolves the correct {@link CameraBackend} for a given {@link CameraProfile}.
 *
 * <ul>
 *   <li>{@code mock} profiles → {@link MockCameraBackend}</li>
 *   <li>{@code canon-ccapi} profiles → {@link CanonCcapiNativeFocusBracketingBackend}
 *       with an isolated profile-scoped HTTP transport</li>
 *   <li>Unknown types → checks {@link CameraBackendRegistry} for external plugin backends</li>
 * </ul>
 *
 * Backends are cached per profile id so focus-position and CCAPI authentication state
 * remain associated with one camera identity only. A secret-free transport fingerprint
 * replaces stale cache entries atomically when the effective backend type, location or
 * credential configuration changes. Managed transports are closed after replacement,
 * explicit eviction and application shutdown.
 */
@Component
public class CameraBackendFactory {

    private static final Logger log = LoggerFactory.getLogger(CameraBackendFactory.class);

    private final long mockDelayMs;
    private final int simulateTimeoutAfterFrames;
    private final int simulateDisconnectAfterFrames;
    private final int simulateEmptyFrameAt;
    private final int simulateMovementAt;
    private final CameraBackendRegistry cameraBackendRegistry;
    private final CcapiRestTemplateFactory ccapiRestTemplateFactory;

    /** Cache backends together with their transport identity and owned cleanup action. */
    private final Map<String, CachedBackend> cache = new ConcurrentHashMap<>();

    public CameraBackendFactory(
            @Value("${camera.mock.delay-ms:50}") long mockDelayMs,
            @Value("${camera.mock.simulate-timeout-after-frames:0}") int simulateTimeoutAfterFrames,
            @Value("${camera.mock.simulate-disconnect-after-frames:0}") int simulateDisconnectAfterFrames,
            @Value("${camera.mock.simulate-empty-frame-at:0}") int simulateEmptyFrameAt,
            @Value("${camera.mock.simulate-movement-at:0}") int simulateMovementAt,
            CameraBackendRegistry cameraBackendRegistry,
            CcapiRestTemplateFactory ccapiRestTemplateFactory) {
        this.mockDelayMs = mockDelayMs;
        this.simulateTimeoutAfterFrames = simulateTimeoutAfterFrames;
        this.simulateDisconnectAfterFrames = simulateDisconnectAfterFrames;
        this.simulateEmptyFrameAt = simulateEmptyFrameAt;
        this.simulateMovementAt = simulateMovementAt;
        this.cameraBackendRegistry = cameraBackendRegistry;
        this.ccapiRestTemplateFactory = ccapiRestTemplateFactory;
    }

    /**
     * Returns a backend matching the profile's current transport configuration.
     *
     * <p>Creation happens inside the per-key {@link ConcurrentHashMap#compute}
     * operation. If replacement fails, the previous mapping remains installed and
     * its transport is not closed.</p>
     */
    public CameraBackend getBackend(CameraProfile profile) {
        Objects.requireNonNull(profile, "profile");
        BackendFingerprint desiredFingerprint = fingerprint(profile);
        AtomicReference<CachedBackend> retired = new AtomicReference<>();

        CachedBackend selected = cache.compute(profile.id(), (profileId, existing) -> {
            if (existing != null && existing.fingerprint().equals(desiredFingerprint)) {
                return existing;
            }
            CachedBackend replacement = createBackend(profile, desiredFingerprint);
            retired.set(existing);
            return replacement;
        });

        CachedBackend previous = retired.get();
        if (previous != null) {
            log.info("Replacing cached camera backend for profile '{}' after configuration change",
                    profile.id());
            closeSafely(profile.id(), previous);
        }
        return selected.backend();
    }

    /** Removes and closes a cached backend so later access creates a fresh transport. */
    public void evict(String profileId) {
        CachedBackend removed = cache.remove(profileId);
        if (removed != null) {
            closeSafely(profileId, removed);
        }
    }

    /** Releases all per-profile HTTP clients and credential copies during shutdown. */
    @PreDestroy
    public void close() {
        cache.forEach(this::closeSafely);
        cache.clear();
    }

    private BackendFingerprint fingerprint(CameraProfile profile) {
        String type = normalizedType(profile.backendType());
        if ("mock".equals(type) || "canon-usb".equals(type)) {
            return BackendFingerprint.unmanaged(type, "");
        }
        if ("folder".equals(type)) {
            return BackendFingerprint.unmanaged(type, normalizedFolder(profile).toString());
        }
        if (cameraBackendRegistry.findByType(type).isPresent()) {
            return BackendFingerprint.unmanaged(type, "plugin");
        }

        URI baseUri = ccapiBaseUri(profile);
        CcapiTransportFingerprint ccapiFingerprint =
                ccapiRestTemplateFactory.fingerprint(profile, baseUri);
        return BackendFingerprint.ccapi(type, ccapiFingerprint);
    }

    private CachedBackend createBackend(
            CameraProfile profile,
            BackendFingerprint plannedFingerprint) {
        String type = normalizedType(profile.backendType());
        if ("mock".equals(type)) {
            log.info("Creating MockCameraBackend for profile '{}' ({})", profile.name(), profile.id());
            return CachedBackend.unmanaged(
                    new MockCameraBackend(mockDelayMs, simulateTimeoutAfterFrames,
                            simulateDisconnectAfterFrames, simulateEmptyFrameAt,
                            simulateMovementAt),
                    plannedFingerprint);
        }
        if ("folder".equals(type)) {
            Path folderPath = normalizedFolder(profile);
            if (!folderPath.toFile().isDirectory()) {
                log.warn("FolderCameraBackend: path does not exist or is not a directory: {}", folderPath);
            }
            log.info("Creating FolderCameraBackend for profile '{}' ({}) → {}",
                    profile.name(), profile.id(), folderPath);
            return CachedBackend.unmanaged(
                    new FolderCameraBackend(folderPath), plannedFingerprint);
        }

        if ("canon-usb".equals(type)) {
            log.info("Creating PtpCameraBackend (USB) for profile '{}' ({})", profile.name(), profile.id());
            return CachedBackend.unmanaged(
                    PtpCameraBackendFactory.createUsbBackend(), plannedFingerprint);
        }

        Optional<CameraBackendPlugin> pluginBackend = cameraBackendRegistry.findByType(type);
        if (pluginBackend.isPresent()) {
            log.info("Using external CameraBackendPlugin '{}' for profile '{}' ({})",
                    type, profile.name(), profile.id());
            return CachedBackend.unmanaged(pluginBackend.get(), plannedFingerprint);
        }

        CcapiTransportCreation creation =
                ccapiRestTemplateFactory.create(profile, ccapiBaseUri(profile));
        CcapiTransport transport = creation.transport();
        try {
            log.info("Creating CanonCcapiNativeFocusBracketingBackend for profile '{}' ({})",
                    profile.name(), profile.id());
            CameraBackend backend = new CanonCcapiNativeFocusBracketingBackend(
                    creation.fingerprint().baseUri().toString(), transport.restTemplate());
            BackendFingerprint actualFingerprint =
                    BackendFingerprint.ccapi(type, creation.fingerprint());
            return new CachedBackend(backend, transport::close, actualFingerprint);
        } catch (RuntimeException exception) {
            transport.close();
            throw exception;
        }
    }

    private static String normalizedType(String backendType) {
        if (backendType == null || backendType.isBlank()) {
            throw new IllegalArgumentException("Camera backend type is required");
        }
        return backendType.trim().toLowerCase(Locale.ROOT);
    }

    private static Path normalizedFolder(CameraProfile profile) {
        String path = profile.host();
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException(
                    "Profile '" + profile.name()
                            + "': host field must contain a valid folder path for folder backend");
        }
        return Path.of(path).toAbsolutePath().normalize();
    }

    private static URI ccapiBaseUri(CameraProfile profile) {
        String host = profile.host();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException(
                    "Profile '" + profile.name() + "': host is required for canon-ccapi");
        }
        String trimmed = host.trim();
        String value = trimmed.regionMatches(true, 0, "http://", 0, 7)
                || trimmed.regionMatches(true, 0, "https://", 0, 8)
                ? trimmed
                : "http://" + trimmed + ":8080";
        return CcapiRestTemplateFactory.normalizeBaseUri(URI.create(value));
    }

    private void closeSafely(String profileId, CachedBackend cachedBackend) {
        try {
            cachedBackend.close();
        } catch (RuntimeException exception) {
            log.warn("Failed to close camera transport for profile '{}'", profileId, exception);
        }
    }

    private record BackendFingerprint(
            String backendType,
            String location,
            CcapiAuthenticationProperties.CredentialState credentialState) {

        private BackendFingerprint {
            Objects.requireNonNull(backendType, "backendType");
            Objects.requireNonNull(location, "location");
        }

        private static BackendFingerprint unmanaged(String type, String location) {
            return new BackendFingerprint(type, location, null);
        }

        private static BackendFingerprint ccapi(
                String type,
                CcapiTransportFingerprint fingerprint) {
            return new BackendFingerprint(
                    type,
                    fingerprint.baseUri().toString(),
                    fingerprint.credentialState());
        }
    }

    private record CachedBackend(
            CameraBackend backend,
            Runnable closeAction,
            BackendFingerprint fingerprint) {

        private CachedBackend {
            Objects.requireNonNull(backend, "backend");
            Objects.requireNonNull(closeAction, "closeAction");
            Objects.requireNonNull(fingerprint, "fingerprint");
        }

        private static CachedBackend unmanaged(
                CameraBackend backend,
                BackendFingerprint fingerprint) {
            return new CachedBackend(backend, () -> { }, fingerprint);
        }

        private void close() {
            closeAction.run();
        }
    }
}
