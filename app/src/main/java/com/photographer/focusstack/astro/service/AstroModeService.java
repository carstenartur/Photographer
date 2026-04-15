package com.photographer.focusstack.astro.service;

import com.photographer.focusstack.astro.AstroCapturePipeline;
import com.photographer.focusstack.astro.AstroFocusOptimizer;
import com.photographer.focusstack.astro.AstroStackingService;
import com.photographer.focusstack.astro.FrameRegistrationService;
import com.photographer.focusstack.astro.NoOpStarTrailDeblurService;
import com.photographer.focusstack.astro.StarDetectionService;
import com.photographer.focusstack.astro.entity.AstroSessionEntity;
import com.photographer.focusstack.astro.repository.AstroSessionRepository;
import com.photographer.focusstack.camera.backend.CameraBackendFactory;
import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.camera.CaptureResult;
import com.photographer.focusstack.common.camera.FocusCommand;
import com.photographer.focusstack.common.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AstroModeService {

    private static final Logger log = LoggerFactory.getLogger(AstroModeService.class);

    private final AstroSessionRepository repository;
    private final CameraBackendFactory cameraBackendFactory;
    private final String dataDir;

    /** Tracks sessions that have been requested to abort. */
    private final Map<String, Boolean> abortFlags = new ConcurrentHashMap<>();

    public AstroModeService(AstroSessionRepository repository, CameraBackendFactory cameraBackendFactory,
                            @org.springframework.beans.factory.annotation.Value("${astro.data-dir:data/astro-sessions}") String dataDir) {
        this.repository = repository;
        this.cameraBackendFactory = cameraBackendFactory;
        this.dataDir = dataDir;
    }

    /**
     * Returns a CameraBackend for astro sessions.
     * Uses a mock "astro" profile for the default backend from the factory.
     */
    private CameraBackend getDefaultBackend() {
        CameraProfile astroProfile = new CameraProfile(
                "astro-default", "Astro Camera", "mock", "localhost", 1, 1000, 0.0);
        return cameraBackendFactory.getBackend(astroProfile);
    }

    @Transactional
    public AstroSession createSession(AstroCaptureConfig captureConfig, AstroFocusConfig focusConfig) {
        AstroSessionEntity entity = AstroSessionEntity.create(captureConfig, focusConfig);
        entity.setOutputDirectory(Path.of(dataDir, entity.getId()).toString());
        entity = repository.save(entity);
        log.info("Created astro session {}", entity.getId());
        return entity.toRecord();
    }

    @Transactional(readOnly = true)
    public Optional<AstroSession> getSession(String id) {
        return repository.findById(id).map(AstroSessionEntity::toRecord);
    }

    @Transactional(readOnly = true)
    public List<AstroSession> listSessions() {
        return repository.findAllByOrderByCreatedAtDesc().stream()
                .map(AstroSessionEntity::toRecord)
                .toList();
    }

    /**
     * Returns terminal astro sessions that finished before the given cutoff.
     * Used for efficient retention cleanup without loading all sessions.
     */
    @Transactional(readOnly = true)
    public List<AstroSession> findTerminalSessionsBefore(Instant cutoff) {
        var terminalStatuses = java.util.Set.of(
                AstroSessionStatus.COMPLETED, AstroSessionStatus.FAILED, AstroSessionStatus.ABORTED);
        return repository.findByStatusInAndFinishedAtBefore(terminalStatuses, cutoff)
                .stream()
                .map(AstroSessionEntity::toRecord)
                .toList();
    }

    /**
     * Deletes an astro session and its associated files on disk.
     * Only terminal sessions (COMPLETED, FAILED, ABORTED) can be deleted.
     *
     * @param id the session id
     * @return {@code true} if the session was deleted, {@code false} if not found
     * @throws IllegalStateException if the session is still active
     */
    @Transactional
    public boolean deleteSession(String id) {
        return repository.findById(id).map(entity -> {
            AstroSessionStatus status = entity.getStatus();
            if (status != AstroSessionStatus.COMPLETED
                    && status != AstroSessionStatus.FAILED
                    && status != AstroSessionStatus.ABORTED) {
                throw new IllegalStateException(
                        "Cannot delete session in state " + status + ". Abort it first.");
            }
            // Clean up output directory on disk
            String outputDir = entity.getOutputDirectory();
            // Delete DB row first so if it fails we don't lose data
            repository.delete(entity);
            log.info("Deleted astro session {}", id);
            // Best-effort cleanup of output directory (after DB delete succeeds)
            if (outputDir != null) {
                try {
                    Path dir = Path.of(outputDir);
                    if (Files.exists(dir)) {
                        try (var walk = Files.walk(dir)) {
                            walk.sorted(java.util.Comparator.reverseOrder())
                                    .forEach(p -> {
                                        try { Files.deleteIfExists(p); }
                                        catch (Exception ex) {
                                            log.warn("Failed to delete {}: {}", p, ex.getMessage());
                                        }
                                    });
                        }
                    }
                } catch (IOException e) {
                    log.warn("Failed to clean up session directory {}: {}", outputDir, e.getMessage());
                }
            }
            return true;
        }).orElse(false);
    }

    public void requestAbort(String sessionId) {
        abortFlags.put(sessionId, true);
        log.info("Abort requested for astro session {}", sessionId);
    }

    /**
     * Executes the full astro session workflow.
     * Called via the bounded sessionTaskExecutor from the controller.
     */
    public void executeSession(String sessionId) {
        log.info("Starting astro session {}", sessionId);

        try {
            // Update status to PREPARING
            updateStatus(sessionId, AstroSessionStatus.PREPARING);

            AstroSessionEntity entity = repository.findById(sessionId).orElseThrow(
                    () -> new IllegalStateException("Session not found: " + sessionId));

            // Create output directories
            Path sessionDir = Path.of(entity.getOutputDirectory());
            Path framesDir = sessionDir.resolve("frames");
            Path analysisDir = sessionDir.resolve("analysis");
            Path resultDir = sessionDir.resolve("result");
            Path focusScanDir = sessionDir.resolve("focus-scan");
            Files.createDirectories(framesDir);
            Files.createDirectories(analysisDir);
            Files.createDirectories(resultDir);
            Files.createDirectories(focusScanDir);

            if (isAborted(sessionId)) return;

            CameraBackend cameraBackend = getDefaultBackend();

            // Focus strategy
            AstroFocusStrategy strategy = entity.getFocusStrategy();
            if (strategy == AstroFocusStrategy.FOCUS_SCAN) {
                executeFocusScan(entity, focusScanDir, cameraBackend);
            } else {
                log.info("Using MANUAL_FIXED focus strategy");
            }

            if (isAborted(sessionId)) return;

            // Capture frames
            updateStatus(sessionId, AstroSessionStatus.CAPTURING);
            List<BufferedImage> capturedImages = new ArrayList<>();
            int frameCount = entity.getFrameCount();

            for (int i = 0; i < frameCount; i++) {
                if (isAborted(sessionId)) return;

                log.info("Capturing frame {}/{} for session {}", i + 1, frameCount, sessionId);
                CaptureResult result = cameraBackend.capture();

                BufferedImage img = ImageIO.read(new ByteArrayInputStream(result.imageData()));
                if (img == null) {
                    log.warn("Frame {} returned null image, skipping", i);
                    continue;
                }
                capturedImages.add(img);

                // Save frame to disk
                Path framePath = framesDir.resolve("frame_" + String.format("%04d", i) + ".png");
                ImageIO.write(img, "PNG", framePath.toFile());

                if (entity.getDelayBetweenFramesMs() > 0 && i < frameCount - 1) {
                    Thread.sleep(entity.getDelayBetweenFramesMs());
                }
            }

            if (capturedImages.isEmpty()) {
                throw new IllegalStateException("No valid frames captured");
            }

            if (isAborted(sessionId)) return;

            // Process pipeline
            updateStatus(sessionId, AstroSessionStatus.ANALYZING);
            log.info("Analyzing {} frames for session {}", capturedImages.size(), sessionId);

            StarDetectionService starDetection = new StarDetectionService();
            FrameRegistrationService registration = new FrameRegistrationService();
            AstroStackingService stacking = new AstroStackingService();
            AstroCapturePipeline pipeline = new AstroCapturePipeline(
                    starDetection, registration, stacking, new NoOpStarTrailDeblurService());

            updateStatus(sessionId, AstroSessionStatus.STACKING);
            AstroCapturePipeline.PipelineResult pipelineResult = pipeline.process(capturedImages);

            // Log star counts
            for (AstroFrame frame : pipelineResult.frames()) {
                log.info("Frame #{}: {} stars, focusScore={}, used={}",
                        frame.frameIndex(), frame.starPoints().size(),
                        String.format("%.2f", frame.focusScore()), frame.usedInStack());
            }

            // Save result
            Path resultPath = resultDir.resolve("stacked_result.png");
            ImageIO.write(pipelineResult.stackedImage(), "PNG", resultPath.toFile());
            log.info("Stacked result saved to {}", resultPath);

            // Update session as completed
            completeSession(sessionId, resultPath.toString(), pipelineResult.summary());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failSession(sessionId, "Session interrupted");
        } catch (Exception e) {
            log.error("Astro session {} failed", sessionId, e);
            failSession(sessionId, e.getMessage());
        } finally {
            abortFlags.remove(sessionId);
        }
    }

    private void executeFocusScan(AstroSessionEntity entity, Path focusScanDir, CameraBackend cameraBackend) throws IOException {
        updateStatus(entity.getId(), AstroSessionStatus.FOCUS_SCANNING);
        log.info("Executing focus scan: {} steps, step size {}",
                entity.getScanSteps(), entity.getScanStepSize());

        StarDetectionService starDetection = new StarDetectionService();
        AstroFocusOptimizer optimizer = new AstroFocusOptimizer(starDetection);
        List<AstroFocusOptimizer.FocusEvaluation> evaluations = new ArrayList<>();

        for (int step = 0; step < entity.getScanSteps(); step++) {
            // Move focus
            FocusCommand.Direction dir = step < entity.getScanSteps() / 2
                    ? FocusCommand.Direction.NEAR : FocusCommand.Direction.FAR;
            cameraBackend.setFocus(new FocusCommand(dir, Math.abs(entity.getScanStepSize())));

            // Capture test frame
            CaptureResult result = cameraBackend.capture();
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(result.imageData()));
            if (img != null) {
                Path scanPath = focusScanDir.resolve("focus_scan_" + step + ".png");
                ImageIO.write(img, "PNG", scanPath.toFile());

                AstroFocusOptimizer.FocusEvaluation eval = optimizer.evaluate(img, step);
                evaluations.add(eval);
                log.info("Focus scan step {}: position={}, score={}, stars={}",
                        step, eval.focusPosition(), String.format("%.2f", eval.score()), eval.starCount());
            }
        }

        if (!evaluations.isEmpty()) {
            int bestPos = optimizer.selectBestPosition(evaluations);
            log.info("Best focus position: {}", bestPos);
            updateBestFocusPosition(entity.getId(), bestPos);
        }
    }

    private boolean isAborted(String sessionId) {
        if (Boolean.TRUE.equals(abortFlags.get(sessionId))) {
            repository.findById(sessionId).ifPresent(e -> {
                e.setStatus(AstroSessionStatus.ABORTED);
                e.setFinishedAt(Instant.now());
                e.setErrorMessage("Session aborted by user");
                repository.save(e);
            });
            log.info("Astro session {} aborted", sessionId);
            return true;
        }
        return false;
    }

    @Transactional
    void updateStatus(String sessionId, AstroSessionStatus status) {
        repository.findById(sessionId).ifPresent(e -> {
            e.setStatus(status);
            if (status == AstroSessionStatus.PREPARING) {
                e.setStartedAt(Instant.now());
            }
            repository.save(e);
        });
    }

    @Transactional
    void completeSession(String sessionId, String resultPath, String summary) {
        repository.findById(sessionId).ifPresent(e -> {
            e.setStatus(AstroSessionStatus.COMPLETED);
            e.setFinishedAt(Instant.now());
            e.setResultImagePath(resultPath);
            e.setStackingSummary(summary);
            repository.save(e);
        });
        log.info("Astro session {} completed", sessionId);
    }

    @Transactional
    void failSession(String sessionId, String error) {
        repository.findById(sessionId).ifPresent(e -> {
            e.setStatus(AstroSessionStatus.FAILED);
            e.setFinishedAt(Instant.now());
            e.setErrorMessage(error);
            repository.save(e);
        });
        log.error("Astro session {} failed: {}", sessionId, error);
    }

    @Transactional
    void updateBestFocusPosition(String sessionId, int position) {
        repository.findById(sessionId).ifPresent(e -> {
            e.setBestFocusPosition(position);
            repository.save(e);
        });
    }
}
