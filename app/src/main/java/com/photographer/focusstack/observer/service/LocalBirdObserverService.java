package com.photographer.focusstack.observer.service;

import com.photographer.focusstack.ai.VisionProvider;
import com.photographer.focusstack.ai.local.LocalBirdVisionProvider;
import com.photographer.focusstack.camera.backend.CameraBackendFactory;
import com.photographer.focusstack.camera.service.CameraProfileService;
import com.photographer.focusstack.camera.session.*;
import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.camera.LiveViewFrame;
import com.photographer.focusstack.common.camera.Operation;
import com.photographer.focusstack.common.camera.ProcessingCaptureProvider;
import com.photographer.focusstack.common.model.CameraProfile;
import com.photographer.focusstack.observer.bird.BirdTriggerPolicy;
import com.photographer.focusstack.observer.bird.BirdTriggerSettings;
import com.photographer.focusstack.observer.events.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** One camera-owning worker: fetch, analyse, optionally capture, then fetch again. No frame queue. */
@Service
public class LocalBirdObserverService {
    private static final Logger log = LoggerFactory.getLogger(LocalBirdObserverService.class);
    private static final Duration MAX_FRAME_AGE = Duration.ofSeconds(5);
    private final CameraBackendFactory backends;
    private final CameraProfileService profiles;
    private final CameraSessionLeaseManager leases;
    private final CameraLeaseIdentityProvider identities;
    private final VisionProvider vision;
    private final BirdFrameAnalyzer analyzer;
    private final BirdCaptureStore store;
    private final ObserverService events;
    private volatile Boolean modelAvailable;
    private final Object modelLock = new Object();
    private Run current;
    private boolean closing;

    public LocalBirdObserverService(CameraBackendFactory backends, CameraProfileService profiles,
                                    CameraSessionLeaseManager leases, CameraLeaseIdentityProvider identities,
                                    @Qualifier("localBirdVisionProvider") VisionProvider vision,
                                    BirdCaptureStore store, ObserverService events) {
        this.backends = backends;
        this.profiles = profiles;
        this.leases = leases;
        this.identities = identities;
        this.vision = vision;
        this.analyzer = new BirdFrameAnalyzer(vision);
        this.store = store;
        this.events = events;
    }

    public record Preview(long sequence, Instant timestamp, int width, int height,
                          String imageData, List<BoundingBox> boundingBoxes) {}

    public record Status(boolean running, boolean armed, String phase, String cameraId, String model,
                         boolean modelAvailable, String modelError, BirdTriggerSettings settings,
                         long captureCount, long framesAnalyzed, long skippedFrames, Long frameAgeMs,
                         Long inferenceMs, Instant lastCaptureAt, String lastEventId, String error, Preview preview) {}

    private record Configuration(boolean armed, BirdTriggerSettings settings) {}

    public Status start(String cameraId, boolean armed, BirdTriggerSettings settings) {
        if (armed) throw new IllegalArgumentException("Start in preview mode, then explicitly arm automatic capture");
        if (cameraId == null || cameraId.isBlank()) throw new IllegalArgumentException("Select a camera");
        if (!checkModel()) throw new IllegalStateException("The local bird model is unavailable");
        CameraProfile profile = profiles.getProfile(cameraId);
        String backend = profile.backendType().toLowerCase(Locale.ROOT);
        if (!List.of("canon-ccapi", "canon-usb", "mock").contains(backend)) {
            throw new IllegalArgumentException("Select a CCAPI or USB camera, or a mock camera for development. Folder inputs use Observer Replay.");
        }
        synchronized (this) {
            if (closing) throw new IllegalStateException("Camera services are shutting down");
            if (current != null && !current.finished) throw new IllegalStateException("An observer is already running or stopping");
            Run run = new Run(profile, new Configuration(false, settings == null ? BirdTriggerSettings.defaults() : settings));
            current = run;
            run.thread = Thread.ofPlatform().daemon().name("local-bird-observer").unstarted(() -> execute(run));
            run.thread.start();
        }
        return status();
    }

    public Status update(String cameraId, boolean armed, BirdTriggerSettings settings) {
        if (settings == null) throw new IllegalArgumentException("Settings are required");
        synchronized (this) {
            if (closing || current == null || current.finished || current.stopping) {
                throw new IllegalStateException("Start a preview before changing the running observer");
            }
            if (!current.profile.id().equals(cameraId)) throw new IllegalArgumentException("Stop before selecting another camera");
            // Identity, not equality, is the generation token for in-flight analysis/capture decisions.
            current.configuration = new Configuration(armed, settings);
        }
        return status();
    }

    public Status stop() {
        requestStop();
        return status();
    }

    private synchronized void requestStop() {
        if (current == null || current.finished || current.stopping) return;
        current.stopping = true;
        boolean captureAlreadyCommitted = "CAPTURING".equals(current.phase);
        current.phase = "STOPPING";
        // Let an already committed photo/download finish and be saved. Wake other waits immediately.
        if (!captureAlreadyCommitted) current.thread.interrupt();
    }

    public synchronized void requestCameraShutdown() {
        closing = true;
        requestStop();
    }

    public synchronized int activeCameraExecutions() {
        return current != null && !current.finished ? 1 : 0;
    }

    public Status status() {
        boolean available = checkModel();
        String modelError = available ? null : vision instanceof LocalBirdVisionProvider local
                ? local.availabilityError() : "The local bird model is unavailable";
        synchronized (this) {
            if (current == null) return new Status(false, false, "STOPPED", null,
                    LocalBirdVisionProvider.MODEL_NAME, available, modelError, BirdTriggerSettings.defaults(),
                    0, 0, 0, null, null, null, null, null, null);
            Run run = current;
            Long age = run.preview == null ? null : Math.max(0, Duration.between(run.preview.timestamp(), Instant.now()).toMillis());
            return new Status(!run.finished, !run.finished && !run.stopping && run.configuration.armed(),
                    run.phase, run.profile.id(), LocalBirdVisionProvider.MODEL_NAME, available, modelError,
                    run.configuration.settings(), run.captures, run.analysed, run.skipped, age,
                    run.inferenceMs, run.lastCapture, run.lastEvent, run.error, run.preview);
        }
    }

    private boolean checkModel() {
        if (modelAvailable == null) synchronized (modelLock) {
            if (modelAvailable == null) modelAvailable = vision.isAvailable();
        }
        return Boolean.TRUE.equals(modelAvailable);
    }

    private void execute(Run run) {
        CameraSessionLease lease = null;
        CameraBackend camera = null;
        String terminalError = null;
        try {
            var key = identities.resolve(run.profile);
            lease = key.isPresent() ? leases.acquire(new CameraLeaseRequest(key.get(),
                    CameraWorkflowType.WILDLIFE_OBSERVER, run.id, run.id, Duration.ofHours(6))) : CameraSessionLease.noop();
            if (run.stopping) return;
            camera = backends.getBackend(run.profile);
            if (!camera.supports(Operation.LIVE_VIEW) || !camera.supports(Operation.CAPTURE)) {
                throw new IllegalStateException("Camera does not support live view and photo capture");
            }
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] previousDigest = null;
            Instant previousTimestamp = null;
            Configuration policyConfiguration = null;
            BirdTriggerPolicy policy = null;
            int consecutiveErrors = 0;
            while (!run.stopping) {
                long iterationStart = System.nanoTime();
                Configuration configuration = run.configuration;
                if (configuration != policyConfiguration) {
                    policy = new BirdTriggerPolicy(configuration.settings());
                    policyConfiguration = configuration;
                }
                boolean capturing = false;
                try {
                    setPhase(run, "OBSERVING");
                    LiveViewFrame frame = camera.getLiveView();
                    if (!usable(frame) || previousTimestamp != null && !frame.timestamp().isAfter(previousTimestamp)) {
                        skipped(run, policy, "Waiting for a fresh camera frame");
                    } else {
                        byte[] fingerprint = digest.digest(frame.data());
                        if (Arrays.equals(previousDigest, fingerprint)) {
                            skipped(run, policy, "Duplicate camera frame; waiting for a new image");
                        } else {
                            previousTimestamp = frame.timestamp();
                            previousDigest = fingerprint;
                            var analysis = analyzer.analyze(frame.data(), configuration.settings());
                            if (!canProceed(run, configuration) || !usable(frame)) {
                                skipped(run, policy, "Discarded a stale or superseded detection");
                            } else {
                                synchronized (this) {
                                    run.analysed++;
                                    run.inferenceMs = analysis.result().durationMs();
                                    run.preview = new Preview(run.analysed, frame.timestamp(), analysis.width(), analysis.height(),
                                            Base64.getEncoder().encodeToString(analysis.previewJpeg()), analysis.boxes());
                                    run.error = null;
                                }
                                consecutiveErrors = 0;
                                boolean captureDue = policy.shouldCapture(frame.timestamp(), !analysis.boxes().isEmpty());
                                if (configuration.armed() && captureDue && canProceed(run, configuration)) {
                                    capturing = true;
                                    setPhase(run, "CAPTURING");
                                    camera.stopLiveView();
                                    // A stop or changed ROI during stopLiveView invalidates this decision too.
                                    if (commitCapture(run, configuration, frame)) {
                                        policy.captureCommitted(Instant.now());
                                        var capture = ProcessingCaptureProvider.capture(camera);
                                        synchronized (this) {
                                            run.captures++;
                                            run.lastCapture = Instant.now();
                                        }
                                        String eventId = UUID.randomUUID().toString();
                                        var saved = store.save(eventId, capture, analysis.previewJpeg());
                                        persist(run, configuration, frame, analysis, eventId, saved);
                                        policy.captureCommitted(Instant.now());
                                    }
                                    policy.gap();
                                }
                            }
                        }
                    }
                } catch (BirdFrameAnalyzer.DetectionFailure failure) {
                    if (!run.stopping) terminalError = failure.getMessage();
                    break;
                } catch (IOException | RuntimeException failure) {
                    if (capturing) {
                        terminalError = "Capture or saving failed; stopped to avoid duplicate shots. Check the camera card and output directory.";
                        log.warn("Local bird capture {} failed ({})", run.id, failure.getClass().getSimpleName());
                        break;
                    }
                    if (run.stopping) break;
                    skipped(run, policy, "Camera frame unavailable or invalid; waiting for recovery");
                    if (++consecutiveErrors >= 10) {
                        terminalError = "Stopped after 10 consecutive camera/image errors; reconnect the camera and restart preview";
                        break;
                    }
                }
                if (!run.stopping) {
                    long elapsedMs = (System.nanoTime() - iterationStart) / 1_000_000;
                    Thread.sleep(Math.max(1, configuration.settings().intervalMs() - elapsedMs));
                }
            }
        } catch (InterruptedException interrupted) {
            if (!run.stopping) terminalError = "Observer worker was interrupted";
        } catch (CameraSessionConflictException conflict) {
            terminalError = "Camera is in use by another workflow; stop that workflow before starting preview";
        } catch (Exception failure) {
            terminalError = "Could not start the camera observer; check camera connection and live-view capability";
            log.warn("Local bird observer {} stopped ({})", run.id, failure.getClass().getSimpleName());
        } finally {
            // Cancellation must not cause a live-view cleanup request to fail just because the flag is set.
            Thread.interrupted();
            if (camera != null) {
                try { camera.stopLiveView(); }
                catch (RuntimeException failure) {
                    terminalError = "Observer stopped, but camera live-view cleanup failed; check the camera before restarting";
                }
            }
            if (lease != null) lease.close();
            synchronized (this) {
                run.finished = true;
                run.phase = terminalError == null ? "STOPPED" : "ERROR";
                run.error = terminalError;
            }
        }
    }

    private void persist(Run run, Configuration configuration, LiveViewFrame frame,
                         BirdFrameAnalyzer.Analysis analysis, String eventId, BirdCaptureStore.StoredCapture saved) {
        double confidence = analysis.boxes().stream().mapToDouble(BoundingBox::confidence).max().orElse(0);
        var result = new VisionResultData(analysis.result().provider(), analysis.result().model(), "object_detection",
                "bird", List.of(), confidence, "Bird detected in observation region; species not classified",
                analysis.boxes(), null, analysis.result().durationMs());
        events.onEventCompleted(new ObservationEvent.Builder().id(eventId).timestamp(frame.timestamp())
                .completedAt(Instant.now()).state(EventState.COMPLETED).mode("wildlife")
                .cameraId(run.profile.id()).triggerReason("local-bird").roi(configuration.settings().roi())
                .images(List.of(saved.original())).bestImageId(saved.original().id()).aiResult(result)
                .metadata(Map.of("previewPath", saved.previewPath(), "previewSource", saved.previewSource())).build());
        synchronized (this) { run.lastEvent = eventId; }
    }

    private synchronized boolean canProceed(Run run, Configuration configuration) {
        return current == run && !run.stopping && !closing && run.configuration == configuration;
    }

    private synchronized boolean commitCapture(Run run, Configuration configuration, LiveViewFrame frame) {
        return configuration.armed() && canProceed(run, configuration) && usable(frame);
    }

    private synchronized void setPhase(Run run, String phase) {
        if (!run.stopping) run.phase = phase;
    }

    private synchronized void skipped(Run run, BirdTriggerPolicy policy, String reason) {
        policy.gap();
        run.skipped++;
        run.error = reason;
    }

    private static boolean usable(LiveViewFrame frame) {
        if (frame == null || frame.data() == null || frame.data().length == 0
                || frame.data().length > BirdFrameAnalyzer.MAX_FRAME_BYTES || frame.timestamp() == null) return false;
        Instant now = Instant.now();
        return !frame.timestamp().isAfter(now.plusSeconds(1))
                && Duration.between(frame.timestamp(), now).compareTo(MAX_FRAME_AGE) <= 0;
    }

    private static final class Run {
        final String id = UUID.randomUUID().toString();
        final CameraProfile profile;
        volatile Configuration configuration;
        volatile boolean stopping;
        boolean finished;
        Thread thread;
        String phase = "STARTING";
        long captures, analysed, skipped;
        Long inferenceMs;
        Instant lastCapture;
        String lastEvent, error;
        Preview preview;

        Run(CameraProfile profile, Configuration configuration) {
            this.profile = profile;
            this.configuration = configuration;
        }
    }
}
