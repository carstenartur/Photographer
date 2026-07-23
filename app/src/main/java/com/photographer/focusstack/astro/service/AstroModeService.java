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
import com.photographer.focusstack.common.model.AstroCaptureConfig;
import com.photographer.focusstack.common.model.AstroFocusConfig;
import com.photographer.focusstack.common.model.AstroFocusStrategy;
import com.photographer.focusstack.common.model.AstroSession;
import com.photographer.focusstack.common.model.AstroSessionStatus;
import com.photographer.focusstack.common.model.CameraProfile;
import com.photographer.focusstack.session.CameraBackendProvider;
import com.photographer.focusstack.session.CameraBackendSession;
import com.photographer.focusstack.session.CameraSessionPurpose;
import com.photographer.focusstack.session.SessionException;
import com.photographer.focusstack.session.SessionUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class AstroModeService {

    private static final Logger log = LoggerFactory.getLogger(AstroModeService.class);
    private static final int SESSION_LOCK_STRIPES = 64;
    private static final String PUBLIC_EXECUTION_FAILURE = "Astro session execution failed";
    private static final String PUBLIC_SCHEDULING_FAILURE = "Astro session scheduling failed";

    private final AstroSessionRepository repository;
    private final CameraBackendFactory cameraBackendFactory;
    private final CameraBackendProvider backendProvider;
    private final String dataDir;
    private final ConcurrentMap<String, PreparedExecution> activeExecutions =
            new ConcurrentHashMap<>();
    private final AtomicBoolean shutdownRequested = new AtomicBoolean();
    private final Object[] sessionLocks = createSessionLocks();

    @Autowired
    public AstroModeService(
            AstroSessionRepository repository,
            CameraBackendFactory cameraBackendFactory,
            CameraBackendProvider backendProvider,
            @Value("${astro.data-dir:data/astro-sessions}") String dataDir) {
        this.repository = repository;
        this.cameraBackendFactory = cameraBackendFactory;
        this.backendProvider = backendProvider;
        this.dataDir = dataDir;
    }

    /** Backward-compatible constructor for focused tests using the default mock backend. */
    public AstroModeService(
            AstroSessionRepository repository,
            CameraBackendFactory cameraBackendFactory,
            String dataDir) {
        this(repository, cameraBackendFactory, null, dataDir);
    }

    private static Object[] createSessionLocks() {
        Object[] locks = new Object[SESSION_LOCK_STRIPES];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new Object();
        }
        return locks;
    }

    private Object sessionLock(String sessionId) {
        return sessionLocks[Math.floorMod(sessionId.hashCode(), sessionLocks.length)];
    }

    @Transactional
    public AstroSession createSession(
            AstroCaptureConfig captureConfig,
            AstroFocusConfig focusConfig) {
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

    @Transactional(readOnly = true)
    public List<AstroSession> findTerminalSessionsBefore(Instant cutoff) {
        Set<AstroSessionStatus> terminalStatuses = Set.of(
                AstroSessionStatus.COMPLETED,
                AstroSessionStatus.FAILED,
                AstroSessionStatus.ABORTED);
        return repository.findByStatusInAndFinishedAtBefore(terminalStatuses, cutoff)
                .stream()
                .map(AstroSessionEntity::toRecord)
                .toList();
    }

    @Transactional
    public boolean deleteSession(String id) {
        synchronized (sessionLock(id)) {
            if (activeExecutions.containsKey(id)) {
                throw new IllegalStateException("Cannot delete an active astro session");
            }
            return repository.findById(id).map(entity -> {
                AstroSessionStatus status = entity.getStatus();
                if (status != AstroSessionStatus.COMPLETED
                        && status != AstroSessionStatus.FAILED
                        && status != AstroSessionStatus.ABORTED) {
                    throw new IllegalStateException(
                            "Cannot delete session in state " + status + ". Abort it first.");
                }
                String outputDir = entity.getOutputDirectory();
                repository.delete(entity);
                log.info("Deleted astro session {}", id);
                cleanupOutputDirectory(outputDir);
                return true;
            }).orElse(false);
        }
    }

    private void cleanupOutputDirectory(String outputDir) {
        if (outputDir == null) {
            return;
        }
        try {
            Path directory = Path.of(outputDir);
            if (Files.exists(directory)) {
                try (var walk = Files.walk(directory)) {
                    walk.sorted(java.util.Comparator.reverseOrder())
                            .forEach(path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (Exception failure) {
                                    log.warn("Failed to delete astro session output");
                                }
                            });
                }
            }
        } catch (Exception failure) {
            log.warn("Failed to clean astro session output directory");
        }
    }

    /**
     * Acquires the camera and records one queued execution before scheduling.
     * The caller must submit the returned runnable or invoke {@link PreparedExecution#reject}.
     */
    public PreparedExecution prepareExecution(String sessionId) {
        synchronized (sessionLock(sessionId)) {
            ensureCameraServiceOpen();
            AstroSessionEntity entity = requireEntity(sessionId);
            if (entity.getStatus() != AstroSessionStatus.CREATED) {
                throw new IllegalStateException(
                        "Astro session cannot start in state " + entity.getStatus());
            }
            if (activeExecutions.containsKey(sessionId)) {
                throw new IllegalStateException("Astro session already has an active execution");
            }

            CameraBackendSession cameraSession = openCameraSession(entity);
            PreparedExecution execution = new PreparedExecution(
                    this, sessionId, cameraSession);
            PreparedExecution previous = activeExecutions.putIfAbsent(
                    sessionId, execution);
            if (previous != null) {
                cameraSession.close();
                throw new IllegalStateException("Astro session already has an active execution");
            }
            try {
                setStatus(entity, AstroSessionStatus.PREPARING, true);
                return execution;
            } catch (RuntimeException failure) {
                activeExecutions.remove(sessionId, execution);
                cameraSession.close();
                throw failure;
            }
        }
    }

    /** Legacy synchronous entry point using the same prepared ownership contract. */
    public void executeSession(String sessionId) {
        prepareExecution(sessionId).run();
    }

    private CameraBackendSession openCameraSession(AstroSessionEntity entity) {
        String cameraProfileId = entity.getCameraProfileId();
        if (cameraProfileId != null && !cameraProfileId.isBlank()) {
            if (backendProvider == null) {
                throw new SessionException("Camera session provider is unavailable");
            }
            return backendProvider.openSession(
                    cameraProfileId,
                    CameraSessionPurpose.ASTRO,
                    entity.getId());
        }

        CameraProfile mockProfile = new CameraProfile(
                "astro-default", "Astro Camera", "mock", "localhost",
                1, 1000, 0.0);
        return CameraBackendSession.unmanaged(
                cameraBackendFactory.getBackend(mockProfile));
    }

    public void requestCameraShutdown() {
        if (!shutdownRequested.compareAndSet(false, true)) {
  return;
        }
        List.copyOf(activeExecutions.keySet()).forEach(this::requestAbort);
    }

    public int activeCameraExecutions() {
        return activeExecutions.size();
    }

    private void ensureCameraServiceOpen() {
        if (shutdownRequested.get()) {
            throw new SessionUnavailableException();
        }
    }

    public void requestAbort(String sessionId) {
        synchronized (sessionLock(sessionId)) {
            PreparedExecution execution = activeExecutions.get(sessionId);
            AstroSessionEntity entity = repository.findById(sessionId).orElse(null);
            if (execution == null) {
                if (entity != null && !isTerminal(entity.getStatus())) {
                    markAborted(entity);
                }
                return;
            }
            if (execution.cancelBeforeStart()) {
                activeExecutions.remove(sessionId, execution);
                if (entity != null) {
                    markAborted(entity);
                }
                execution.closeCamera();
            } else {
                execution.requestAbort();
            }
        }
        log.info("Abort requested for an active astro session");
    }

    private void runPrepared(PreparedExecution execution) {
        String sessionId = execution.sessionId;
        try {
            AstroSessionEntity entity = requireEntity(sessionId);
            createOutputDirectories(entity);
            if (execution.abortRequested()) {
                markAborted(entity);
                return;
            }

            CameraBackend cameraBackend = execution.cameraSession.backend();
            if (entity.getFocusStrategy() == AstroFocusStrategy.FOCUS_SCAN) {
                executeFocusScan(
                        entity,
                        Path.of(entity.getOutputDirectory()).resolve("focus-scan"),
                        cameraBackend,
                        execution);
            } else {
                log.info("Using MANUAL_FIXED focus strategy for astro session {}", sessionId);
            }

            if (abortIfRequested(entity, execution)) {
                return;
            }

            setStatus(entity, AstroSessionStatus.CAPTURING, false);
            List<BufferedImage> capturedImages = captureFrames(
                    entity,
                    Path.of(entity.getOutputDirectory()).resolve("frames"),
                    cameraBackend,
                    execution);

            if (abortIfRequested(entity, execution)) {
                return;
            }
            if (capturedImages.isEmpty()) {
                throw new IllegalStateException("No valid frames captured");
            }

            setStatus(entity, AstroSessionStatus.ANALYZING, false);
            StarDetectionService starDetection = new StarDetectionService();
            FrameRegistrationService registration = new FrameRegistrationService();
            AstroStackingService stacking = new AstroStackingService();
            AstroCapturePipeline pipeline = new AstroCapturePipeline(
                    starDetection,
                    registration,
                    stacking,
                    new NoOpStarTrailDeblurService());

            setStatus(entity, AstroSessionStatus.STACKING, false);
            AstroCapturePipeline.PipelineResult pipelineResult =
                    pipeline.process(capturedImages);
            Path resultPath = Path.of(entity.getOutputDirectory())
                    .resolve("result")
                    .resolve("stacked_result.png");
            ImageIO.write(pipelineResult.stackedImage(), "PNG", resultPath.toFile());
            completeSession(entity, resultPath.toString(), pipelineResult.summary());
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            failOrAbort(execution, PUBLIC_EXECUTION_FAILURE);
        } catch (Exception failure) {
            log.error("Astro session {} failed with {}",
                    sessionId, failure.getClass().getSimpleName());
            failOrAbort(execution, PUBLIC_EXECUTION_FAILURE);
        }
    }

    private void createOutputDirectories(AstroSessionEntity entity) throws IOException {
        Path sessionDirectory = Path.of(entity.getOutputDirectory());
        Files.createDirectories(sessionDirectory.resolve("frames"));
        Files.createDirectories(sessionDirectory.resolve("analysis"));
        Files.createDirectories(sessionDirectory.resolve("result"));
        Files.createDirectories(sessionDirectory.resolve("focus-scan"));
    }

    private List<BufferedImage> captureFrames(
            AstroSessionEntity entity,
            Path framesDirectory,
            CameraBackend cameraBackend,
            PreparedExecution execution) throws Exception {
        List<BufferedImage> capturedImages = new ArrayList<>();
        for (int index = 0; index < entity.getFrameCount(); index++) {
            if (execution.abortRequested()) {
                break;
            }
            log.info("Capturing astro frame {}/{} for session {}",
                    index + 1, entity.getFrameCount(), entity.getId());
            CaptureResult result = cameraBackend.capture();
            BufferedImage image = image(result);
            if (image == null) {
                log.warn("Astro frame {} contained no decodable image", index);
                continue;
            }
            capturedImages.add(image);
            Path framePath = framesDirectory.resolve(
                    "frame_" + String.format("%04d", index) + ".png");
            ImageIO.write(image, "PNG", framePath.toFile());

            if (entity.getDelayBetweenFramesMs() > 0
                    && index < entity.getFrameCount() - 1
                    && !execution.abortRequested()) {
                Thread.sleep(entity.getDelayBetweenFramesMs());
            }
        }
        return capturedImages;
    }

    private static BufferedImage image(CaptureResult result) throws IOException {
        if (result == null || result.imageData() == null || result.imageData().length == 0) {
            return null;
        }
        return ImageIO.read(new ByteArrayInputStream(result.imageData()));
    }

    private void executeFocusScan(
            AstroSessionEntity entity,
            Path focusScanDirectory,
            CameraBackend cameraBackend,
            PreparedExecution execution) throws IOException {
        setStatus(entity, AstroSessionStatus.FOCUS_SCANNING, false);
        StarDetectionService starDetection = new StarDetectionService();
        AstroFocusOptimizer optimizer = new AstroFocusOptimizer(starDetection);
        List<AstroFocusOptimizer.FocusEvaluation> evaluations = new ArrayList<>();

        for (int step = 0; step < entity.getScanSteps(); step++) {
            if (execution.abortRequested()) {
                break;
            }
            FocusCommand.Direction direction = step < entity.getScanSteps() / 2
                    ? FocusCommand.Direction.NEAR
                    : FocusCommand.Direction.FAR;
            cameraBackend.setFocus(new FocusCommand(
                    direction, Math.abs(entity.getScanStepSize())));
            CaptureResult result = cameraBackend.capture();
            BufferedImage image = image(result);
            if (image != null) {
                Path scanPath = focusScanDirectory.resolve(
                        "focus_scan_" + step + ".png");
                ImageIO.write(image, "PNG", scanPath.toFile());
                evaluations.add(optimizer.evaluate(image, step));
            }
        }

        if (!evaluations.isEmpty() && !execution.abortRequested()) {
            updateBestFocusPosition(
                    entity.getId(), optimizer.selectBestPosition(evaluations));
        }
    }

    private boolean abortIfRequested(
            AstroSessionEntity entity,
            PreparedExecution execution) {
        if (!execution.abortRequested()) {
            return false;
        }
        markAborted(entity);
        return true;
    }

    private void failOrAbort(PreparedExecution execution, String publicFailure) {
        synchronized (sessionLock(execution.sessionId)) {
            AstroSessionEntity entity = requireEntity(execution.sessionId);
            if (execution.abortRequested()) {
                markAborted(entity);
            } else {
                failSession(entity, publicFailure);
            }
        }
    }

    private void rejectExecution(
            PreparedExecution execution,
            RuntimeException failure) {
        synchronized (sessionLock(execution.sessionId)) {
            if (activeExecutions.remove(execution.sessionId, execution)) {
                AstroSessionEntity entity = requireEntity(execution.sessionId);
                failSession(entity, PUBLIC_SCHEDULING_FAILURE);
            }
        }
        execution.closeCamera();
    }

    private void finishExecution(PreparedExecution execution) {
        activeExecutions.remove(execution.sessionId, execution);
        execution.closeCamera();
    }

    private AstroSessionEntity requireEntity(String sessionId) {
        return repository.findById(sessionId).orElseThrow(
                () -> new IllegalStateException("Astro session not found"));
    }

    private void setStatus(
            AstroSessionEntity entity,
            AstroSessionStatus status,
            boolean start) {
        entity.setStatus(status);
        if (start) {
            entity.setStartedAt(Instant.now());
        }
        repository.save(entity);
    }

    private void markAborted(AstroSessionEntity entity) {
        entity.setStatus(AstroSessionStatus.ABORTED);
        entity.setFinishedAt(Instant.now());
        entity.setErrorMessage("Session aborted by user");
        repository.save(entity);
        log.info("Astro session {} aborted", entity.getId());
    }

    private void completeSession(
            AstroSessionEntity entity,
            String resultPath,
            String summary) {
        entity.setStatus(AstroSessionStatus.COMPLETED);
        entity.setFinishedAt(Instant.now());
        entity.setResultImagePath(resultPath);
        entity.setStackingSummary(summary);
        entity.setErrorMessage(null);
        repository.save(entity);
        log.info("Astro session {} completed", entity.getId());
    }

    private void failSession(AstroSessionEntity entity, String publicError) {
        entity.setStatus(AstroSessionStatus.FAILED);
        entity.setFinishedAt(Instant.now());
        entity.setErrorMessage(publicError);
        repository.save(entity);
        log.error("Astro session {} failed", entity.getId());
    }

    private static boolean isTerminal(AstroSessionStatus status) {
        return status == AstroSessionStatus.COMPLETED
                || status == AstroSessionStatus.FAILED
                || status == AstroSessionStatus.ABORTED;
    }

    @Transactional
    void updateBestFocusPosition(String sessionId, int position) {
        repository.findById(sessionId).ifPresent(entity -> {
            entity.setBestFocusPosition(position);
            repository.save(entity);
        });
    }

    private enum ExecutionState {
        QUEUED,
        RUNNING,
        CANCELLED,
        FINISHED
    }

    public static final class PreparedExecution implements Runnable {
        private final AstroModeService owner;
        private final String sessionId;
        private final CameraBackendSession cameraSession;
        private final AtomicReference<ExecutionState> state =
                new AtomicReference<>(ExecutionState.QUEUED);
        private final AtomicBoolean abortRequested = new AtomicBoolean();

        private PreparedExecution(
                AstroModeService owner,
                String sessionId,
                CameraBackendSession cameraSession) {
            this.owner = owner;
            this.sessionId = sessionId;
            this.cameraSession = cameraSession;
        }

        public String sessionId() {
            return sessionId;
        }

        @Override
        public void run() {
            if (!state.compareAndSet(ExecutionState.QUEUED, ExecutionState.RUNNING)) {
                return;
            }
            try {
                owner.runPrepared(this);
            } finally {
                state.set(ExecutionState.FINISHED);
                owner.finishExecution(this);
            }
        }

        public void reject(RuntimeException failure) {
            if (failure == null) {
                throw new IllegalArgumentException("failure is required");
            }
            if (state.compareAndSet(ExecutionState.QUEUED, ExecutionState.CANCELLED)) {
                owner.rejectExecution(this, failure);
            }
        }

        private boolean cancelBeforeStart() {
            return state.compareAndSet(ExecutionState.QUEUED, ExecutionState.CANCELLED);
        }

        private void requestAbort() {
            abortRequested.set(true);
        }

        private boolean abortRequested() {
            return abortRequested.get();
        }

        private void closeCamera() {
            cameraSession.close();
        }
    }
}
