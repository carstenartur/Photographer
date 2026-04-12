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
 * A scene monitor that integrates {@link WindFilter} to suppress false triggers
 * caused by wind-induced global motion (e.g., swaying branches, grass, flowers).
 *
 * <p>When wind motion is detected (high global motion relative to ROI motion),
 * the trigger is suppressed and the reference frame is updated to adapt to the
 * new background position.</p>
 *
 * <p>This is particularly important for macro photography profiles where the
 * camera is often positioned near vegetation that moves in wind.</p>
 */
public class WindAwareSceneMonitor extends SceneMonitor {

    private static final Logger log = LoggerFactory.getLogger(WindAwareSceneMonitor.class);

    private final WindFilter windFilter;
    private final ObserverProfile profile;
    private byte[] lastFrameData;

    /**
     * Creates a wind-aware scene monitor.
     *
     * @param motionDetector the motion detector
     * @param rulesEngine    the rules engine for trigger evaluation
     * @param profile        the active observer profile
     * @param windFilter     the wind filter instance
     */
    public WindAwareSceneMonitor(MotionDetector motionDetector, RulesEngine rulesEngine,
                                  ObserverProfile profile, WindFilter windFilter) {
        super(motionDetector, rulesEngine, profile);
        this.windFilter = windFilter;
        this.profile = profile;
    }

    /**
     * Analyzes a preview frame, filtering out wind-induced motion before delegating
     * to the standard scene monitor logic.
     *
     * @return trigger context if a real subject is detected, empty if wind or no trigger
     */
    @Override
    public Optional<TriggerContext> analyzeFrame(LiveViewFrame frame) {
        if (lastFrameData != null
                && windFilter.isWindMotion(lastFrameData, frame.data(), profile.defaultRoi())) {
            log.debug("Wind motion detected — suppressing trigger and updating reference");
            updateReference(frame.data());
            lastFrameData = frame.data();
            return Optional.empty();
        }
        lastFrameData = frame.data();
        return super.analyzeFrame(frame);
    }

    @Override
    public void resetAfterEvent(byte[] currentFrame) {
        super.resetAfterEvent(currentFrame);
        this.lastFrameData = currentFrame;
    }
}
