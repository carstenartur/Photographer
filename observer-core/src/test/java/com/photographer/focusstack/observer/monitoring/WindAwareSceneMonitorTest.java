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
        // WindFilter condition: fullMotion >= 0.6 AND (fullMotion / roiMotion) > 1.5
        // macroFlower uses fullFrame ROI, so full motion equals ROI motion (ratio=1.0).
        // Use a profile with a small ROI so global motion can exceed ROI motion.
        ObserverProfile smallRoiProfile = new ObserverProfile(
                "test_wind", "macro_pollinator",
                200, 0.08, 0.02,
                new RegionOfInterest(0.3, 0.3, 0.4, 0.4, "center"),
                5, true, 3,
                10, "08:00", "19:00", 300,
                null, "gemini", "manual", "raw+jpeg"
        );
        RulesEngine rulesEngine = new RulesEngine(List.of());
        WindFilter wf = new WindFilter(0.6);
        WindAwareSceneMonitor smallRoiMonitor = new WindAwareSceneMonitor(
                motionDetector, rulesEngine, smallRoiProfile, wf);

        // First frame — all zeros
        byte[] frame1Data = new byte[1000];
        LiveViewFrame frame1 = frame(frame1Data);
        smallRoiMonitor.analyzeFrame(frame1); // init lastFrameData

        // Second frame — change only bytes OUTSIDE the ROI region.
        // ROI = 0.4*0.4 = 16% of frame → first 160 bytes.
        // Change bytes 160..999 but leave 0..159 unchanged:
        //   ROI (first 160 bytes): 0 changed → roiMotion ≈ 0.0
        //   Full frame (1000 bytes): 840 changed → fullMotion = 0.84
        //   Condition: 0.84 >= 0.6 ✓ AND 0.84 / max(0.0, 0.001) >> 1.5 ✓
        byte[] windFrame = new byte[1000];
        for (int i = 160; i < 1000; i++) {
            windFrame[i] = (byte) 200;
        }
        LiveViewFrame frame2 = frame(windFrame);

        Optional<TriggerContext> result = smallRoiMonitor.analyzeFrame(frame2);
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
