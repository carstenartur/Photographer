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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service that replays stored image series from a folder for testing trigger strategies
 * and comparing AI providers.
 *
 * <p>A replay session reads JPEG/PNG images from a folder via {@link FolderCameraBackend},
 * iterates through them at a configurable speed, and records progress counters.
 * The resulting frame counts are available via {@link #getStatus(String)}.</p>
 *
 * <p>Speed is controlled via {@code speedMultiplier}: 1.0 = one frame every 500 ms,
 * 2.0 = twice as fast, etc. {@code speedMultiplier} must be positive.</p>
 *
 * <p>To evaluate trigger strategies or run AI comparison, register an
 * {@link ObservationEventListener} via {@link #addListener} and wire the replay
 * output to an {@link ObserverPipeline} externally.</p>
 */
@Service
public class ReplayService {

    private static final Logger log = LoggerFactory.getLogger(ReplayService.class);

    /** Default interval between replay frames in milliseconds. */
    private static final long BASE_FRAME_INTERVAL_MS = 500;

    /**
     * Bounded thread pool for replay sessions. Replaces the previous
     * {@code Executors.newCachedThreadPool()} which allowed unbounded thread
     * growth and could exhaust system resources under burst load. The bounded
     * pool keeps a small core, scales up to {@value #MAX_REPLAY_THREADS}
     * concurrent replays, and queues additional requests.
     */
    private static final int MAX_REPLAY_THREADS = 8;
    private static final int REPLAY_QUEUE_CAPACITY = 32;

    private final ExecutorService executor = new ThreadPoolExecutor(
            2,
            MAX_REPLAY_THREADS,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(REPLAY_QUEUE_CAPACITY),
            new ThreadFactoryWithName("replay-"),
            new ThreadPoolExecutor.CallerRunsPolicy());

    private static final class ThreadFactoryWithName implements java.util.concurrent.ThreadFactory {
        private final String prefix;
        private final AtomicInteger counter = new AtomicInteger();
        ThreadFactoryWithName(String prefix) { this.prefix = prefix; }
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }

    /** Active replay sessions keyed by session ID. */
    private final Map<String, ReplayStatus> sessions = new ConcurrentHashMap<>();

    /**
     * Starts a replay session asynchronously.
     *
     * @param folderPath      path to the folder containing replay images
     * @param speedMultiplier 1.0 = real-time, 2.0 = double speed, etc. Must be positive.
     * @param profileName     observer profile name to use during replay
     * @return a {@link ReplayStatus} describing the started session
     * @throws IllegalArgumentException if {@code speedMultiplier} is not positive
     */
    public ReplayStatus startReplay(String folderPath, double speedMultiplier, String profileName) {
        if (speedMultiplier <= 0) {
            throw new IllegalArgumentException("speedMultiplier must be positive, got: " + speedMultiplier);
        }
        String sessionId = java.util.UUID.randomUUID().toString();
        ReplayStatus status = new ReplayStatus(sessionId, folderPath, profileName,
                speedMultiplier, "RUNNING", 0, 0, 0, null);
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
            int nonEmptyFrames = 0;

            int totalImages = countImages(folderPath);
            if (totalImages == 0) {
                updateStatus(sessionId, "COMPLETED", 0, 0, "No images found in folder");
                return;
            }

            for (int i = 0; i < totalImages; i++) {
                LiveViewFrame frame = camera.getLiveView();
                framesProcessed++;

                if (frame.data().length > 0) {
                    nonEmptyFrames++;
                }

                updateStatus(sessionId, "RUNNING", framesProcessed, nonEmptyFrames, null);

                if (i < totalImages - 1) {
                    Thread.sleep(delayMs);
                }
            }

            updateStatus(sessionId, "COMPLETED", framesProcessed, nonEmptyFrames, null);
            log.info("Replay session {} completed: {} frames read, {} non-empty",
                    sessionId, framesProcessed, nonEmptyFrames);

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

    private void updateStatus(String sessionId, String state, int frames, int nonEmptyFrames, String error) {
        ReplayStatus old = sessions.get(sessionId);
        if (old != null) {
            int updatedErrorCount = old.errorCount() + (error == null ? 0 : 1);
            sessions.put(sessionId, new ReplayStatus(
                    old.sessionId(), old.folderPath(), old.profileName(),
                    old.speedMultiplier(), state, frames, nonEmptyFrames,
                    updatedErrorCount, error != null ? error : old.lastError()
            ));
        }
    }

    /**
     * Immutable status record for a replay session.
     *
     * @param framesProcessed total frames read from the folder
     * @param nonEmptyFrames  number of frames containing image data (not empty)
     * @param errorCount      cumulative number of errors encountered during replay
     * @param lastError       message of the most recent error, or {@code null}
     */
    public record ReplayStatus(
            String sessionId,
            String folderPath,
            String profileName,
            double speedMultiplier,
            String state,
            int framesProcessed,
            int nonEmptyFrames,
            int errorCount,
            String lastError
    ) {}

    // Expose listener support for future extension
    private final List<ObservationEventListener> listeners = new ArrayList<>();

    public void addListener(ObservationEventListener listener) {
        listeners.add(listener);
    }
}
