package com.photographer.focusstack.camera.backend;

import com.photographer.focusstack.camera.backend.FolderCameraBackend;
import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.model.CameraProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the correct {@link CameraBackend} for a given {@link CameraProfile}.
 *
 * <ul>
 *   <li>{@code mock} profiles → {@link MockCameraBackend}</li>
 *   <li>{@code canon-ccapi} profiles → {@link CanonCcapiBackend} pointed at the profile's host</li>
 * </ul>
 *
 * Backends are cached per profile id so that focus-position state is preserved
 * across requests for the same camera.
 */
@Component
public class CameraBackendFactory {

    private static final Logger log = LoggerFactory.getLogger(CameraBackendFactory.class);

    private final long mockDelayMs;
    private final int simulateTimeoutAfterFrames;
    private final int simulateDisconnectAfterFrames;
    private final int simulateEmptyFrameAt;
    private final int simulateMovementAt;
    private final RestTemplate restTemplate;

    /** Cache backends per profile-id so focus position state is preserved. */
    private final Map<String, CameraBackend> cache = new ConcurrentHashMap<>();

    public CameraBackendFactory(
            @Value("${camera.mock.delay-ms:50}") long mockDelayMs,
            @Value("${camera.mock.simulate-timeout-after-frames:0}") int simulateTimeoutAfterFrames,
            @Value("${camera.mock.simulate-disconnect-after-frames:0}") int simulateDisconnectAfterFrames,
            @Value("${camera.mock.simulate-empty-frame-at:0}") int simulateEmptyFrameAt,
            @Value("${camera.mock.simulate-movement-at:0}") int simulateMovementAt) {
        this.mockDelayMs = mockDelayMs;
        this.simulateTimeoutAfterFrames = simulateTimeoutAfterFrames;
        this.simulateDisconnectAfterFrames = simulateDisconnectAfterFrames;
        this.simulateEmptyFrameAt = simulateEmptyFrameAt;
        this.simulateMovementAt = simulateMovementAt;
        // Configure timeouts: 10 s connection, 30 s read (allows multi-MB JPEG downloads).
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(30_000);
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * Returns a {@link CameraBackend} appropriate for the given profile.
     * The backend is created on first access and then cached by profile id.
     * If the profile's host or backendType changes, call {@link #evict(String)}
     * to force re-creation.
     */
    public CameraBackend getBackend(CameraProfile profile) {
        return cache.computeIfAbsent(profile.id(), id -> createBackend(profile));
    }

    /** Removes a cached backend (e.g. when a profile is updated or deleted). */
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
            String path = profile.host(); // host field reused as folder path
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException(
                        "Profile '" + profile.name() + "': host field must contain a valid folder path for folder backend");
            }
            java.nio.file.Path folderPath = java.nio.file.Path.of(path);
            if (!folderPath.isAbsolute()) {
                folderPath = folderPath.toAbsolutePath();
            }
            if (!folderPath.toFile().isDirectory()) {
                // Log a warning but allow creation; the directory may be created at runtime
                // (e.g., replay session starts before images are written). Actual failures
                // will be surfaced when getLiveView() is called on the backend.
                log.warn("FolderCameraBackend: path does not exist or is not a directory: {}", folderPath);
            }
            log.info("Creating FolderCameraBackend for profile '{}' ({}) → {}",
                    profile.name(), profile.id(), folderPath);
            return new FolderCameraBackend(folderPath);
        }
        // Default: canon-ccapi
        String host = profile.host();
        String baseUrl = host.startsWith("http") ? host : "http://" + host + ":8080";
        log.info("Creating CanonCcapiBackend for profile '{}' ({}) → {}",
                profile.name(), profile.id(), baseUrl);
        return new CanonCcapiBackend(baseUrl, restTemplate);
    }
}
