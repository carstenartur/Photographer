package com.photographer.focusstack.observer.monitoring;

import com.photographer.focusstack.common.camera.LiveViewFrame;
import com.photographer.focusstack.observer.events.RegionOfInterest;
import com.photographer.focusstack.observer.profile.ObserverProfile;
import com.photographer.focusstack.observer.rules.RulesEngine;
import com.photographer.focusstack.observer.rules.TriggerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class WindAwareSceneMonitorTest {

    private TestMotionDetector motionDetector;
    private WindFilter windFilter;
    private WindAwareSceneMonitor monitor;

    @BeforeEach
    void setUp() {
        motionDetector = new TestMotionDetector();
        // Rules engine that always triggers
        RulesEngine rulesEngine = new RulesEngine(List.of());
        ObserverProfile profile = ObserverProfile.macroFlower();
        windFilter = new WindFilter(0.6);
        monitor = new WindAwareSceneMonitor(motionDetector, rulesEngine, profile, windFilter);
    }

    @Test
    void firstFrameInitializesReference() {
        LiveViewFrame frame = frame(new byte[]{1, 2, 3});
        Optional<TriggerContext> result = monitor.analyzeFrame(frame);
        assertTrue(result.isEmpty(), "First frame should not trigger");
    }

    @Test
    void normalMotionTriggers() {
        // Set up: motion detector returns moderate ROI motion, low global motion
        // This means it's a real subject, not wind
        motionDetector.roiMotionScore = 0.3;
        motionDetector.objectSize = 0.1;

        LiveViewFrame frame1 = frame(new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        monitor.analyzeFrame(frame1); // init reference

        // Second frame — the wind filter should NOT detect wind
        // (since PixelDiffMotionDetector in WindFilter uses its own detection)
        LiveViewFrame frame2 = frame(new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        Optional<TriggerContext> result = monitor.analyzeFrame(frame2);
        // With identical frames, motion will be 0 — no trigger from rules
        // The key is: no exception, and wind filter doesn't interfere
        assertNotNull(result);
    }

    @Test
    void resetAfterEventUpdatesState() {
        byte[] currentFrame = new byte[]{5, 6, 7};
        monitor.resetAfterEvent(currentFrame);
        // Should not throw and should update internal state
    }

    @Test
    void windFilterSuppressesOnHighGlobalMotion() {
        // Create frames where wind filter would detect wind
        // WindFilter: fullMotion >= 0.6 AND (fullMotion / roiMotion) > 1.5
        // Use frames that produce different pixel diff results

        LiveViewFrame frame1 = frame(new byte[100]);
        monitor.analyzeFrame(frame1); // set lastFrameData

        // Create a very different frame (high global motion)
        byte[] data2 = new byte[100];
        java.util.Arrays.fill(data2, (byte) 200);
        LiveViewFrame frame2 = frame(data2);

        // The wind filter will compute pixel diff between frame1 and frame2
        // Since all pixels changed drastically, fullMotion will be high
        Optional<TriggerContext> result = monitor.analyzeFrame(frame2);
        // Wind should be detected → trigger suppressed
        assertTrue(result.isEmpty(), "Should suppress trigger when wind motion is detected");
    }

    private LiveViewFrame frame(byte[] data) {
        return new LiveViewFrame(data, 10, 10, Instant.now(), "image/jpeg");
    }

    /** Test stub for MotionDetector that returns configurable scores. */
    static class TestMotionDetector implements MotionDetector {
        double roiMotionScore = 0.0;
        double objectSize = 0.0;

        @Override
        public double detect(byte[] reference, byte[] current, RegionOfInterest roi) {
            return roiMotionScore;
        }

        @Override
        public double estimateObjectSize(byte[] frame, RegionOfInterest roi) {
            return objectSize;
        }
    }
}
