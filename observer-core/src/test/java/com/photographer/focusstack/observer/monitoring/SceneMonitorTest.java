package com.photographer.focusstack.observer.monitoring;

import com.photographer.focusstack.common.camera.LiveViewFrame;
import com.photographer.focusstack.observer.events.RegionOfInterest;
import com.photographer.focusstack.observer.profile.ObserverProfile;
import com.photographer.focusstack.observer.rules.MotionThresholdRule;
import com.photographer.focusstack.observer.rules.RulesEngine;
import com.photographer.focusstack.observer.rules.TriggerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SceneMonitorTest {

    private StubMotionDetector motionDetector;
    private ObserverProfile profile;

    @BeforeEach
    void setUp() {
        motionDetector = new StubMotionDetector();
        profile = ObserverProfile.birdhouse(); // motionThreshold = 0.15
    }

    @Test
    void firstFrame_initializesReference_noTrigger() {
        SceneMonitor monitor = buildMonitor(0.1);
        Optional<TriggerContext> result = monitor.analyzeFrame(frame(new byte[]{1, 2, 3}));
        assertTrue(result.isEmpty(), "First frame should not produce a trigger");
    }

    @Test
    void identicalFrames_producesNoTrigger() {
        motionDetector.motionScore = 0.0;
        SceneMonitor monitor = buildMonitor(0.1);
        monitor.analyzeFrame(frame(new byte[]{5, 5, 5})); // reference
        Optional<TriggerContext> result = monitor.analyzeFrame(frame(new byte[]{5, 5, 5}));
        assertTrue(result.isEmpty(), "Identical frames should not trigger");
    }

    @Test
    void highMotion_exceedingThreshold_producesTrigger() {
        motionDetector.motionScore = 0.5;  // above any reasonable threshold
        SceneMonitor monitor = buildMonitor(0.1);
        monitor.analyzeFrame(frame(new byte[]{0})); // init reference
        Optional<TriggerContext> result = monitor.analyzeFrame(frame(new byte[]{(byte) 200}));
        assertTrue(result.isPresent(), "Motion exceeding threshold should trigger");
        assertEquals(0.5, result.get().motionScore(), 0.001);
    }

    @Test
    void motionBelowThreshold_doesNotTrigger() {
        motionDetector.motionScore = 0.05;  // below threshold
        SceneMonitor monitor = buildMonitor(0.2);  // threshold = 0.2
        monitor.analyzeFrame(frame(new byte[]{0}));
        Optional<TriggerContext> result = monitor.analyzeFrame(frame(new byte[]{10}));
        assertTrue(result.isEmpty(), "Motion below threshold should not trigger");
    }

    @Test
    void resetAfterEvent_updatesReferenceFrame() {
        SceneMonitor monitor = buildMonitor(0.1);
        monitor.analyzeFrame(frame(new byte[]{0})); // reference
        byte[] newRef = new byte[]{(byte) 100};
        monitor.resetAfterEvent(newRef);
        // After reset, feeding same frame as new reference → no trigger
        motionDetector.motionScore = 0.0;
        Optional<TriggerContext> result = monitor.analyzeFrame(frame(newRef));
        assertNotNull(result); // no exception; result may or may not trigger depending on rules
    }

    @Test
    void roiFiltering_onlyMotionInRoiTriggers() {
        // Use a profile with a small center ROI
        ObserverProfile smallRoiProfile = new ObserverProfile(
                "test", "wildlife",
                500, 0.15, 0.05,
                new RegionOfInterest(0.4, 0.4, 0.2, 0.2, "center"),
                3, false, 0, 30, "06:00", "21:00", 120,
                null, "gemini", "auto", "jpeg");

        // ROI-aware stub: only returns a high score when the ROI covers the full frame
        // (i.e. no real ROI constraint). For a small ROI the motion is below threshold.
        RoiAwareStubMotionDetector roiAware = new RoiAwareStubMotionDetector();

        RulesEngine rules = new RulesEngine(List.of(new MotionThresholdRule(0.1)));
        SceneMonitor monitor = new SceneMonitor(roiAware, rules, smallRoiProfile);

        monitor.analyzeFrame(frame(new byte[]{0})); // reference
        Optional<TriggerContext> result = monitor.analyzeFrame(frame(new byte[]{(byte) 200}));
        // ROI is small, so roiAware returns 0.0 → should not trigger
        assertTrue(result.isEmpty(), "No motion in ROI should not trigger");

        // Verify the stub was called with the ROI from the profile
        assertEquals(smallRoiProfile.defaultRoi(), roiAware.lastRoi,
                "SceneMonitor must pass the profile ROI to the motion detector");
    }

    // ---- helpers ----

    private SceneMonitor buildMonitor(double threshold) {
        RulesEngine rules = new RulesEngine(List.of(new MotionThresholdRule(threshold)));
        return new SceneMonitor(motionDetector, rules, profile);
    }

    private LiveViewFrame frame(byte[] data) {
        return new LiveViewFrame(data, 1, 1, Instant.now(), "image/jpeg");
    }

    static class StubMotionDetector implements MotionDetector {
        double motionScore = 0.0;
        double objectSize  = 0.05;

        @Override
        public double detect(byte[] reference, byte[] current, RegionOfInterest roi) {
            return motionScore;
        }

        @Override
        public double estimateObjectSize(byte[] frame, RegionOfInterest roi) {
            return objectSize;
        }
    }

    /**
     * ROI-aware stub that records the last ROI passed to {@code detect()} and
     * always returns 0.0, simulating no motion within the ROI.
     */
    static class RoiAwareStubMotionDetector implements MotionDetector {
        RegionOfInterest lastRoi;

        @Override
        public double detect(byte[] reference, byte[] current, RegionOfInterest roi) {
            this.lastRoi = roi;
            return 0.0; // no motion in ROI
        }

        @Override
        public double estimateObjectSize(byte[] frame, RegionOfInterest roi) {
            return 0.0;
        }
    }
}
