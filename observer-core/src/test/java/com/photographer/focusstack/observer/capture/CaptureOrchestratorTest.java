package com.photographer.focusstack.observer.capture;

import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.camera.CameraInfo;
import com.photographer.focusstack.common.camera.CameraState;
import com.photographer.focusstack.common.camera.CaptureResult;
import com.photographer.focusstack.common.camera.FocusCommand;
import com.photographer.focusstack.common.camera.LiveViewFrame;
import com.photographer.focusstack.common.camera.Operation;
import com.photographer.focusstack.observer.events.RegionOfInterest;
import com.photographer.focusstack.observer.monitoring.SharpnessEvaluator;
import com.photographer.focusstack.observer.profile.ObserverProfile;
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

class CaptureOrchestratorTest {

    private StubCameraBackend camera;
    private SharpnessEvaluator sharpnessEvaluator;
    private TriggerContext trigger;

    @BeforeEach
    void setUp() {
        camera = new StubCameraBackend();
        // Use default SharpnessEvaluator; override its behaviour via varied image bytes
        sharpnessEvaluator = new SharpnessEvaluator();
        trigger = new TriggerContext(Instant.now(), 0.5, 0.1,
                RegionOfInterest.fullFrame(), null, "wildlife", Map.of());
    }

    @Test
    void wildlifeMode_takesCorrectNumberOfBurstShots() {
        ObserverProfile profile = ObserverProfile.birdhouse(); // burstCount = 3
        CaptureOrchestrator orchestrator = new CaptureOrchestrator(
                camera, sharpnessEvaluator, profile, ms -> {});

        CaptureSeriesResult result = orchestrator.captureSeries(trigger);

        assertEquals(profile.burstCount(), result.allImages().size(),
                "Wildlife mode should capture exactly burstCount images");
        assertEquals(profile.burstCount(), camera.getCaptureCount());
    }

    @Test
    void wildlifeMode_selectsBestSharpnessImage() {
        // Use a custom SharpnessEvaluator that returns known scores based on call order
        AtomicInteger callIndex = new AtomicInteger(0);
        double[] scores = {0.3, 0.9, 0.5};
        SharpnessEvaluator scoring = new SharpnessEvaluator() {
            @Override
            public double evaluate(byte[] imageBytes, RegionOfInterest roi) {
                return scores[callIndex.getAndIncrement() % scores.length];
            }
        };

        ObserverProfile profile = ObserverProfile.birdhouse(); // burstCount = 3
        CaptureOrchestrator orchestrator = new CaptureOrchestrator(
                camera, scoring, profile, ms -> {});

        CaptureSeriesResult result = orchestrator.captureSeries(trigger);
        assertTrue(result.bestImage().isBestShot(), "Best image should be marked as isBestShot");
        // The best image should have sharpness score 0.9
        assertEquals(0.9, result.bestImage().sharpnessScore(), 0.001);
    }

    @Test
    void macroMode_withFocusBracketing_sendsFocusCommands() {
        ObserverProfile profile = ObserverProfile.macroFlower(); // focusBracketing=true, steps=3
        assertTrue(profile.focusBracketing());

        CaptureOrchestrator orchestrator = new CaptureOrchestrator(
                camera, sharpnessEvaluator, profile, ms -> {});

        orchestrator.captureSeries(trigger);

        assertEquals(profile.focusBracketingSteps(), camera.getCaptureCount(),
                "Should capture focusBracketingSteps images");
        // One focus command per step + one return command
        assertEquals(profile.focusBracketingSteps() + 1, camera.getFocusCommandCount(),
                "Should send steps+1 focus commands (forward steps + return)");
    }

    @Test
    void macroMode_withFocusBracketing_returnsFocusToOriginalPosition() {
        ObserverProfile profile = ObserverProfile.macroFlower(); // steps=3
        CaptureOrchestrator orchestrator = new CaptureOrchestrator(
                camera, sharpnessEvaluator, profile, ms -> {});
        orchestrator.captureSeries(trigger);

        // Verify that the last focus command moves NEAR by the number of steps
        FocusCommand returnCommand = camera.getLastFocusCommand();
        assertNotNull(returnCommand);
        assertEquals(FocusCommand.Direction.NEAR, returnCommand.direction(),
                "Last focus command should return to original position (NEAR)");
        assertEquals(profile.focusBracketingSteps(), returnCommand.steps(),
                "Return command should move back by focusBracketingSteps steps");
    }

    @Test
    void macroMode_withoutFocusBracketing_doesNotSendFocusCommands() {
        ObserverProfile profile = new ObserverProfile(
                "test", "macro_pollinator",
                200, 0.08, 0.02, RegionOfInterest.fullFrame(),
                3, false, 0,
                10, "08:00", "19:00", 300,
                null, "gemini", "manual", "jpeg");

        CaptureOrchestrator orchestrator = new CaptureOrchestrator(
                camera, sharpnessEvaluator, profile, ms -> {});
        orchestrator.captureSeries(trigger);

        assertEquals(0, camera.getFocusCommandCount(), "No focus commands without focusBracketing");
        assertEquals(3, camera.getCaptureCount());
    }

    // ---- stub ----

    static class StubCameraBackend implements CameraBackend {
        private final AtomicInteger captureCount = new AtomicInteger(0);
        private final List<FocusCommand> focusCommands = new ArrayList<>();

        @Override
        public CameraInfo getInfo() {
            return new CameraInfo("stub", "Stub Camera", "StubBackend", "mock", "localhost", "1.0");
        }

        @Override
        public LiveViewFrame getLiveView() {
            return new LiveViewFrame(new byte[]{50}, 1, 1, Instant.now(), "image/jpeg");
        }

        @Override
        public CaptureResult capture() {
            int n = captureCount.incrementAndGet();
            byte[] data = new byte[]{(byte) (n * 50)};
            return new CaptureResult(UUID.randomUUID().toString(), "img" + n + ".jpg",
                    "/tmp/img" + n + ".jpg", Instant.now(), n, data.length, data);
        }

        @Override
        public void setFocus(FocusCommand command) {
            focusCommands.add(command);
        }

        @Override
        public CameraState getState() {
            return new CameraState(true, 100, 1024L * 1024 * 1024, 0, "1/125", "f/5.6", "ISO400", true);
        }

        @Override
        public boolean supports(Operation operation) {
            return true;
        }

        int getCaptureCount() { return captureCount.get(); }
        int getFocusCommandCount() { return focusCommands.size(); }
        FocusCommand getLastFocusCommand() {
            return focusCommands.isEmpty() ? null : focusCommands.get(focusCommands.size() - 1);
        }
    }
}
