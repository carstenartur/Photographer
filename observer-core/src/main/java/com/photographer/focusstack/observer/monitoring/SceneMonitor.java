package com.photographer.focusstack.observer.monitoring;

import com.photographer.focusstack.observer.profile.ObserverProfile;
import com.photographer.focusstack.observer.rules.RulesEngine;
import com.photographer.focusstack.observer.rules.TriggerContext;
import com.photographer.focusstack.common.camera.LiveViewFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Monitors a stream of live-view frames and decides when to trigger an observation event.
 *
 * <p>Call {@link #analyzeFrame(LiveViewFrame)} for each incoming preview frame.
 * The monitor maintains an internal reference frame for motion comparison.</p>
 */
public class SceneMonitor {

    private static final Logger log = LoggerFactory.getLogger(SceneMonitor.class);

    private final MotionDetector motionDetector;
    private final RulesEngine rulesEngine;
    private final ObserverProfile profile;

    private byte[] referenceFrame;
    private Instant lastEventTime;

    public SceneMonitor(MotionDetector motionDetector, RulesEngine rulesEngine, ObserverProfile profile) {
        this.motionDetector = motionDetector;
        this.rulesEngine = rulesEngine;
        this.profile = profile;
    }

    /**
     * Analyzes a new preview frame.
     *
     * @return an Optional containing a TriggerContext if an event should be triggered,
     *         or empty if no trigger should occur
     */
    public Optional<TriggerContext> analyzeFrame(LiveViewFrame frame) {
        if (referenceFrame == null) {
            referenceFrame = frame.data();
            return Optional.empty();
        }

        double motionScore = motionDetector.detect(referenceFrame, frame.data(), profile.defaultRoi());
        double objectSize = motionDetector.estimateObjectSize(frame.data(), profile.defaultRoi());

        TriggerContext ctx = new TriggerContext(
                Instant.now(),
                motionScore,
                objectSize,
                profile.defaultRoi(),
                lastEventTime,
                profile.mode(),
                Map.of()
        );

        if (rulesEngine.shouldTrigger(ctx)) {
            log.debug("SceneMonitor triggered: motionScore={} objectSize={}", motionScore, objectSize);
            return Optional.of(ctx);
        }
        return Optional.empty();
    }

    /** Updates the reference frame used as background model. */
    public void updateReference(byte[] frameData) {
        this.referenceFrame = frameData;
    }

    /** Records the time of the last completed event (for cooldown computation). */
    public void recordEventTime(Instant time) {
        this.lastEventTime = time;
    }

    /** Resets state after an event (updates reference frame and records time). */
    public void resetAfterEvent(byte[] currentFrame) {
        this.referenceFrame = currentFrame;
        this.lastEventTime = Instant.now();
    }
}
