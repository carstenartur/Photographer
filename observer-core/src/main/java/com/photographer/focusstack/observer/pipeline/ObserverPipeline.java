package com.photographer.focusstack.observer.pipeline;

import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.camera.LiveViewFrame;
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

    private volatile boolean active = false;

    public ObserverPipeline(CameraBackend camera,
                             SceneMonitor sceneMonitor,
                             CaptureOrchestrator captureOrchestrator,
                             VisionProviderPort visionProvider,
                             EventStateMachine stateMachine,
                             ObserverProfile profile,
                             List<ObservationEventListener> eventListeners) {
        this.camera = camera;
        this.sceneMonitor = sceneMonitor;
        this.captureOrchestrator = captureOrchestrator;
        this.visionProvider = visionProvider;
        this.stateMachine = stateMachine;
        this.profile = profile;
        this.eventListeners = new ArrayList<>(eventListeners);
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

        Optional<TriggerContext> trigger = sceneMonitor.analyzeFrame(frame);
        if (trigger.isEmpty()) {
            return;
        }

        log.info("Observer triggered: mode={} camera={}", profile.mode(), profile.name());

        try {
            // 1. Motion detected → tracking
            stateMachine.motionDetected(trigger.get().mode(), profile.defaultRoi());
            stateMachine.startTracking();

            // 2. Capture
            stateMachine.startCapturing();
            CaptureSeriesResult series = captureOrchestrator.captureSeries(trigger.get());
            stateMachine.imagesCaptured(series.allImages());
            stateMachine.bestImageSelected(series.bestImage().id());

            // 3. AI analysis
            VisionResultData aiResult = null;
            if (visionProvider != null && visionProvider.isAvailable()) {
                aiResult = visionProvider.classify(series.bestImage(), profile.mode(), profile.aiPromptTemplate());
            }

            // 4. Complete or discard
            if (aiResult != null && !"none".equals(aiResult.primaryLabel())) {
                stateMachine.analysisComplete(aiResult);
                ObservationEvent event = stateMachine.getCurrentEvent();
                notifyCompleted(event);
            } else {
                String reason = aiResult != null ? "No subject detected by AI" : "AI unavailable";
                stateMachine.discard(reason);
                ObservationEvent event = stateMachine.getCurrentEvent();
                notifyDiscarded(event);
            }

        } catch (Exception e) {
            log.error("Error in observer pipeline", e);
            if (stateMachine.getState() != EventState.IDLE
                    && stateMachine.getState() != EventState.COMPLETED
                    && stateMachine.getState() != EventState.DISCARDED) {
                stateMachine.discard("Pipeline error: " + e.getMessage());
                notifyDiscarded(stateMachine.getCurrentEvent());
            }
        } finally {
            // Reset for next event
            sceneMonitor.resetAfterEvent(frame.data());
            stateMachine.reset(profile.name(), profile.mode());
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
