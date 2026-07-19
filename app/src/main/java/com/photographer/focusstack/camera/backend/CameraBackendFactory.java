package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.model.CameraProfile;
import com.photographer.focusstack.plugin.CameraBackendPlugin;
import com.photographer.ptp.backend.PtpCameraBackendFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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
 * remain associated with one camera identity only.
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

    /** Cache backends per profile-id so focus position and auth state are preserved. */
    private final Map<String, CameraBackend> cache = new ConcurrentHashMap<>();

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
     * Returns a {@link CameraBackend} appropriate for the given profile.
     * The backend is created on first access and then cached by profile id.
     * If transport-relevant profile or credential configuration changes, call
     * {@link #evict(String)} before requesting it again.
     */
    public CameraBackend getBackend(CameraProfile profile) {
        return cache.computeIfAbsent(profile.id(), id -> createBackend(profile));
    }

    /** Removes a cached backend so a later access creates a fresh transport. */
    public void evict(String profileId) {
        cache.remove(profileId);
    }

    private CameraBackend createBackend(CameraProfile profile) {
        String type = profile.backendType();
        if ("mock".equalsIgnoreCase(type)) {
            log.info("Creating MockCameraBackend for profile '{}' ({})", profile.name(), profile.id());
            return new MockCameraBackend(mockDelayMs, simulateTimeoutAfterFrames,
                    simulateDisconnectAfterFrames, simulateEmptyFrameAt, simulateMovementAt);
        }
        if ("folder".equalsIgnoreCase(type)) {
            String path = profile.host();
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException(
                        "Profile '" + profile.name() + "': host field must contain a valid folder path for folder backend");
            }
            java.nio.file.Path folderPath = java.nio.file.Path.of(path);
            if (!folderPath.isAbsolute()) {
                folderPath = folderPath.toAbsolutePath();
            }
            if (!folderPath.toFile().isDirectory()) {
                log.warn("FolderCameraBackend: path does not exist or is not a directory: {}", folderPath);
            }
            log.info("Creating FolderCameraBackend for profile '{}' ({}) → {}",
                    profile.name(), profile.id(), folderPath);
            return new FolderCameraBackend(folderPath);
        }

        if ("canon-usb".equalsIgnoreCase(type)) {
            log.info("Creating PtpCameraBackend (USB) for profile '{}' ({})", profile.name(), profile.id());
            return PtpCameraBackendFactory.createUsbBackend();
        }

        Optional<CameraBackendPlugin> pluginBackend = cameraBackendRegistry.findByType(type);
        if (pluginBackend.isPresent()) {
            log.info("Using external CameraBackendPlugin '{}' for profile '{}' ({})",
                    type, profile.name(), profile.id());
            return pluginBackend.get();
        }

        String host = profile.host();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException(
                    "Profile '" + profile.name() + "': host is required for canon-ccapi");
        }
        URI baseUri = URI.create(host.startsWith("http") ? host : "http://" + host + ":8080");
        RestTemplate restTemplate = ccapiRestTemplateFactory.create(profile, baseUri);
        log.info("Creating CanonCcapiNativeFocusBracketingBackend for profile '{}' ({})",
                profile.name(), profile.id());
        return new CanonCcapiNativeFocusBracketingBackend(baseUri.toString(), restTemplate);
    }
}
