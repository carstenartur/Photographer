package com.photographer.focusstack.session;

import com.photographer.focusstack.capture.SequenceCaptureService;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Orchestrates guided or automatic face-orbit capture while retaining one
 * logical camera owner for the complete capture session.
 */
public class FaceOrbitService {

    private static final Logger log = LoggerFactory.getLogger(FaceOrbitService.class);
    private static final int SESSION_LOCK_STRIPES = 64;

    private final SequenceCaptureService captureService;
    private final CameraBackendProvider backendProvider;
    private final SessionStorage storage;
    private final FaceAnalysisEngine analysisEngine;
    private final FaceProfileEngine profileEngine;

    private final Map<String, CaptureSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, FaceOrbitSettings> sessionSettings = new ConcurrentHashMap<>();
    private final Map<String, List<CaptureFrame>> sessionFrames = new ConcurrentHashMap<>();
    private final Map<String, List<QualityCheck>> sessionQualityChecks = new ConcurrentHashMap<>();
    private final Map<String, FaceProfileJob> profileJobs = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ManualOwnership> manualOwnerships =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PreparedAutoExecution> autoExecutions =
            new ConcurrentHashMap<>();
    private final AtomicBoolean shutdownRequested = new AtomicBoolean();
    private final Object[] sessionLocks = createSessionLocks();

    public FaceOrbitService(CameraBackendProvider backendProvider,
                             SessionStorage storage,
                             FaceAnalysisEngine analysisEngine,
                             FaceProfileEngine profileEngine) {
        this.backendProvider = Objects.requireNonNull(backendProvider, "backendProvider");
        this.captureService = null;
        this.storage = Objects.requireNonNull(storage, "storage");
        this.analysisEngine = Objects.requireNonNull(analysisEngine, "analysisEngine");
        this.profileEngine = Objects.requireNonNull(profileEngine, "profileEngine");
    }

    public FaceOrbitService(SequenceCaptureService captureService,
                             SessionStorage storage,
                             FaceAnalysisEngine analysisEngine,
                             FaceProfileEngine profileEngine) {
        this.captureService = Objects.requireNonNull(captureService, "captureService");
        this.backendProvider = null;
        this.storage = Objects.requireNonNull(storage, "storage");
        this.analysisEngine = Objects.requireNonNull(analysisEngine, "analysisEngine");
        this.profileEngine = Objects.requireNonNull(profileEngine, "profileEngine");
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

    private PreparedCapture prepareCapture(
            String sessionId,
            FaceOrbitSettings settings) {
        if (backendProvider != null && settings.cameraProfileId() != null) {
            CameraBackendSession backendSession = backendProvider.openSession(
                    settings.cameraProfileId(), CameraSessionPurpose.FACE_ORBIT, sessionId);
            try {
                return new PreparedCapture(
                        new SequenceCaptureService(backendSession.backend()), backendSession::close);
            } catch (RuntimeException failure) {
                backendSession.close();
                throw failure;
            }
        }
        if (captureService != null) {
            return new PreparedCapture(captureService, () -> { });
        }
        throw new SessionException(
                "No camera backend available: supply a cameraProfileId in FaceOrbitSettings or "
                        + "configure the application with a CameraBackendProvider.");
    }

    public CaptureSession createSession(FaceOrbitSettings settings) {
        Objects.requireNonNull(settings, "settings");
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
        sessionFrames.put(sessionId, Collections.synchronizedList(new ArrayList<>()));
        sessionQualityChecks.put(sessionId, Collections.synchronizedList(new ArrayList<>()));

        log.info("Created face orbit session: {}", sessionId);
        return session;
    }

    /**
     * Captures one manual step. The first call opens ownership; following calls
     * reuse it until complete, stop or failure.
     */
    public QualityCheck captureNextStep(String sessionId) {
        synchronized (sessionLock(sessionId)) {
            ensureCameraServiceOpen();
            CaptureSession session = getSession(sessionId);
            if (session.status() != SessionStatus.CREATED
                    && session.status() != SessionStatus.RUNNING) {
                throw new SessionException("Session is not active: " + session.status());
            }
            if (autoExecutions.containsKey(sessionId)) {
                throw new SessionException("Automatic face orbit execution is active");
            }

            FaceOrbitSettings settings = requireSettings(sessionId);
            List<CaptureFrame> frames = sessionFrames.get(sessionId);
            int stepIndex = frames.size();
            if (stepIndex >= settings.totalSteps()) {
                throw new SessionException("All face orbit steps are already captured");
            }

            ManualOwnership ownership = manualOwnerships.get(sessionId);
            if (ownership == null) {
                ManualOwnership created = new ManualOwnership(
                        prepareCapture(sessionId, settings));
                ManualOwnership previous = manualOwnerships.putIfAbsent(sessionId, created);
                if (previous != null) {
                    created.close();
                    ownership = previous;
                } else {
                    ownership = created;
                }
            }

            CaptureSession running = session.status() == SessionStatus.CREATED
                    ? new CaptureSession(
                            sessionId, session.type(), SessionStatus.RUNNING,
                            session.createdAt(), Instant.now(), null,
                            session.frameIds(), null, session.metadata())
                    : session;
            sessions.put(sessionId, running);

            double angle = angleFor(settings, stepIndex);
            try {
                CaptureFrame frame = ownership.capture.service().captureFrame(
                        sessionId, stepIndex, angle);
                frames.add(frame);
                QualityCheck quality = analysisEngine.analyzeQuality(
                        new byte[]{0}, frame.frameId());
                sessionQualityChecks.get(sessionId).add(quality);
                List<String> frameIds = frameIds(frames);
                sessions.put(sessionId, new CaptureSession(
                        sessionId, running.type(), SessionStatus.RUNNING,
                        running.createdAt(), running.startedAt(), null,
                        frameIds, null, running.metadata()));
                log.debug("Session {}: captured manual step {}/{} at angle {}",
                        sessionId, stepIndex + 1, settings.totalSteps(), angle);
                return quality;
            } catch (RuntimeException failure) {
                failManualCapture(sessionId, running, frames);
                throw new SessionException("Face orbit capture failed");
            }
        }
    }

    private void failManualCapture(
            String sessionId,
            CaptureSession running,
            List<CaptureFrame> frames) {
        ManualOwnership ownership = manualOwnerships.remove(sessionId);
        if (ownership != null) {
            ownership.close();
        }
        sessions.put(sessionId, new CaptureSession(
                sessionId, running.type(), SessionStatus.FAILED,
                running.createdAt(), running.startedAt(), Instant.now(),
                frameIds(frames),
                "Face orbit capture failed", running.metadata()));
        log.error("Face orbit session {} capture failed", sessionId);
    }

    public void validateAutoModeStartable(String sessionId) {
        synchronized (sessionLock(sessionId)) {
            ensureCameraServiceOpen();
            CaptureSession session = getSession(sessionId);
            requireAutoStartable(sessionId, session);
        }
    }

    private void requireAutoStartable(String sessionId, CaptureSession session) {
        if (session.status() != SessionStatus.CREATED) {
            throw new SessionException("Auto mode can only start from CREATED state");
        }
        if (manualOwnerships.containsKey(sessionId)
                || autoExecutions.containsKey(sessionId)) {
            throw new SessionException("Session already owns a camera execution");
        }
    }

    /** Opens ownership before the controller is allowed to enqueue auto mode. */
    public PreparedAutoExecution prepareAutoExecution(String sessionId) {
        synchronized (sessionLock(sessionId)) {
            ensureCameraServiceOpen();
            CaptureSession session = getSession(sessionId);
            requireAutoStartable(sessionId, session);
            FaceOrbitSettings settings = requireSettings(sessionId);
            PreparedCapture capture = prepareCapture(sessionId, settings);
            CaptureSession running = new CaptureSession(
                    sessionId, session.type(), SessionStatus.RUNNING,
                    session.createdAt(), Instant.now(), null,
                    new ArrayList<>(session.frameIds()), null, session.metadata());
            PreparedAutoExecution execution = new PreparedAutoExecution(
                    this, session, running, settings, capture);
            PreparedAutoExecution previous = autoExecutions.putIfAbsent(
                    sessionId, execution);
            if (previous != null) {
                capture.close();
                throw new SessionException("Session already owns a camera execution");
            }
            sessions.put(sessionId, running);
            return execution;
        }
    }

    public CaptureSession executeAutoMode(String sessionId) {
        return prepareAutoExecution(sessionId).execute();
    }

    private CaptureSession runAuto(PreparedAutoExecution execution) {
        String sessionId = execution.sessionId();
        CaptureSession source = execution.sourceSession;
        CaptureSession running = execution.runningSession;
        FaceOrbitSettings settings = execution.settings;
        List<CaptureFrame> frames = sessionFrames.get(sessionId);

        try {
            for (int step = frames.size(); step < settings.totalSteps(); step++) {
                if (execution.stopRequested()) {
                    break;
                }
                double angle = angleFor(settings, step);
                CaptureFrame frame = execution.capture.service().captureFrame(
                        sessionId, step, angle);
                frames.add(frame);
                QualityCheck quality = analysisEngine.analyzeQuality(
                        new byte[]{0}, frame.frameId());
                sessionQualityChecks.get(sessionId).add(quality);
                updateAutoProgress(execution, frames);

                if (settings.qualityCheckEnabled() && !quality.passed()) {
                    log.warn("Session {}: quality check failed at step {}",
                            sessionId, step);
                }
                log.debug("Session {}: auto step {}/{} angle={}",
                        sessionId, step + 1, settings.totalSteps(), angle);
            }

            SessionStatus status = execution.stopRequested()
                    ? SessionStatus.CANCELLED : SessionStatus.COMPLETED;
            CaptureSession terminal = new CaptureSession(
                    sessionId, source.type(), status,
                    source.createdAt(), running.startedAt(), Instant.now(),
                    frameIds(frames),
                    null, source.metadata());
            storeAutoTerminal(execution, terminal);
            return getSession(sessionId);
        } catch (RuntimeException failure) {
            SessionStatus status = execution.stopRequested()
                    ? SessionStatus.CANCELLED : SessionStatus.FAILED;
            CaptureSession terminal = new CaptureSession(
                    sessionId, source.type(), status,
                    source.createdAt(), running.startedAt(), Instant.now(),
                    frameIds(frames),
                    status == SessionStatus.FAILED ? "Face orbit capture failed" : null,
                    source.metadata());
            storeAutoTerminal(execution, terminal);
            log.error("Face orbit session {} failed with {}",
                    sessionId, failure.getClass().getSimpleName());
            return getSession(sessionId);
        }
    }

    private void updateAutoProgress(
            PreparedAutoExecution execution,
            List<CaptureFrame> frames) {
        synchronized (sessionLock(execution.sessionId())) {
            if (autoExecutions.get(execution.sessionId()) != execution
                    || execution.stopRequested()) {
                return;
            }
            CaptureSession running = execution.runningSession;
            sessions.put(execution.sessionId(), new CaptureSession(
                    execution.sessionId(), running.type(), SessionStatus.RUNNING,
                    running.createdAt(), running.startedAt(), null,
                    frameIds(frames),
                    null, running.metadata()));
        }
    }

    private void storeAutoTerminal(
            PreparedAutoExecution execution,
            CaptureSession terminal) {
        synchronized (sessionLock(execution.sessionId())) {
            if (autoExecutions.get(execution.sessionId()) == execution) {
                sessions.put(execution.sessionId(), terminal);
            }
        }
    }

    private void finishAuto(PreparedAutoExecution execution) {
        autoExecutions.remove(execution.sessionId(), execution);
        execution.closeCapture();
    }

    private void rejectAuto(
            PreparedAutoExecution execution,
            RuntimeException failure) {
        synchronized (sessionLock(execution.sessionId())) {
            if (autoExecutions.remove(execution.sessionId(), execution)) {
                CaptureSession running = execution.runningSession;
                sessions.put(execution.sessionId(), new CaptureSession(
                        execution.sessionId(), running.type(), SessionStatus.FAILED,
                        running.createdAt(), running.startedAt(), Instant.now(),
                        running.frameIds(), "Face orbit scheduling failed",
                        running.metadata()));
            }
        }
        execution.closeCapture();
    }

    public void requestCameraShutdown() {
        if (!shutdownRequested.compareAndSet(false, true)) {
  return;
        }
        List.copyOf(autoExecutions.keySet()).forEach(this::stopSession);
        List.copyOf(manualOwnerships.keySet()).forEach(this::stopSession);
    }

    public int activeCameraExecutions() {
        return autoExecutions.size() + manualOwnerships.size();
    }

    private void ensureCameraServiceOpen() {
        if (shutdownRequested.get()) {
  throw new SessionException("Camera workflow service is shutting down");
        }
    }

    public void stopSession(String sessionId) {
        synchronized (sessionLock(sessionId)) {
            CaptureSession session = getSession(sessionId);
            PreparedAutoExecution auto = autoExecutions.get(sessionId);
            if (auto != null) {
                if (auto.cancelBeforeStart()) {
                    autoExecutions.remove(sessionId, auto);
                    sessions.put(sessionId, cancelled(session));
                    auto.closeCapture();
                } else {
                    auto.requestStop();
                }
                return;
            }

            ManualOwnership manual = manualOwnerships.remove(sessionId);
            if (manual != null) {
                manual.close();
            }
            if (session.status() == SessionStatus.CREATED
                    || session.status() == SessionStatus.RUNNING
                    || session.status() == SessionStatus.PAUSED) {
                sessions.put(sessionId, cancelled(session));
            }
        }
    }

    private static CaptureSession cancelled(CaptureSession source) {
        return new CaptureSession(
                source.sessionId(), source.type(), SessionStatus.CANCELLED,
                source.createdAt(), source.startedAt(), Instant.now(),
                source.frameIds(), null, source.metadata());
    }

    public CaptureSession completeSession(String sessionId) {
        synchronized (sessionLock(sessionId)) {
            CaptureSession session = getSession(sessionId);
            if (autoExecutions.containsKey(sessionId)) {
                throw new SessionException("Automatic face orbit execution is active");
            }
            if (session.status() != SessionStatus.CREATED
                    && session.status() != SessionStatus.RUNNING
                    && session.status() != SessionStatus.PAUSED) {
                throw new SessionException("Session cannot be completed in state: "
                        + session.status());
            }
            List<CaptureFrame> frames = snapshot(
                sessionFrames.getOrDefault(sessionId, List.of()));
            CaptureSession completed = new CaptureSession(
                    sessionId, session.type(), SessionStatus.COMPLETED,
                    session.createdAt(), session.startedAt(), Instant.now(),
                    frameIds(frames),
                    null, session.metadata());
            sessions.put(sessionId, completed);
            ManualOwnership manual = manualOwnerships.remove(sessionId);
            if (manual != null) {
                manual.close();
            }
            return completed;
        }
    }

    public FaceProfileJob generateProfile(String sessionId) {
        CaptureSession session = getSession(sessionId);
        List<CaptureFrame> frames = snapshot(
                sessionFrames.getOrDefault(sessionId, List.of()));
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
                    imageData = new byte[]{0};
                }
                imageDataList.add(imageData);
                angles.add(Double.parseDouble(
                        frame.metadata().getOrDefault("angle", "0.0")));
                landmarksList.add(analysisEngine.detectLandmarks(imageData));
            }

            FaceProfileEngine.FaceProfileResult result = profileEngine.generate(
                    imageDataList, angles, landmarksList);
            String resultPath = "results/profile_" + jobId + "." + result.format();
            try {
                storage.storeFile(sessionId, resultPath, result.profileData());
            } catch (IOException e) {
                log.warn("Failed to store face profile result");
            }

            FaceProfileJob completed = new FaceProfileJob(
                    jobId, sessionId, SessionStatus.COMPLETED,
                    job.createdAt(), Instant.now(), resultPath, null,
                    result.vertexCount(), result.confidence());
            profileJobs.put(jobId, completed);
            return completed;
        } catch (Exception e) {
            log.error("Face profile generation failed for session {}", sessionId);
            FaceProfileJob failed = new FaceProfileJob(
                    jobId, sessionId, SessionStatus.FAILED,
                    job.createdAt(), Instant.now(), null,
                    "Face profile generation failed", 0, 0.0);
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

    public List<QualityCheck> getQualityChecks(String sessionId) {
        List<QualityCheck> checks = sessionQualityChecks.get(sessionId);
        return checks == null ? List.of() : snapshot(checks);
    }

    public FaceProfileJob getProfileJob(String jobId) {
        FaceProfileJob job = profileJobs.get(jobId);
        if (job == null) {
            throw new SessionNotFoundException("Profile job not found: " + jobId);
        }
        return job;
    }

    private static List<String> frameIds(List<CaptureFrame> frames) {
        synchronized (frames) {
            return frames.stream().map(CaptureFrame::frameId).toList();
        }
    }

    private static <T> List<T> snapshot(List<T> values) {
        synchronized (values) {
            return List.copyOf(values);
        }
    }

    private FaceOrbitSettings requireSettings(String sessionId) {
        FaceOrbitSettings settings = sessionSettings.get(sessionId);
        if (settings == null) {
            throw new SessionException("No settings found for face orbit session");
        }
        return settings;
    }

    private static double angleFor(FaceOrbitSettings settings, int stepIndex) {
        return (settings.angleRange() / Math.max(1, settings.totalSteps() - 1))
                * stepIndex;
    }

    private enum AutoState {
        QUEUED,
        RUNNING,
        CANCELLED,
        FINISHED
    }

    public static final class PreparedAutoExecution implements Runnable {
        private final FaceOrbitService owner;
        private final CaptureSession sourceSession;
        private final CaptureSession runningSession;
        private final FaceOrbitSettings settings;
        private final PreparedCapture capture;
        private final AtomicReference<AutoState> state =
                new AtomicReference<>(AutoState.QUEUED);
        private final AtomicBoolean stopRequested = new AtomicBoolean();

        private PreparedAutoExecution(
                FaceOrbitService owner,
                CaptureSession sourceSession,
                CaptureSession runningSession,
                FaceOrbitSettings settings,
                PreparedCapture capture) {
            this.owner = owner;
            this.sourceSession = sourceSession;
            this.runningSession = runningSession;
            this.settings = settings;
            this.capture = capture;
        }

        public String sessionId() {
            return runningSession.sessionId();
        }

        public CaptureSession execute() {
            if (!state.compareAndSet(AutoState.QUEUED, AutoState.RUNNING)) {
                return owner.getSession(sessionId());
            }
            try {
                return owner.runAuto(this);
            } finally {
                state.set(AutoState.FINISHED);
                owner.finishAuto(this);
            }
        }

        @Override
        public void run() {
            execute();
        }

        public void reject(RuntimeException failure) {
            Objects.requireNonNull(failure, "failure");
            if (state.compareAndSet(AutoState.QUEUED, AutoState.CANCELLED)) {
                owner.rejectAuto(this, failure);
            }
        }

        private boolean cancelBeforeStart() {
            return state.compareAndSet(AutoState.QUEUED, AutoState.CANCELLED);
        }

        private void requestStop() {
            stopRequested.set(true);
        }

        private boolean stopRequested() {
            return stopRequested.get();
        }

        private void closeCapture() {
            capture.close();
        }
    }

    private static final class ManualOwnership implements AutoCloseable {
        private final PreparedCapture capture;

        private ManualOwnership(PreparedCapture capture) {
            this.capture = capture;
        }

        @Override
        public void close() {
            capture.close();
        }
    }

    private static final class PreparedCapture implements AutoCloseable {
        private final SequenceCaptureService service;
        private final Runnable closeAction;
        private final AtomicBoolean closed = new AtomicBoolean();

        private PreparedCapture(SequenceCaptureService service, Runnable closeAction) {
            this.service = Objects.requireNonNull(service, "service");
            this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
        }

        private SequenceCaptureService service() {
            return service;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                closeAction.run();
            }
        }
    }
}
