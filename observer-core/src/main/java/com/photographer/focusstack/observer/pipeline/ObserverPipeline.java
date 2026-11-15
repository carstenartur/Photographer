package com.photographer.focusstack.observer.pipeline;

import com.photographer.focusstack.ai.VisionFailureKind;
import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.camera.LiveViewFrame;
import com.photographer.focusstack.common.pipeline.PipelineBlockerKind;
import com.photographer.focusstack.observer.capture.CaptureOrchestrator;
import com.photographer.focusstack.observer.capture.CaptureSeriesResult;
import com.photographer.focusstack.observer.events.EventState;
import com.photographer.focusstack.observer.events.EventStateMachine;
import com.photographer.focusstack.observer.events.ObservationEvent;
import com.photographer.focusstack.observer.events.VisionResultData;
import com.photographer.focusstack.observer.monitoring.SceneMonitor;
import com.photographer.focusstack.observer.profile.ObserverProfile;
import com.photographer.focusstack.observer.rules.TriggerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Central coordinator for the Wildlife Observer pipeline.
 *
 * <p>Call {@link #processFrame(LiveViewFrame)} periodically for each new preview frame.
 * The pipeline manages the complete flow:</p>
 * <ol>
 *   <li>Scene analysis (motion detection + rules evaluation)</li>
 *   <li>Capture series</li>
 *   <li>Best-shot selection</li>
 *   <li>AI analysis</li>
 *   <li>Event completion or discard</li>
 * </ol>
 *
 * <p>Thread safety: not thread-safe. Use one instance per camera/profile combination.</p>
 */
public class ObserverPipeline {

    private static final Logger log = LoggerFactory.getLogger(ObserverPipeline.class);

    private final SceneMonitor sceneMonitor;
    private final CaptureOrchestrator captureOrchestrator;
    private final VisionProviderPort visionProvider;
    private final EventStateMachine stateMachine;
    private final ObserverProfile profile;
    private final CameraBackend camera;
    private final List<ObservationEventListener> eventListeners;
    private final PipelineSafetyGuards safetyGuards;

    private volatile boolean active = false;

    /**
     * Backward-compatible constructor that disables rate limiting and uses the
     * default event-log capacity. New callers should prefer the constructor
     * that takes an explicit {@link PipelineSafetyGuards} instance.
     */
    public ObserverPipeline(CameraBackend camera,
                             SceneMonitor sceneMonitor,
                             CaptureOrchestrator captureOrchestrator,
                             VisionProviderPort visionProvider,
                             EventStateMachine stateMachine,
                             ObserverProfile profile,
                             List<ObservationEventListener> eventListeners) {
        this(camera, sceneMonitor, captureOrchestrator, visionProvider,
                stateMachine, profile, eventListeners, new PipelineSafetyGuards(0));
    }

    public ObserverPipeline(CameraBackend camera,
                             SceneMonitor sceneMonitor,
                             CaptureOrchestrator captureOrchestrator,
                             VisionProviderPort visionProvider,
                             EventStateMachine stateMachine,
                             ObserverProfile profile,
                             List<ObservationEventListener> eventListeners,
                             PipelineSafetyGuards safetyGuards) {
        this.camera = camera;
        this.sceneMonitor = sceneMonitor;
        this.captureOrchestrator = captureOrchestrator;
        this.visionProvider = visionProvider;
        this.stateMachine = stateMachine;
        this.profile = profile;
        this.eventListeners = new ArrayList<>(eventListeners);
        this.safetyGuards = java.util.Objects.requireNonNull(safetyGuards, "safetyGuards");
    }

    /** Returns the safety guards bound to this pipeline. Never {@code null}. */
    public PipelineSafetyGuards getSafetyGuards() {
        return safetyGuards;
    }

    /**
     * Processes a single preview frame through the pipeline.
     * No-op when the pipeline is not active or when an event is already in progress.
     */
    public void processFrame(LiveViewFrame frame) {
        if (!active) {
            return;
        }
        if (stateMachine.getState() != EventState.IDLE) {
            return; // Event in progress
        }

        // Per-frame OBSERVE events would churn the bounded log on high-FPS
        // live view, evicting useful entries (triggers/captures/blocks). Only
        // log significant pipeline events from here on.

        Optional<TriggerContext> trigger = sceneMonitor.analyzeFrame(frame);
        if (trigger.isEmpty()) {
            return;
        }

        // Production-safety gate: emergency stop / connection / rate limit.
        // Read camera state once: avoid NPE / inconsistent snapshot if the
        // backend transitions between calls.
        var cameraState = camera.getState();
        boolean connected = cameraState != null && cameraState.connected();
        Optional<PipelineBlockerKind> blocker = safetyGuards.checkBeforeAction(connected);
        if (blocker.isPresent()) {
            log.warn("Observer trigger blocked: kind={} camera={}", blocker.get(), camera.getInfo().id());
            sceneMonitor.resetAfterEvent(frame.data());
            return;
        }

        safetyGuards.recordEvent(PipelineEvent.decide("Trigger fired: mode=" + profile.mode()));

        log.info("Observer triggered: mode={} profile={} camera={}", profile.mode(), profile.name(),
                camera.getInfo().id());

        try {
            // 1. Motion detected → tracking
            stateMachine.motionDetected("motion", profile.defaultRoi());
            stateMachine.startTracking();

            // 2. Capture
            stateMachine.startCapturing();
            CaptureSeriesResult series = captureOrchestrator.captureSeries(trigger.get());
            stateMachine.imagesCaptured(series.allImages());
            stateMachine.bestImageSelected(series.bestImage().id());
            safetyGuards.recordCapture();
            safetyGuards.recordEvent(PipelineEvent.act("Captured " + series.allImages().size()
                    + " image(s); best=" + series.bestImage().id()));

            // 3. AI analysis
            VisionResultData aiResult = classify(series);

            // Only a successful negative detection discards a capture.
            if (!"none".equals(aiResult.primaryLabel())) {
                stateMachine.analysisComplete(aiResult);
                ObservationEvent event = stateMachine.getCurrentEvent();
                safetyGuards.recordEvent(PipelineEvent.act("Event completed: label="
                        + aiResult.primaryLabel()));
                notifyCompleted(event);
            } else {
                String reason = "No subject detected by AI";
                stateMachine.discard(reason);
                ObservationEvent event = stateMachine.getCurrentEvent();
                safetyGuards.recordEvent(PipelineEvent.act("Event discarded: " + reason));
                notifyDiscarded(event);
            }

        } catch (Exception e) {
            log.error("Error in observer pipeline", e);
            PipelineBlockerKind kind = PipelineBlockerKind.blockerKindFor(e);
            safetyGuards.recordEvent(PipelineEvent.blocked(kind,
                    "Pipeline error: " + e.getMessage()));
            if (stateMachine.getState() != EventState.IDLE
                    && stateMachine.getState() != EventState.COMPLETED
                    && stateMachine.getState() != EventState.DISCARDED) {
                stateMachine.discard("Pipeline error: " + e.getMessage());
                notifyDiscarded(stateMachine.getCurrentEvent());
            }
        } finally {
            // Reset for next event
            sceneMonitor.resetAfterEvent(frame.data());
            stateMachine.reset(camera.getInfo().id(), profile.mode());
        }
    }

    private VisionResultData classify(CaptureSeriesResult series) {
        if (visionProvider == null) {
            return VisionResultData.unclassified(VisionFailureKind.NOT_CONFIGURED);
        }
        try {
            if (!visionProvider.isAvailable()) {
                return VisionResultData.unclassified(VisionFailureKind.TEMPORARY_UNAVAILABLE);
            }
            var result = visionProvider.classify(series.bestImage(), profile.mode(), profile.aiPromptTemplate());
            return result == null ? VisionResultData.unclassified(VisionFailureKind.MALFORMED_RESPONSE) : result;
        } catch (RuntimeException failure) {
            // External providers may include prompts, credentials or image details in exception messages.
            return VisionResultData.unclassified(VisionFailureKind.UNKNOWN);
        }
    }

    public void start() {
        active = true;
        log.info("ObserverPipeline started: profile={}", profile.name());
    }

    public void stop() {
        active = false;
        log.info("ObserverPipeline stopped: profile={}", profile.name());
    }

    public boolean isActive() {
        return active;
    }

    public void addListener(ObservationEventListener listener) {
        eventListeners.add(listener);
    }

    private void notifyCompleted(ObservationEvent event) {
        for (ObservationEventListener listener : eventListeners) {
            try {
                listener.onEventCompleted(event);
            } catch (Exception e) {
                log.error("Listener error on event completed", e);
            }
        }
    }

    private void notifyDiscarded(ObservationEvent event) {
        for (ObservationEventListener listener : eventListeners) {
            try {
                listener.onEventDiscarded(event);
            } catch (Exception e) {
                log.error("Listener error on event discarded", e);
            }
        }
    }
}
