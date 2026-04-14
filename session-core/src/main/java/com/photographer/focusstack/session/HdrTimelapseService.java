package com.photographer.focusstack.session;

import com.photographer.focusstack.capture.BracketCaptureService;
import com.photographer.focusstack.common.camera.CameraBackend;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orchestrates HDR Timelapse sessions: captures bracket sets at intervals,
 * merges to HDR, applies tonemapping, and generates video.
 * No shell calls or external process dependencies.
 *
 * <p>The interval delay between captures is delegated to a {@link Sleeper}
 * so that tests can supply a no-op implementation instead of blocking on
 * {@code Thread.sleep}.</p>
 *
 * <p>For production use, construct with a {@link CameraBackendProvider} so that
 * the correct camera backend is resolved at session-start time from the profile
 * ID stored in {@link HdrSettings#cameraProfileId()}.  For tests, the legacy
 * constructor accepting a pre-built {@link BracketCaptureService} can still be
 * used.</p>
 */
public class HdrTimelapseService {

    private static final Logger log = LoggerFactory.getLogger(HdrTimelapseService.class);

    /**
     * Abstraction over {@code Thread.sleep} so tests can eliminate real delays.
     */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long milliseconds) throws InterruptedException;
    }

    /** Default sleeper that delegates to {@link Thread#sleep(long)}. */
    private static final Sleeper DEFAULT_SLEEPER = Thread::sleep;

    /** Legacy fixed capture service (non-null when using test constructors). */
    private final BracketCaptureService captureService;

    /** Provider that resolves a real backend per camera profile (non-null in production). */
    private final CameraBackendProvider backendProvider;

    private final SessionStorage storage;
    private final HdrProcessor hdrProcessor;
    private final Tonemapper tonemapper;
    private final VideoEncoder videoEncoder;
    private final Sleeper sleeper;

    private final Map<String, CaptureSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, List<BracketSet>> sessionBrackets = new ConcurrentHashMap<>();
    private final Map<String, HdrSettings> sessionSettings = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> stopFlags = new ConcurrentHashMap<>();

    /** Production constructor: resolves the camera backend via {@code backendProvider} at session start. */
    public HdrTimelapseService(CameraBackendProvider backendProvider,
                                SessionStorage storage,
                                HdrProcessor hdrProcessor,
                                Tonemapper tonemapper,
                                VideoEncoder videoEncoder) {
        this(backendProvider, storage, hdrProcessor, tonemapper, videoEncoder, DEFAULT_SLEEPER);
    }

    /** Production constructor with custom sleeper (for testing the production code path). */
    public HdrTimelapseService(CameraBackendProvider backendProvider,
                                SessionStorage storage,
                                HdrProcessor hdrProcessor,
                                Tonemapper tonemapper,
                                VideoEncoder videoEncoder,
                                Sleeper sleeper) {
        this.backendProvider = backendProvider;
        this.captureService = null;
        this.storage = storage;
        this.hdrProcessor = hdrProcessor;
        this.tonemapper = tonemapper;
        this.videoEncoder = videoEncoder;
        this.sleeper = sleeper;
    }

    /** Legacy constructor for tests that supply a pre-built {@link BracketCaptureService}. */
    public HdrTimelapseService(BracketCaptureService captureService,
                                SessionStorage storage,
                                HdrProcessor hdrProcessor,
                                Tonemapper tonemapper,
                                VideoEncoder videoEncoder) {
        this(captureService, storage, hdrProcessor, tonemapper, videoEncoder, DEFAULT_SLEEPER);
    }

    /** Legacy constructor with custom sleeper. */
    public HdrTimelapseService(BracketCaptureService captureService,
                                SessionStorage storage,
                                HdrProcessor hdrProcessor,
                                Tonemapper tonemapper,
                                VideoEncoder videoEncoder,
                                Sleeper sleeper) {
        this.captureService = captureService;
        this.backendProvider = null;
        this.storage = storage;
        this.hdrProcessor = hdrProcessor;
        this.tonemapper = tonemapper;
        this.videoEncoder = videoEncoder;
        this.sleeper = sleeper;
    }

    /**
     * Resolves the {@link BracketCaptureService} to use for the given session.
     * Uses the {@link CameraBackendProvider} if set; otherwise falls back to the
     * fixed {@code captureService} (test / mock mode).
     */
    private BracketCaptureService resolveCapture(HdrSettings settings) {
        if (backendProvider != null && settings.cameraProfileId() != null) {
            CameraBackend backend = backendProvider.getBackend(settings.cameraProfileId());
            return new BracketCaptureService(backend);
        }
        if (captureService != null) {
            return captureService;
        }
        throw new SessionException(
                "No camera backend available: supply a cameraProfileId in HdrSettings or " +
                "configure the application with a CameraBackendProvider.");
    }

    /**
     * Creates a new HDR timelapse session.
     */
    public CaptureSession createSession(HdrSettings settings) {
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

    /**
     * Validates that the session is in a startable state.
     *
     * @throws SessionNotFoundException if the session does not exist
     * @throws SessionException if the session is not in CREATED or PAUSED state
     */
    public void validateStartable(String sessionId) {
        CaptureSession session = getSession(sessionId);
        if (session.status() != SessionStatus.CREATED && session.status() != SessionStatus.PAUSED) {
            throw new SessionException("Session cannot be started in state: " + session.status());
        }
    }

    /**
     * Executes the HDR timelapse capture loop synchronously.
     * Call from an async executor for non-blocking operation.
     */
    public CaptureSession executeSession(String sessionId) {
        CaptureSession session = getSession(sessionId);
        if (session.status() != SessionStatus.CREATED && session.status() != SessionStatus.PAUSED) {
            throw new SessionException("Session cannot be started in state: " + session.status());
        }

        HdrSettings settings = sessionSettings.get(sessionId);
        if (settings == null) {
            throw new SessionException("No settings found for session: " + sessionId);
        }

        // Resolve the capture service: use real backend from provider if cameraProfileId is set
        BracketCaptureService resolvedCaptureService = resolveCapture(settings);

        AtomicBoolean stopFlag = new AtomicBoolean(false);
        stopFlags.put(sessionId, stopFlag);

        CaptureSession running = new CaptureSession(
                sessionId, session.type(), SessionStatus.RUNNING,
                session.createdAt(), Instant.now(), null,
                new ArrayList<>(session.frameIds()), null, session.metadata());
        sessions.put(sessionId, running);

        List<BracketSet> brackets = sessionBrackets.get(sessionId);
        List<String> frameIds = new ArrayList<>(running.frameIds());

        try {
            for (int interval = brackets.size(); interval < settings.totalIntervals(); interval++) {
                if (stopFlag.get()) {
                    log.info("Session {} stop requested at interval {}", sessionId, interval);
                    break;
                }

                int startIndex = interval * settings.bracketCount();
                BracketSet bracketSet = resolvedCaptureService.captureBracket(
                        sessionId, settings.bracketCount(), settings.evStep(), startIndex);

                // Collect frame IDs
                for (CaptureFrame frame : bracketSet.frames()) {
                    frameIds.add(frame.frameId());
                }

                // HDR merge
                List<byte[]> bracketImages = new ArrayList<>();
                List<Double> evOffsets = new ArrayList<>();
                for (CaptureFrame frame : bracketSet.frames()) {
                    try {
                        byte[] data = storage.readFile(sessionId, "frames/" + frame.fileName());
                        bracketImages.add(data);
                    } catch (IOException e) {
                        // Frame may not have been stored yet if using mock backend
                        bracketImages.add(new byte[]{0});
                    }
                    evOffsets.add(frame.exposureValue());
                }

                byte[] hdrResult = hdrProcessor.merge(bracketImages, evOffsets);

                // Tonemapping
                if (settings.tonemapEnabled() && hdrResult.length > 0) {
                    hdrResult = tonemapper.tonemap(hdrResult, 2.2);
                }

                // Store HDR result and propagate path back into BracketSet
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

                // Store bracket with HDR result path
                BracketSet bracketWithHdr = new BracketSet(
                        bracketSet.bracketSetId(), bracketSet.sessionId(),
                        bracketSet.capturedAt(), bracketSet.frames(), hdrResultPath);
                brackets.add(bracketWithHdr);

                // Update session
                CaptureSession updated = new CaptureSession(
                        sessionId, session.type(), SessionStatus.RUNNING,
                        session.createdAt(), running.startedAt(), null,
                        new ArrayList<>(frameIds), null, session.metadata());
                sessions.put(sessionId, updated);

                log.debug("Session {}: completed interval {}/{}", sessionId, interval + 1,
                        settings.totalIntervals());

                // Wait for next interval (skip for last)
                if (interval < settings.totalIntervals() - 1 && !stopFlag.get()) {
                    try {
                        sleeper.sleep(settings.intervalSeconds() * 1000L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            SessionStatus finalStatus = stopFlag.get() ? SessionStatus.CANCELLED : SessionStatus.COMPLETED;
            CaptureSession completed = new CaptureSession(
                    sessionId, session.type(), finalStatus,
                    session.createdAt(), running.startedAt(), Instant.now(),
                    frameIds, null, session.metadata());
            sessions.put(sessionId, completed);

            log.info("HDR timelapse session {} {} with {} brackets",
                    sessionId, finalStatus, brackets.size());
            return completed;

        } catch (Exception e) {
            log.error("HDR timelapse session {} failed: {}", sessionId, e.getMessage(), e);
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
     * Requests the current session to stop after the current interval.
     */
    public void stopSession(String sessionId) {
        getSession(sessionId); // Verify it exists
        AtomicBoolean stopFlag = stopFlags.get(sessionId);
        if (stopFlag != null) {
            stopFlag.set(true);
        }
        log.info("Stop requested for session: {}", sessionId);
    }

    /**
     * Generates a timelapse video from the HDR results.
     */
    public byte[] generateVideo(String sessionId, int fps, int width, int height) {
        getSession(sessionId); // Verify exists
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

    /**
     * Returns the video format of the configured encoder (e.g. "mp4", "mock").
     */
    public String getVideoFormat() {
        return videoEncoder.format();
    }

    /**
     * Gets the current session status.
     */
    public CaptureSession getSession(String sessionId) {
        CaptureSession session = sessions.get(sessionId);
        if (session == null) {
            throw new SessionNotFoundException("Session not found: " + sessionId);
        }
        return session;
    }

    /**
     * Gets all bracket sets for a session.
     * Returns an immutable snapshot to avoid concurrent modification.
     */
    public List<BracketSet> getBrackets(String sessionId) {
        List<BracketSet> brackets = sessionBrackets.get(sessionId);
        if (brackets == null) {
            return List.of();
        }
        return List.copyOf(brackets);
    }
}
