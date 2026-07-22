package com.photographer.focusstack.session;

import com.photographer.focusstack.capture.BracketCaptureService;
import com.photographer.focusstack.common.model.BracketSet;
import com.photographer.focusstack.common.model.CaptureFrame;
import com.photographer.focusstack.common.model.CaptureSession;
import com.photographer.focusstack.common.model.HdrSettings;
import com.photographer.focusstack.common.model.SessionStatus;
import com.photographer.focusstack.hdr.HdrProcessor;
import com.photographer.focusstack.hdr.Tonemapper;
import com.photographer.focusstack.storage.SessionStorage;
import com.photographer.focusstack.video.VideoEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Orchestrates HDR Timelapse sessions: captures bracket sets at intervals,
 * merges to HDR, applies tonemapping, and generates video.
 * No shell calls or external process dependencies.
 *
 * <p>The interval delay between captures is delegated to a {@link Sleeper}
 * so that tests can supply a no-op implementation instead of blocking on
 * {@code Thread.sleep}.</p>
 *
 * <p>Production execution is split into synchronous preparation and asynchronous
 * execution. {@link #prepareExecution(String)} opens the camera ownership before
 * a controller may enqueue the worker. The returned execution retains that
 * ownership until success, failure or cooperative cancellation has completed.</p>
 */
public class HdrTimelapseService {

    private static final Logger log = LoggerFactory.getLogger(HdrTimelapseService.class);
    private static final int SESSION_LOCK_STRIPES = 64;

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long milliseconds) throws InterruptedException;
    }

    private static final Sleeper DEFAULT_SLEEPER = Thread::sleep;

    /** Legacy fixed capture service (non-null when using test constructors). */
    private final BracketCaptureService captureService;

    /** Provider that opens an owned backend session in production. */
    private final CameraBackendProvider backendProvider;

    private final SessionStorage storage;
    private final HdrProcessor hdrProcessor;
    private final Tonemapper tonemapper;
    private final VideoEncoder videoEncoder;
    private final Sleeper sleeper;

    private final Map<String, CaptureSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, List<BracketSet>> sessionBrackets = new ConcurrentHashMap<>();
    private final Map<String, HdrSettings> sessionSettings = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PreparedExecution> activeExecutions =
            new ConcurrentHashMap<>();
    private final AtomicBoolean shutdownRequested = new AtomicBoolean();
    private final Object[] sessionLocks = createSessionLocks();

    public HdrTimelapseService(CameraBackendProvider backendProvider,
                                SessionStorage storage,
                                HdrProcessor hdrProcessor,
                                Tonemapper tonemapper,
                                VideoEncoder videoEncoder) {
        this(backendProvider, storage, hdrProcessor, tonemapper, videoEncoder, DEFAULT_SLEEPER);
    }

    public HdrTimelapseService(CameraBackendProvider backendProvider,
                                SessionStorage storage,
                                HdrProcessor hdrProcessor,
                                Tonemapper tonemapper,
                                VideoEncoder videoEncoder,
                                Sleeper sleeper) {
        this.backendProvider = Objects.requireNonNull(backendProvider, "backendProvider");
        this.captureService = null;
        this.storage = Objects.requireNonNull(storage, "storage");
        this.hdrProcessor = Objects.requireNonNull(hdrProcessor, "hdrProcessor");
        this.tonemapper = Objects.requireNonNull(tonemapper, "tonemapper");
        this.videoEncoder = Objects.requireNonNull(videoEncoder, "videoEncoder");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    public HdrTimelapseService(BracketCaptureService captureService,
                                SessionStorage storage,
                                HdrProcessor hdrProcessor,
                                Tonemapper tonemapper,
                                VideoEncoder videoEncoder) {
        this(captureService, storage, hdrProcessor, tonemapper, videoEncoder, DEFAULT_SLEEPER);
    }

    public HdrTimelapseService(BracketCaptureService captureService,
                                SessionStorage storage,
                                HdrProcessor hdrProcessor,
                                Tonemapper tonemapper,
                                VideoEncoder videoEncoder,
                                Sleeper sleeper) {
        this.captureService = Objects.requireNonNull(captureService, "captureService");
        this.backendProvider = null;
        this.storage = Objects.requireNonNull(storage, "storage");
        this.hdrProcessor = Objects.requireNonNull(hdrProcessor, "hdrProcessor");
        this.tonemapper = Objects.requireNonNull(tonemapper, "tonemapper");
        this.videoEncoder = Objects.requireNonNull(videoEncoder, "videoEncoder");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
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

    private PreparedCapture prepareCapture(HdrSettings settings, String sessionId) {
        if (backendProvider != null && settings.cameraProfileId() != null) {
            CameraBackendSession backendSession = backendProvider.openSession(
                    settings.cameraProfileId(), CameraSessionPurpose.HDR_TIMELAPSE, sessionId);
            try {
                return new PreparedCapture(
                        new BracketCaptureService(backendSession.backend()), backendSession::close);
            } catch (RuntimeException failure) {
                backendSession.close();
                throw failure;
            }
        }
        if (captureService != null) {
            return new PreparedCapture(captureService, () -> { });
        }
        throw new SessionException(
                "No camera backend available: supply a cameraProfileId in HdrSettings or "
                        + "configure the application with a CameraBackendProvider.");
    }

    public CaptureSession createSession(HdrSettings settings) {
        Objects.requireNonNull(settings, "settings");
        String sessionId = UUID.randomUUID().toString();
        try {
            storage.createSessionDirectory(sessionId);
        } catch (IOException e) {
            throw new SessionException("Failed to create session directory", e);
        }

        CaptureSession session = new CaptureSession(
                sessionId, "hdr-timelapse", SessionStatus.CREATED,
                Instant.now(), null, null,
                new ArrayList<>(), null,
                Map.of("hdrProcessor", hdrProcessor.name(),
                       "videoEncoder", videoEncoder.name()));
        sessions.put(sessionId, session);
        sessionSettings.put(sessionId, settings);
        sessionBrackets.put(sessionId, new ArrayList<>());

        log.info("Created HDR timelapse session: {}", sessionId);
        return session;
    }

    public void validateStartable(String sessionId) {
        synchronized (sessionLock(sessionId)) {
            ensureCameraServiceOpen();
            CaptureSession session = getSession(sessionId);
            requireStartable(session);
            if (activeExecutions.containsKey(sessionId)) {
                throw new SessionException("Session already has an active camera execution");
            }
        }
    }

    private static void requireStartable(CaptureSession session) {
        if (session.status() != SessionStatus.CREATED
                && session.status() != SessionStatus.PAUSED) {
            throw new SessionException(
                    "Session cannot be started in state: " + session.status());
        }
    }

    /**
     * Opens camera ownership and marks the session running before scheduling.
     * A conflict or backend-initialization error therefore reaches the caller
     * synchronously and no worker is queued without ownership.
     */
    public PreparedExecution prepareExecution(String sessionId) {
        synchronized (sessionLock(sessionId)) {
            ensureCameraServiceOpen();
            CaptureSession session = getSession(sessionId);
            requireStartable(session);
            if (activeExecutions.containsKey(sessionId)) {
                throw new SessionException("Session already has an active camera execution");
            }
            HdrSettings settings = sessionSettings.get(sessionId);
            if (settings == null) {
                throw new SessionException("No settings found for session: " + sessionId);
            }

            PreparedCapture capture = prepareCapture(settings, sessionId);
            CaptureSession running = new CaptureSession(
                    sessionId, session.type(), SessionStatus.RUNNING,
                    session.createdAt(), Instant.now(), null,
                    new ArrayList<>(session.frameIds()), null, session.metadata());
            PreparedExecution execution = new PreparedExecution(
                    this, session, running, settings, capture);
            PreparedExecution previous = activeExecutions.putIfAbsent(sessionId, execution);
            if (previous != null) {
                capture.close();
                throw new SessionException("Session already has an active camera execution");
            }
            sessions.put(sessionId, running);
            return execution;
        }
    }

    /** Executes synchronously while preserving the same preparation contract. */
    public CaptureSession executeSession(String sessionId) {
        return prepareExecution(sessionId).execute();
    }

    private CaptureSession runPrepared(PreparedExecution execution) {
        String sessionId = execution.sessionId();
        CaptureSession source = execution.sourceSession;
        CaptureSession running = execution.runningSession;
        HdrSettings settings = execution.settings;
        List<BracketSet> brackets = sessionBrackets.get(sessionId);
        List<String> frameIds = new ArrayList<>(running.frameIds());

        try {
            for (int interval = brackets.size(); interval < settings.totalIntervals(); interval++) {
                if (execution.stopRequested()) {
                    log.info("Session {} stop requested at interval {}", sessionId, interval);
                    break;
                }

                int startIndex = interval * settings.bracketCount();
                BracketSet bracketSet = execution.capture.service().captureBracket(
                        sessionId, settings.bracketCount(), settings.evStep(), startIndex);

                for (CaptureFrame frame : bracketSet.frames()) {
                    frameIds.add(frame.frameId());
                }

                List<byte[]> bracketImages = new ArrayList<>();
                List<Double> evOffsets = new ArrayList<>();
                for (CaptureFrame frame : bracketSet.frames()) {
                    try {
                        byte[] data = storage.readFile(sessionId, "frames/" + frame.fileName());
                        bracketImages.add(data);
                    } catch (IOException e) {
                        bracketImages.add(new byte[]{0});
                    }
                    evOffsets.add(frame.exposureValue());
                }

                byte[] hdrResult = hdrProcessor.merge(bracketImages, evOffsets);
                if (settings.tonemapEnabled() && hdrResult.length > 0) {
                    hdrResult = tonemapper.tonemap(hdrResult, 2.2);
                }

                String hdrResultPath = null;
                if (hdrResult.length > 0) {
                    hdrResultPath = "brackets/hdr_" + bracketSet.bracketSetId() + ".jpg";
                    try {
                        storage.storeFile(sessionId, hdrResultPath, hdrResult);
                    } catch (IOException e) {
                        log.warn("Failed to store HDR result for bracket {}: {}",
                                bracketSet.bracketSetId(), e.getMessage());
                        hdrResultPath = null;
                    }
                }

                BracketSet bracketWithHdr = new BracketSet(
                        bracketSet.bracketSetId(), bracketSet.sessionId(),
                        bracketSet.capturedAt(), bracketSet.frames(), hdrResultPath);
                brackets.add(bracketWithHdr);
                updateRunningProgress(execution, frameIds);

                log.debug("Session {}: completed interval {}/{}", sessionId, interval + 1,
                        settings.totalIntervals());

                if (interval < settings.totalIntervals() - 1
                        && !execution.stopRequested()) {
                    try {
                        sleeper.sleep(settings.intervalSeconds() * 1000L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        execution.requestStop();
                        break;
                    }
                }
            }

            SessionStatus finalStatus = execution.stopRequested()
                    ? SessionStatus.CANCELLED : SessionStatus.COMPLETED;
            CaptureSession completed = new CaptureSession(
                    sessionId, source.type(), finalStatus,
                    source.createdAt(), running.startedAt(), Instant.now(),
                    frameIds, null, source.metadata());
            storeTerminalIfCurrent(execution, completed);

            log.info("HDR timelapse session {} {} with {} brackets",
                    sessionId, finalStatus, brackets.size());
            return getSession(sessionId);

        } catch (Exception e) {
            log.error("HDR timelapse session {} failed: {}", sessionId, e.getMessage(), e);
            SessionStatus status = execution.stopRequested()
                    ? SessionStatus.CANCELLED : SessionStatus.FAILED;
            CaptureSession failed = new CaptureSession(
                    sessionId, source.type(), status,
                    source.createdAt(), running.startedAt(), Instant.now(),
                    frameIds, status == SessionStatus.FAILED ? safeFailure(e) : null,
                    source.metadata());
            storeTerminalIfCurrent(execution, failed);
            return getSession(sessionId);
        }
    }

    private void updateRunningProgress(
            PreparedExecution execution,
            List<String> frameIds) {
        synchronized (sessionLock(execution.sessionId())) {
            if (activeExecutions.get(execution.sessionId()) != execution
                    || execution.stopRequested()) {
                return;
            }
            CaptureSession running = execution.runningSession;
            sessions.put(execution.sessionId(), new CaptureSession(
                    execution.sessionId(), running.type(), SessionStatus.RUNNING,
                    running.createdAt(), running.startedAt(), null,
                    new ArrayList<>(frameIds), null, running.metadata()));
        }
    }

    private void storeTerminalIfCurrent(
            PreparedExecution execution,
            CaptureSession terminal) {
        synchronized (sessionLock(execution.sessionId())) {
            if (activeExecutions.get(execution.sessionId()) == execution) {
                sessions.put(execution.sessionId(), terminal);
            }
        }
    }

    private void finishExecution(PreparedExecution execution) {
        activeExecutions.remove(execution.sessionId(), execution);
        execution.closeCapture();
    }

    private void rejectExecution(
            PreparedExecution execution,
            RuntimeException failure) {
        synchronized (sessionLock(execution.sessionId())) {
            if (activeExecutions.remove(execution.sessionId(), execution)) {
                CaptureSession running = execution.runningSession;
                sessions.put(execution.sessionId(), new CaptureSession(
                        execution.sessionId(), running.type(), SessionStatus.FAILED,
                        running.createdAt(), running.startedAt(), Instant.now(),
                        running.frameIds(), safeFailure(failure), running.metadata()));
            }
        }
        execution.closeCapture();
    }

    public void requestCameraShutdown() {
        if (!shutdownRequested.compareAndSet(false, true)) {
  return;
        }
        List.copyOf(activeExecutions.keySet()).forEach(this::stopSession);
    }

    public int activeCameraExecutions() {
        return activeExecutions.size();
    }

    private void ensureCameraServiceOpen() {
        if (shutdownRequested.get()) {
  throw new SessionException("Camera workflow service is shutting down");
        }
    }

    public void stopSession(String sessionId) {
        synchronized (sessionLock(sessionId)) {
            CaptureSession session = getSession(sessionId);
            PreparedExecution execution = activeExecutions.get(sessionId);
            if (execution == null) {
                log.info("Stop requested for inactive session: {}", sessionId);
                return;
            }
            if (execution.cancelBeforeStart()) {
                activeExecutions.remove(sessionId, execution);
                sessions.put(sessionId, new CaptureSession(
                        sessionId, session.type(), SessionStatus.CANCELLED,
                        session.createdAt(), session.startedAt(), Instant.now(),
                        session.frameIds(), null, session.metadata()));
                execution.closeCapture();
            } else {
                execution.requestStop();
            }
        }
        log.info("Stop requested for session: {}", sessionId);
    }

    public byte[] generateVideo(String sessionId, int fps, int width, int height) {
        getSession(sessionId);
        List<BracketSet> brackets = sessionBrackets.getOrDefault(sessionId, List.of());
        if (brackets.isEmpty()) {
            return new byte[0];
        }

        List<byte[]> frames = new ArrayList<>();
        for (BracketSet bracket : brackets) {
            try {
                byte[] hdrData = storage.readFile(sessionId,
                        "brackets/hdr_" + bracket.bracketSetId() + ".jpg");
                frames.add(hdrData);
            } catch (IOException e) {
                log.warn("Missing HDR result for bracket {}", bracket.bracketSetId());
            }
        }

        return videoEncoder.encode(frames, fps, width, height);
    }

    public String getVideoFormat() {
        return videoEncoder.format();
    }

    public CaptureSession getSession(String sessionId) {
        CaptureSession session = sessions.get(sessionId);
        if (session == null) {
            throw new SessionNotFoundException("Session not found: " + sessionId);
        }
        return session;
    }

    public List<BracketSet> getBrackets(String sessionId) {
        List<BracketSet> brackets = sessionBrackets.get(sessionId);
        if (brackets == null) {
            return List.of();
        }
        return List.copyOf(brackets);
    }

    private static String safeFailure(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message;
    }

    private enum ExecutionState {
        QUEUED,
        RUNNING,
        CANCELLED,
        FINISHED
    }

    /**
     * A prepared HDR execution whose camera ownership already exists.
     * The controller must either submit it or call {@link #reject(RuntimeException)}.
     */
    public static final class PreparedExecution implements Runnable {
        private final HdrTimelapseService owner;
        private final CaptureSession sourceSession;
        private final CaptureSession runningSession;
        private final HdrSettings settings;
        private final PreparedCapture capture;
        private final AtomicReference<ExecutionState> state =
                new AtomicReference<>(ExecutionState.QUEUED);
        private final AtomicBoolean stopRequested = new AtomicBoolean();

        private PreparedExecution(
                HdrTimelapseService owner,
                CaptureSession sourceSession,
                CaptureSession runningSession,
                HdrSettings settings,
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
            if (!state.compareAndSet(ExecutionState.QUEUED, ExecutionState.RUNNING)) {
                return owner.getSession(sessionId());
            }
            try {
                return owner.runPrepared(this);
            } finally {
                state.set(ExecutionState.FINISHED);
                owner.finishExecution(this);
            }
        }

        @Override
        public void run() {
            execute();
        }

        /** Releases a queued execution after executor rejection and marks it failed. */
        public void reject(RuntimeException failure) {
            Objects.requireNonNull(failure, "failure");
            if (state.compareAndSet(ExecutionState.QUEUED, ExecutionState.CANCELLED)) {
                owner.rejectExecution(this, failure);
            }
        }

        private boolean cancelBeforeStart() {
            return state.compareAndSet(ExecutionState.QUEUED, ExecutionState.CANCELLED);
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

    private static final class PreparedCapture implements AutoCloseable {
        private final BracketCaptureService service;
        private final Runnable closeAction;
        private final AtomicBoolean closed = new AtomicBoolean();

        private PreparedCapture(BracketCaptureService service, Runnable closeAction) {
            this.service = Objects.requireNonNull(service, "service");
            this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
        }

        private BracketCaptureService service() {
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
