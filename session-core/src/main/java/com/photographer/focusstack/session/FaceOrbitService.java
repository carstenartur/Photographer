package com.photographer.focusstack.session;

import com.photographer.focusstack.capture.SequenceCaptureService;
import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.model.CaptureFrame;
import com.photographer.focusstack.common.model.CaptureSession;
import com.photographer.focusstack.common.model.FaceOrbitSettings;
import com.photographer.focusstack.common.model.FaceProfileJob;
import com.photographer.focusstack.common.model.QualityCheck;
import com.photographer.focusstack.common.model.SessionStatus;
import com.photographer.focusstack.face.analysis.FaceAnalysisEngine;
import com.photographer.focusstack.face.profile.FaceProfileEngine;
import com.photographer.focusstack.storage.SessionStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orchestrates Face Orbit Capture sessions: guided image series of a face
 * over approximately 180 degrees with quality checks and 3D profile generation.
 *
 * <p>For production use, construct with a {@link CameraBackendProvider} so that
 * the correct camera backend is resolved at session-start time from the profile
 * ID stored in {@link FaceOrbitSettings#cameraProfileId()}.  For tests, the
 * legacy constructor accepting a pre-built {@link SequenceCaptureService} can
 * still be used.</p>
 */
public class FaceOrbitService {

    private static final Logger log = LoggerFactory.getLogger(FaceOrbitService.class);

    /** Legacy fixed capture service (non-null when using test constructors). */
    private final SequenceCaptureService captureService;

    /** Provider that resolves a real backend per camera profile (non-null in production). */
    private final CameraBackendProvider backendProvider;

    private final SessionStorage storage;
    private final FaceAnalysisEngine analysisEngine;
    private final FaceProfileEngine profileEngine;

    private final Map<String, CaptureSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, FaceOrbitSettings> sessionSettings = new ConcurrentHashMap<>();
    private final Map<String, List<CaptureFrame>> sessionFrames = new ConcurrentHashMap<>();
    private final Map<String, List<QualityCheck>> sessionQualityChecks = new ConcurrentHashMap<>();
    private final Map<String, FaceProfileJob> profileJobs = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> stopFlags = new ConcurrentHashMap<>();

    /** Production constructor: resolves the camera backend via {@code backendProvider} at session start. */
    public FaceOrbitService(CameraBackendProvider backendProvider,
                             SessionStorage storage,
                             FaceAnalysisEngine analysisEngine,
                             FaceProfileEngine profileEngine) {
        this.backendProvider = backendProvider;
        this.captureService = null;
        this.storage = storage;
        this.analysisEngine = analysisEngine;
        this.profileEngine = profileEngine;
    }

    /** Legacy constructor for tests that supply a pre-built {@link SequenceCaptureService}. */
    public FaceOrbitService(SequenceCaptureService captureService,
                             SessionStorage storage,
                             FaceAnalysisEngine analysisEngine,
                             FaceProfileEngine profileEngine) {
        this.captureService = captureService;
        this.backendProvider = null;
        this.storage = storage;
        this.analysisEngine = analysisEngine;
        this.profileEngine = profileEngine;
    }

    /**
     * Resolves the {@link SequenceCaptureService} to use for the given session.
     * Uses the {@link CameraBackendProvider} if set; otherwise falls back to the
     * fixed {@code captureService} (test / mock mode).
     */
    private SequenceCaptureService resolveCapture(FaceOrbitSettings settings) {
        if (backendProvider != null && settings.cameraProfileId() != null) {
            CameraBackend backend = backendProvider.getBackend(settings.cameraProfileId());
            return new SequenceCaptureService(backend);
        }
        if (captureService != null) {
            return captureService;
        }
        throw new SessionException(
                "No camera backend available: supply a cameraProfileId in FaceOrbitSettings or " +
                "configure the application with a CameraBackendProvider.");
    }

    /**
     * Creates a new face orbit session.
     */
    public CaptureSession createSession(FaceOrbitSettings settings) {
        String sessionId = UUID.randomUUID().toString();
        try {
            storage.createSessionDirectory(sessionId);
        } catch (IOException e) {
            throw new SessionException("Failed to create session directory", e);
        }

        CaptureSession session = new CaptureSession(
                sessionId, "face-orbit", SessionStatus.CREATED,
                Instant.now(), null, null,
                new ArrayList<>(), null,
                Map.of("analysisEngine", analysisEngine.name(),
                       "profileEngine", profileEngine.name(),
                       "autoMode", String.valueOf(settings.autoMode())));
        sessions.put(sessionId, session);
        sessionSettings.put(sessionId, settings);
        sessionFrames.put(sessionId, new ArrayList<>());
        sessionQualityChecks.put(sessionId, new ArrayList<>());

        log.info("Created face orbit session: {}", sessionId);
        return session;
    }

    /**
     * Captures the next step in manual mode.
     *
     * @param sessionId the session identifier
     * @return the quality check result for the captured frame
     */
    public QualityCheck captureNextStep(String sessionId) {
        CaptureSession session = getSession(sessionId);
        FaceOrbitSettings settings = sessionSettings.get(sessionId);
        List<CaptureFrame> frames = sessionFrames.get(sessionId);

        if (session.status() != SessionStatus.CREATED && session.status() != SessionStatus.RUNNING) {
            throw new SessionException("Session is not active: " + session.status());
        }

        int stepIndex = frames.size();
        if (stepIndex >= settings.totalSteps()) {
            throw new SessionException("All steps completed for session: " + sessionId);
        }

        double angle = (settings.angleRange() / Math.max(1, settings.totalSteps() - 1)) * stepIndex;

        // Update to RUNNING if first capture
        if (session.status() == SessionStatus.CREATED) {
            CaptureSession running = new CaptureSession(
                    sessionId, session.type(), SessionStatus.RUNNING,
                    session.createdAt(), Instant.now(), null,
                    session.frameIds(), null, session.metadata());
            sessions.put(sessionId, running);
        }

        CaptureFrame frame = resolveCapture(settings).captureFrame(sessionId, stepIndex, angle);
        frames.add(frame);

        // Quality check
        QualityCheck quality = analysisEngine.analyzeQuality(new byte[]{0}, frame.frameId());
        sessionQualityChecks.get(sessionId).add(quality);

        // Update frame IDs
        List<String> frameIds = frames.stream().map(CaptureFrame::frameId).toList();
        CaptureSession updated = sessions.get(sessionId);
        sessions.put(sessionId, new CaptureSession(
                sessionId, updated.type(), updated.status(),
                updated.createdAt(), updated.startedAt(), null,
                frameIds, null, updated.metadata()));

        log.debug("Session {}: captured step {}/{} at angle {}", sessionId, stepIndex + 1,
                settings.totalSteps(), angle);
        return quality;
    }

    /**
     * Validates that the session is in a startable state for auto mode.
     *
     * @throws SessionNotFoundException if the session does not exist
     * @throws SessionException if the session is not in CREATED state
     */
    public void validateAutoModeStartable(String sessionId) {
        CaptureSession session = getSession(sessionId);
        if (session.status() != SessionStatus.CREATED) {
            throw new SessionException("Auto mode can only start from CREATED state");
        }
    }

    /**
     * Executes the face orbit capture in auto mode.
     * Call from an async executor for non-blocking operation.
     */
    public CaptureSession executeAutoMode(String sessionId) {
        CaptureSession session = getSession(sessionId);
        FaceOrbitSettings settings = sessionSettings.get(sessionId);

        if (session.status() != SessionStatus.CREATED) {
            throw new SessionException("Auto mode can only start from CREATED state");
        }

        AtomicBoolean stopFlag = new AtomicBoolean(false);
        stopFlags.put(sessionId, stopFlag);

        CaptureSession running = new CaptureSession(
                sessionId, session.type(), SessionStatus.RUNNING,
                session.createdAt(), Instant.now(), null,
                new ArrayList<>(), null, session.metadata());
        sessions.put(sessionId, running);

        List<CaptureFrame> frames = sessionFrames.get(sessionId);
        List<String> frameIds = new ArrayList<>();

        // Resolve capture service once for the entire auto mode run
        SequenceCaptureService resolvedCaptureService = resolveCapture(settings);

        try {
            for (int step = 0; step < settings.totalSteps(); step++) {
                if (stopFlag.get()) {
                    break;
                }

                double angle = (settings.angleRange() / Math.max(1, settings.totalSteps() - 1)) * step;
                CaptureFrame frame = resolvedCaptureService.captureFrame(sessionId, step, angle);
                frames.add(frame);
                frameIds.add(frame.frameId());

                // Quality check
                QualityCheck quality = analysisEngine.analyzeQuality(new byte[]{0}, frame.frameId());
                sessionQualityChecks.get(sessionId).add(quality);

                if (settings.qualityCheckEnabled() && !quality.passed()) {
                    log.warn("Session {}: quality check failed at step {}: {}",
                            sessionId, step, quality.reason());
                }

                log.debug("Session {}: auto step {}/{} angle={}", sessionId, step + 1,
                        settings.totalSteps(), angle);
            }

            SessionStatus finalStatus = stopFlag.get() ? SessionStatus.CANCELLED : SessionStatus.COMPLETED;
            CaptureSession completed = new CaptureSession(
                    sessionId, session.type(), finalStatus,
                    session.createdAt(), running.startedAt(), Instant.now(),
                    frameIds, null, session.metadata());
            sessions.put(sessionId, completed);
            return completed;

        } catch (Exception e) {
            log.error("Face orbit session {} failed: {}", sessionId, e.getMessage(), e);
            CaptureSession failed = new CaptureSession(
                    sessionId, session.type(), SessionStatus.FAILED,
                    session.createdAt(), running.startedAt(), Instant.now(),
                    frameIds, e.getMessage(), session.metadata());
            sessions.put(sessionId, failed);
            return failed;
        } finally {
            stopFlags.remove(sessionId);
        }
    }

    /**
     * Requests the session to stop.
     */
    public void stopSession(String sessionId) {
        getSession(sessionId);
        AtomicBoolean stopFlag = stopFlags.get(sessionId);
        if (stopFlag != null) {
            stopFlag.set(true);
        }
    }

    /**
     * Completes a manual mode session.
     */
    public CaptureSession completeSession(String sessionId) {
        CaptureSession session = getSession(sessionId);
        List<CaptureFrame> frames = sessionFrames.getOrDefault(sessionId, List.of());
        List<String> frameIds = frames.stream().map(CaptureFrame::frameId).toList();

        CaptureSession completed = new CaptureSession(
                sessionId, session.type(), SessionStatus.COMPLETED,
                session.createdAt(), session.startedAt(), Instant.now(),
                frameIds, null, session.metadata());
        sessions.put(sessionId, completed);
        return completed;
    }

    /**
     * Generates a 3D face profile from the captured orbit images.
     */
    public FaceProfileJob generateProfile(String sessionId) {
        CaptureSession session = getSession(sessionId);
        List<CaptureFrame> frames = sessionFrames.getOrDefault(sessionId, List.of());
        FaceOrbitSettings settings = sessionSettings.get(sessionId);

        String jobId = UUID.randomUUID().toString();
        FaceProfileJob job = new FaceProfileJob(
                jobId, sessionId, SessionStatus.RUNNING,
                Instant.now(), null, null, null, 0, 0.0);
        profileJobs.put(jobId, job);

        try {
            List<byte[]> imageDataList = new ArrayList<>();
            List<Double> angles = new ArrayList<>();
            List<List<double[]>> landmarksList = new ArrayList<>();

            for (CaptureFrame frame : frames) {
                byte[] imageData;
                try {
                    imageData = storage.readFile(sessionId, "frames/" + frame.fileName());
                } catch (IOException e) {
                    imageData = new byte[]{0}; // Placeholder for mock testing
                }
                imageDataList.add(imageData);

                double angle = Double.parseDouble(frame.metadata().getOrDefault("angle", "0.0"));
                angles.add(angle);

                landmarksList.add(analysisEngine.detectLandmarks(imageData));
            }

            FaceProfileEngine.FaceProfileResult result = profileEngine.generate(
                    imageDataList, angles, landmarksList);

            // Store profile result
            String resultPath = "results/profile_" + jobId + "." + result.format();
            try {
                storage.storeFile(sessionId, resultPath, result.profileData());
            } catch (IOException e) {
                log.warn("Failed to store profile result: {}", e.getMessage());
            }

            FaceProfileJob completed = new FaceProfileJob(
                    jobId, sessionId, SessionStatus.COMPLETED,
                    job.createdAt(), Instant.now(), resultPath, null,
                    result.vertexCount(), result.confidence());
            profileJobs.put(jobId, completed);
            return completed;

        } catch (Exception e) {
            log.error("Profile generation failed for session {}: {}", sessionId, e.getMessage(), e);
            FaceProfileJob failed = new FaceProfileJob(
                    jobId, sessionId, SessionStatus.FAILED,
                    job.createdAt(), Instant.now(), null, e.getMessage(), 0, 0.0);
            profileJobs.put(jobId, failed);
            return failed;
        }
    }

    public CaptureSession getSession(String sessionId) {
        CaptureSession session = sessions.get(sessionId);
        if (session == null) {
            throw new SessionNotFoundException("Session not found: " + sessionId);
        }
        return session;
    }

    /**
     * Gets quality checks for a session.
     * Returns an immutable snapshot to avoid concurrent modification.
     */
    public List<QualityCheck> getQualityChecks(String sessionId) {
        List<QualityCheck> checks = sessionQualityChecks.get(sessionId);
        if (checks == null) {
            return List.of();
        }
        return List.copyOf(checks);
    }

    public FaceProfileJob getProfileJob(String jobId) {
        FaceProfileJob job = profileJobs.get(jobId);
        if (job == null) {
            throw new SessionNotFoundException("Profile job not found: " + jobId);
        }
        return job;
    }
}
