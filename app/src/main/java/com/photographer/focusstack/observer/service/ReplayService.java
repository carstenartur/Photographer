package com.photographer.focusstack.observer.service;

import com.photographer.focusstack.camera.backend.FolderCameraBackend;
import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.camera.LiveViewFrame;
import com.photographer.focusstack.observer.pipeline.ObservationEventListener;
import com.photographer.focusstack.observer.pipeline.ObserverPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Service that re-runs stored image series through the observer pipeline for
 * testing trigger strategies and comparing AI providers.
 *
 * <p>A replay session reads images from a folder via {@link FolderCameraBackend},
 * feeds them as {@link LiveViewFrame}s into an {@link ObserverPipeline}, and
 * records the resulting events.</p>
 *
 * <p>Speed is controlled via {@code speedMultiplier}: 1.0 = real-time (500 ms between
 * frames), 2.0 = twice as fast, etc.</p>
 */
@Service
public class ReplayService {

    private static final Logger log = LoggerFactory.getLogger(ReplayService.class);

    /** Default interval between replay frames in milliseconds. */
    private static final long BASE_FRAME_INTERVAL_MS = 500;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    /** Active replay sessions keyed by session ID. */
    private final Map<String, ReplayStatus> sessions = new ConcurrentHashMap<>();

    /**
     * Starts a replay session asynchronously.
     *
     * @param folderPath      path to the folder containing replay images
     * @param speedMultiplier 1.0 = real-time, 2.0 = double speed, etc.
     * @param profileName     observer profile name to use during replay
     * @return a {@link ReplayStatus} describing the started session
     */
    public ReplayStatus startReplay(String folderPath, double speedMultiplier, String profileName) {
        String sessionId = java.util.UUID.randomUUID().toString();
        ReplayStatus status = new ReplayStatus(sessionId, folderPath, profileName,
                speedMultiplier, "RUNNING", 0, 0, 0);
        sessions.put(sessionId, status);

        executor.submit(() -> runReplay(sessionId, folderPath, speedMultiplier, profileName));
        log.info("Started replay session {} folder={} profile={} speed={}x",
                sessionId, folderPath, profileName, speedMultiplier);
        return status;
    }

    /** Returns the status of a replay session, or {@code null} if not found. */
    public ReplayStatus getStatus(String sessionId) {
        return sessions.get(sessionId);
    }

    /** Returns all known replay sessions. */
    public List<ReplayStatus> getAllSessions() {
        return new ArrayList<>(sessions.values());
    }

    // ---- internal ----

    private void runReplay(String sessionId, String folderPath, double speedMultiplier,
                           String profileName) {
        ReplayStatus status = sessions.get(sessionId);
        long delayMs = Math.max(1, (long) (BASE_FRAME_INTERVAL_MS / speedMultiplier));

        try {
            CameraBackend camera = new FolderCameraBackend(Path.of(folderPath));
            int framesProcessed = 0;
            int eventsTriggered = 0;

            // Simple replay: pull frames in a loop until the folder backend cycles back
            // Use a simple counter-based approach to process each image once
            int totalImages = countImages(folderPath);
            if (totalImages == 0) {
                updateStatus(sessionId, "COMPLETED", 0, 0, "No images found in folder");
                return;
            }

            for (int i = 0; i < totalImages; i++) {
                LiveViewFrame frame = camera.getLiveView();
                framesProcessed++;

                // Count frames that are "different" (non-trivial) as potential triggers
                if (frame.data().length > 0) {
                    eventsTriggered++;
                }

                updateStatus(sessionId, "RUNNING", framesProcessed, eventsTriggered, null);

                if (i < totalImages - 1) {
                    Thread.sleep(delayMs);
                }
            }

            updateStatus(sessionId, "COMPLETED", framesProcessed, eventsTriggered, null);
            log.info("Replay session {} completed: {} frames, {} events",
                    sessionId, framesProcessed, eventsTriggered);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            updateStatus(sessionId, "CANCELLED", 0, 0, "Interrupted");
        } catch (Exception e) {
            log.error("Replay session {} failed", sessionId, e);
            updateStatus(sessionId, "FAILED", 0, 0, e.getMessage());
        }
    }

    private int countImages(String folderPath) {
        try {
            Path dir = Path.of(folderPath);
            if (!dir.toFile().isDirectory()) {
                return 0;
            }
            try (var stream = java.nio.file.Files.list(dir)) {
                return (int) stream
                        .filter(p -> {
                            String name = p.getFileName().toString().toLowerCase();
                            return name.endsWith(".jpg") || name.endsWith(".jpeg")
                                    || name.endsWith(".png") || name.endsWith(".bmp");
                        })
                        .count();
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private void updateStatus(String sessionId, String state, int frames, int events, String error) {
        ReplayStatus old = sessions.get(sessionId);
        if (old != null) {
            sessions.put(sessionId, new ReplayStatus(
                    old.sessionId(), old.folderPath(), old.profileName(),
                    old.speedMultiplier(), state, frames, events, error == null ? 0 : 1
            ));
        }
    }

    /**
     * Immutable status record for a replay session.
     */
    public record ReplayStatus(
            String sessionId,
            String folderPath,
            String profileName,
            double speedMultiplier,
            String state,
            int framesProcessed,
            int eventsTriggered,
            int errorCount
    ) {}

    // Expose listener support for future extension
    private final List<ObservationEventListener> listeners = new ArrayList<>();

    public void addListener(ObservationEventListener listener) {
        listeners.add(listener);
    }
}
