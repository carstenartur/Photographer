package com.photographer.focusstack.observer.pipeline;

import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.camera.CameraInfo;
import com.photographer.focusstack.common.camera.CameraState;
import com.photographer.focusstack.common.camera.CaptureResult;
import com.photographer.focusstack.common.camera.FocusCommand;
import com.photographer.focusstack.common.camera.LiveViewFrame;
import com.photographer.focusstack.common.camera.Operation;
import com.photographer.focusstack.observer.capture.CaptureOrchestrator;
import com.photographer.focusstack.observer.events.EventStateMachine;
import com.photographer.focusstack.observer.events.ObservationEvent;
import com.photographer.focusstack.observer.events.RegionOfInterest;
import com.photographer.focusstack.observer.events.VisionResultData;
import com.photographer.focusstack.observer.monitoring.MotionDetector;
import com.photographer.focusstack.observer.monitoring.SceneMonitor;
import com.photographer.focusstack.observer.monitoring.SharpnessEvaluator;
import com.photographer.focusstack.observer.profile.ObserverProfile;
import com.photographer.focusstack.observer.rules.MotionThresholdRule;
import com.photographer.focusstack.observer.rules.RulesEngine;
import com.photographer.focusstack.observer.rules.TriggerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ObserverPipelineTest {

    private static final byte[] MOTION_FRAME  = new byte[]{(byte) 200, (byte) 200};
    private static final byte[] STATIC_FRAME  = new byte[]{0, 0};

    private StubCamera camera;
    private ObserverProfile profile;
    private List<ObservationEvent> completedEvents;
    private List<ObservationEvent> discardedEvents;

    @BeforeEach
    void setUp() {
        camera = new StubCamera();
        profile = ObserverProfile.birdhouse();
        completedEvents = new ArrayList<>();
        discardedEvents = new ArrayList<>();
    }

    /** Frame with motion → capture → AI classify → event COMPLETED. */
    @Test
    void frameWithMotion_producesCompletedEvent() {
        // Motion detector returns high score for any non-zero comparison
        MotionDetector motionDetector = new MotionDetector() {
            @Override
            public double detect(byte[] reference, byte[] current, RegionOfInterest roi) {
                double diff = 0;
                for (int i = 0; i < Math.min(reference.length, current.length); i++) {
                    diff += Math.abs((reference[i] & 0xFF) - (current[i] & 0xFF)) / 255.0;
                }
                return reference.length > 0 ? diff / reference.length : 0.0;
            }
            @Override
            public double estimateObjectSize(byte[] frame, RegionOfInterest roi) { return 0.1; }
        };
        RulesEngine rules = new RulesEngine(List.of(new MotionThresholdRule(0.1)));
        SceneMonitor monitor = new SceneMonitor(motionDetector, rules, profile);

        VisionProviderPort aiProvider = makeAiProvider("Kohlmeise", 0.9);
        ObserverPipeline pipeline = buildPipeline(monitor, aiProvider);
        pipeline.start();

        // Feed a static reference frame, then a motion frame
        pipeline.processFrame(frame(STATIC_FRAME));  // initialises reference
        pipeline.processFrame(frame(MOTION_FRAME));  // triggers

        assertEquals(1, completedEvents.size(), "Should produce exactly one completed event");
        assertEquals("Kohlmeise", completedEvents.get(0).aiResult().primaryLabel());
    }

    /** Frame without motion → no event. */
    @Test
    void frameWithoutMotion_producesNoEvent() {
        MotionDetector noMotion = new MotionDetector() {
            @Override public double detect(byte[] ref, byte[] cur, RegionOfInterest roi) { return 0.0; }
            @Override public double estimateObjectSize(byte[] frame, RegionOfInterest roi) { return 0.0; }
        };
        RulesEngine rules = new RulesEngine(List.of(new MotionThresholdRule(0.1)));
        SceneMonitor monitor = new SceneMonitor(noMotion, rules, profile);

        VisionProviderPort aiProvider = makeAiProvider("Kohlmeise", 0.9);
        ObserverPipeline pipeline = buildPipeline(monitor, aiProvider);
        pipeline.start();

        pipeline.processFrame(frame(STATIC_FRAME));
        pipeline.processFrame(frame(STATIC_FRAME));
        pipeline.processFrame(frame(STATIC_FRAME));

        assertTrue(completedEvents.isEmpty(), "No event when motion threshold not exceeded");
        assertTrue(discardedEvents.isEmpty());
    }

    /** AI returns "none" → event DISCARDED. */
    @Test
    void aiReturnsNone_eventDiscarded() {
        MotionDetector alwaysMotion = new MotionDetector() {
            @Override public double detect(byte[] ref, byte[] cur, RegionOfInterest roi) { return 1.0; }
            @Override public double estimateObjectSize(byte[] frame, RegionOfInterest roi) { return 0.1; }
        };
        RulesEngine rules = new RulesEngine(List.of(new MotionThresholdRule(0.1)));
        SceneMonitor monitor = new SceneMonitor(alwaysMotion, rules, profile);

        VisionProviderPort aiNone = makeAiProvider("none", 0.0);
        ObserverPipeline pipeline = buildPipeline(monitor, aiNone);
        pipeline.start();

        pipeline.processFrame(frame(STATIC_FRAME));  // initialise reference
        pipeline.processFrame(frame(MOTION_FRAME));  // trigger

        assertTrue(completedEvents.isEmpty(), "Event should be discarded when AI returns 'none'");
        assertEquals(1, discardedEvents.size());
    }

    /** Pipeline is stopped → processFrame is a no-op. */
    @Test
    void stoppedPipeline_ignoresFrames() {
        MotionDetector alwaysMotion = new MotionDetector() {
            @Override public double detect(byte[] ref, byte[] cur, RegionOfInterest roi) { return 1.0; }
            @Override public double estimateObjectSize(byte[] frame, RegionOfInterest roi) { return 0.1; }
        };
        RulesEngine rules = new RulesEngine(List.of(new MotionThresholdRule(0.0)));
        SceneMonitor monitor = new SceneMonitor(alwaysMotion, rules, profile);

        VisionProviderPort ai = makeAiProvider("Kohlmeise", 0.9);
        ObserverPipeline pipeline = buildPipeline(monitor, ai);
        // Do NOT call start()

        pipeline.processFrame(frame(STATIC_FRAME));
        pipeline.processFrame(frame(MOTION_FRAME));

        assertTrue(completedEvents.isEmpty(), "Stopped pipeline should not produce events");
    }

    // ---- helpers ----

    private ObserverPipeline buildPipeline(SceneMonitor monitor, VisionProviderPort ai) {
        SharpnessEvaluator sharp = new SharpnessEvaluator();
        CaptureOrchestrator orchestrator = new CaptureOrchestrator(
                camera, sharp, profile, ms -> {}); // no-op sleeper
        EventStateMachine stateMachine = new EventStateMachine();
        stateMachine.reset(camera.getInfo().id(), profile.mode());

        ObserverPipeline pipeline = new ObserverPipeline(
                camera, monitor, orchestrator, ai, stateMachine, profile, List.of());

        pipeline.addListener(new ObservationEventListener() {
            @Override
            public void onEventCompleted(ObservationEvent event) {
                completedEvents.add(event);
            }

            @Override
            public void onEventDiscarded(ObservationEvent event) {
                discardedEvents.add(event);
            }
        });

        return pipeline;
    }

    private static VisionProviderPort makeAiProvider(String label, double confidence) {
        return new VisionProviderPort() {
            @Override
            public VisionResultData classify(
                    com.photographer.focusstack.observer.events.CapturedImage image,
                    String mode, String promptTemplate) {
                return new VisionResultData("mock", "m", "classification", label,
                        List.of(), confidence, null, List.of(), null, 0L);
            }

            @Override
            public boolean isAvailable() { return true; }

            @Override
            public String name() { return "test-mock"; }
        };
    }

    private static LiveViewFrame frame(byte[] data) {
        return new LiveViewFrame(data, 1, 1, Instant.now(), "image/jpeg");
    }

    // ---- stub camera ----

    static class StubCamera implements CameraBackend {
        private final AtomicInteger captureIndex = new AtomicInteger(0);

        @Override
        public CameraInfo getInfo() {
            return new CameraInfo("stub-cam", "Stub Camera", "Stub", "mock", "localhost", "1.0");
        }

        @Override
        public LiveViewFrame getLiveView() {
            return new LiveViewFrame(new byte[]{1}, 1, 1, Instant.now(), "image/jpeg");
        }

        @Override
        public CaptureResult capture() {
            int n = captureIndex.incrementAndGet();
            byte[] data = new byte[]{(byte) (n * 10)};
            return new CaptureResult(UUID.randomUUID().toString(), "img" + n + ".jpg",
                    "/tmp/img" + n + ".jpg", Instant.now(), n, data.length, data);
        }

        @Override
        public void setFocus(FocusCommand command) { /* no-op */ }

        @Override
        public CameraState getState() {
            return new CameraState(true, 100, 1024L * 1024, 0, "1/125", "f/5.6", "ISO400", true);
        }

        @Override
        public boolean supports(Operation operation) { return true; }
    }
}
