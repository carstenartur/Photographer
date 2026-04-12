package com.photographer.focusstack.camera.integration.realcamera;

import com.photographer.focusstack.camera.backend.CanonCcapiBackend;
import com.photographer.focusstack.camera.exception.CameraException;
import com.photographer.focusstack.common.camera.CaptureResult;
import com.photographer.focusstack.common.camera.FocusCommand;
import com.photographer.focusstack.common.camera.LiveViewFrame;
import com.photographer.focusstack.common.camera.Operation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real-camera integration tests that exercise the capture flow to reproduce
 * and validate the fix for the "503 Device busy" issue.
 *
 * <p>The original bug: the camera returns 503 "Device busy" on the very first
 * {@code shutterbutton/manual} {@code half_press} after a stacking job starts.
 * Root causes under investigation:
 * <ol>
 *   <li>{@code startLiveView} sends {@code cameradisplay: "on"}, which makes
 *       the camera display the preview on its own LCD — this may block the
 *       shutter button endpoint.</li>
 *   <li>No settle delay between {@code drivefocus} and {@code half_press} —
 *       the camera may still be moving the lens.</li>
 *   <li>Active live view may conflict with the shutter button sequence.</li>
 * </ol>
 *
 * <p>Run with:
 * <pre>
 *   mvn verify -pl app -am -Djava.awt.headless=true \
 *       -DexcludedGroups= -Dgroups=real-camera
 * </pre>
 */
@RealCamera
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CcapiCaptureFlowIT {

    private static final Logger log = LoggerFactory.getLogger(CcapiCaptureFlowIT.class);
    private static final String BASE_URL = CanonCcapiRealCameraIT.CAMERA_BASE_URL;

    private static CanonCcapiBackend backend;
    private static RestTemplate rest;

    @BeforeAll
    static void createBackend() throws Exception {
        rest = new RestTemplate();
        backend = new CanonCcapiBackend(BASE_URL, rest);
        log.info("CcapiCaptureFlowIT targeting {}", BASE_URL);
        // Force discovery so subsequent tests don't pay the cost
        backend.getInfo();
        // Ensure the camera is idle — previous test classes may have left
        // live view or recording active.
        waitForCameraIdle();
    }

    /**
     * Waits up to 30 seconds for the camera to become idle.
     * Stops live view (which blocks SD card access on the G5X Mark II),
     * then polls until the camera accepts SD card listing requests.
     */
    private static void waitForCameraIdle() throws Exception {
        // Stop live view — on G5X Mark II, active live view blocks contents/sd browsing
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                rest.postForObject(BASE_URL + "/ccapi/ver100/shooting/liveview",
                        java.util.Map.of("liveviewsize", "off", "cameradisplay", "on"), String.class);
                log.info("Live view stopped successfully");
                Thread.sleep(1000); // let camera settle after stopping LV
                break;
            } catch (org.springframework.web.client.HttpServerErrorException e) {
                log.debug("Stop live view attempt {}: {}", attempt + 1, e.getMessage());
                Thread.sleep(500);
            } catch (RestClientException e) {
                log.debug("Stop live view attempt {}: {}", attempt + 1, e.getMessage());
                break;
            }
        }

        // Poll contents/sd directly — only PASS when SD card is accessible
        for (int i = 0; i < 60; i++) {
            try {
                rest.getForObject(BASE_URL + "/ccapi/ver100/contents/sd", java.util.Map.class);
                log.info("Camera SD card accessible (waited {}ms)", i * 500);
                return;
            } catch (org.springframework.web.client.HttpServerErrorException e) {
                if (e.getStatusCode().value() == 503) {
                    // Still busy — try stopping live view again periodically
                    if (i > 0 && i % 4 == 0) {
                        try {
                            rest.postForObject(BASE_URL + "/ccapi/ver100/shooting/liveview",
                                    java.util.Map.of("liveviewsize", "off", "cameradisplay", "on"), String.class);
                        } catch (Exception ignored) { }
                    }
                    log.debug("Camera SD still busy, waiting... (attempt {})", i + 1);
                    Thread.sleep(500);
                } else {
                    throw e;
                }
            }
        }
        log.warn("Camera SD card may still be busy after 30s — tests may fail with 503");
    }

    @BeforeEach
    void logTest(TestInfo info) {
        log.info("▶ Running: {}", info.getDisplayName());
    }

    // ── Test 1: Capture immediately after live view is active ────────────

    @Test
    @Order(1)
    void captureImmediatelyAfterLiveViewStart() throws Exception {
        assumeTrue(backend.supports(Operation.CAPTURE), "Camera must support CAPTURE");
        assumeTrue(backend.supports(Operation.LIVE_VIEW), "Camera must support LIVE_VIEW");

        // Start live view explicitly — if already running (503 "During shooting or
        // recording"), that's fine: live view is already active.
        log.info("Starting live view (or confirming already active)...");
        try {
            backend.startLiveView("medium");
            Thread.sleep(500); // let camera initialise the LV stream
        } catch (com.photographer.focusstack.camera.exception.CameraException e) {
            if (e.getMessage().contains("During shooting or recording")) {
                log.info("Live view already active — continuing");
            } else {
                throw e;
            }
        }

        // Verify live view is actually producing frames
        LiveViewFrame frame = backend.getLiveView();
        assertNotNull(frame.data(), "Live view should be producing frames");
        assertTrue(frame.data().length > 100, "Live view frame should contain image data");
        log.info("Live view active, frame={} bytes", frame.data().length);

        // NOW capture — this is the scenario that triggers "Device busy"
        log.info("Attempting capture while live view is active...");
        Instant before = Instant.now();
        CaptureResult result = backend.capture();
        Duration elapsed = Duration.between(before, Instant.now());

        assertNotNull(result, "CaptureResult must not be null");
        assertNotNull(result.imageId(), "imageId must be set");
        log.info("Capture succeeded in {} ms: imageId={}, file={}",
                elapsed.toMillis(), result.imageId(), result.fileName());
    }

    // ── Test 2: Capture after setFocus with NO delay ────────────────────

    @Test
    @Order(2)
    void captureImmediatelyAfterSetFocusNoDelay() throws Exception {
        assumeTrue(backend.supports(Operation.CAPTURE), "Camera must support CAPTURE");
        assumeTrue(backend.supports(Operation.FOCUS_CONTROL), "Camera must support FOCUS_CONTROL");

        // This reproduces the StackingJobService flow:
        // setFocus() → capture() with no sleep between them
        log.info("Driving focus FAR 1 step...");
        backend.setFocus(new FocusCommand(FocusCommand.Direction.FAR, 1));
        // NO delay — this is what StackingJobService does
        log.info("Capturing immediately after setFocus (no delay)...");
        Instant before = Instant.now();
        CaptureResult result = backend.capture();
        Duration elapsed = Duration.between(before, Instant.now());

        assertNotNull(result, "CaptureResult must not be null after immediate focus+capture");
        log.info("Capture after setFocus succeeded in {} ms", elapsed.toMillis());

        // Return focus
        backend.setFocus(new FocusCommand(FocusCommand.Direction.NEAR, 1));
        Thread.sleep(300);
    }

    // ── Test 3: Capture after setFocus WITH settle delay ────────────────

    @Test
    @Order(3)
    void captureAfterSetFocusWithSettleDelay() throws Exception {
        assumeTrue(backend.supports(Operation.CAPTURE), "Camera must support CAPTURE");
        assumeTrue(backend.supports(Operation.FOCUS_CONTROL), "Camera must support FOCUS_CONTROL");

        log.info("Driving focus FAR 1 step...");
        backend.setFocus(new FocusCommand(FocusCommand.Direction.FAR, 1));
        Thread.sleep(500); // settle delay
        log.info("Capturing after 500ms settle delay...");
        Instant before = Instant.now();
        CaptureResult result = backend.capture();
        Duration elapsed = Duration.between(before, Instant.now());

        assertNotNull(result, "CaptureResult must not be null after settled focus+capture");
        log.info("Capture after settle delay succeeded in {} ms", elapsed.toMillis());

        // Return focus
        backend.setFocus(new FocusCommand(FocusCommand.Direction.NEAR, 1));
        Thread.sleep(300);
    }

    // ── Test 4: Rapid capture sequence (multiple frames back-to-back) ───

    @Test
    @Order(4)
    void rapidCaptureSequence() throws Exception {
        assumeTrue(backend.supports(Operation.CAPTURE), "Camera must support CAPTURE");

        int frameCount = 3;
        log.info("Starting rapid capture sequence of {} frames...", frameCount);

        long totalMs = 0;
        for (int i = 0; i < frameCount; i++) {
            Instant before = Instant.now();
            CaptureResult result = backend.capture();
            Duration elapsed = Duration.between(before, Instant.now());
            totalMs += elapsed.toMillis();

            assertNotNull(result, "Frame " + (i + 1) + " must not be null");
            assertNotNull(result.imageId(), "Frame " + (i + 1) + " imageId must be set");
            log.info("  Frame {}/{}: {} ms, imageId={}", i + 1, frameCount,
                    elapsed.toMillis(), result.imageId());
        }

        log.info("Rapid capture: {} frames in {} ms (avg {} ms/frame)",
                frameCount, totalMs, totalMs / frameCount);
    }

    // ── Test 5: Full stacking job flow (focus + capture interleaved) ────

    @Test
    @Order(5)
    void fullStackingJobFlow() throws Exception {
        assumeTrue(backend.supports(Operation.CAPTURE), "Camera must support CAPTURE");
        assumeTrue(backend.supports(Operation.FOCUS_CONTROL), "Camera must support FOCUS_CONTROL");

        // This reproduces exactly what StackingJobService.executeStackingAsync does:
        // 1. setFocus(startPosition)
        // 2. for each frame: capture() → setFocus(step)
        int stackSize = 3;
        log.info("Reproducing stacking job flow with {} frames...", stackSize);

        // Step 1: initial focus position (same as StackingJobService line 175)
        backend.setFocus(new FocusCommand(FocusCommand.Direction.FAR, 5));
        // Note: no delay — matches the bug scenario
        log.info("Initial focus set to FAR 5");

        for (int i = 0; i < stackSize; i++) {
            // Step 2: capture
            Instant before = Instant.now();
            CaptureResult result = backend.capture();
            Duration elapsed = Duration.between(before, Instant.now());
            assertNotNull(result, "Frame " + i + " must not be null");
            log.info("  Frame {}/{}: {} ms, imageId={}, focusPos={}",
                    i + 1, stackSize, elapsed.toMillis(),
                    result.imageId(), result.focusPosition());

            // Step 3: advance focus for next frame (same as StackingJobService)
            if (i < stackSize - 1) {
                backend.setFocus(new FocusCommand(FocusCommand.Direction.FAR, 1));
            }
        }

        // Return focus
        for (int i = 0; i < stackSize + 4; i++) {
            backend.setFocus(new FocusCommand(FocusCommand.Direction.NEAR, 1));
            Thread.sleep(200);
        }
        log.info("Full stacking job flow completed successfully");
    }

    // ── Test 6: Live view → capture → live view cycle ───────────────────

    @Test
    @Order(6)
    void liveViewCaptureInterleaved() throws Exception {
        assumeTrue(backend.supports(Operation.CAPTURE), "Camera must support CAPTURE");
        assumeTrue(backend.supports(Operation.LIVE_VIEW), "Camera must support LIVE_VIEW");

        log.info("Testing live view → capture → live view cycle...");

        // Get a live view frame
        LiveViewFrame lvBefore = backend.getLiveView();
        assertNotNull(lvBefore.data());
        log.info("Live view frame before capture: {} bytes", lvBefore.data().length);

        // Capture
        CaptureResult captureResult = backend.capture();
        assertNotNull(captureResult);
        log.info("Capture: imageId={}", captureResult.imageId());

        // Get another live view frame — camera should still be in LV mode
        Thread.sleep(500); // allow camera to recover after capture
        LiveViewFrame lvAfter = backend.getLiveView();
        assertNotNull(lvAfter.data());
        log.info("Live view frame after capture: {} bytes", lvAfter.data().length);
    }

    // ── Test 7: cameradisplay='on' vs 'off' diagnosis ───────────────────

    @Test
    @Order(7)
    void diagnoseCameraDisplayModeEffect() throws Exception {
        assumeTrue(backend.supports(Operation.CAPTURE), "Camera must support CAPTURE");
        assumeTrue(backend.supports(Operation.LIVE_VIEW), "Camera must support LIVE_VIEW");

        // Test with cameradisplay='on' (current behaviour — may cause 503).
        // If live view is already active, skip the start call.
        log.info("=== Testing with cameradisplay='on' (current behaviour) ===");
        try {
            backend.startLiveView("medium"); // current code sends cameradisplay='on'
            Thread.sleep(500);
        } catch (CameraException e) {
            if (e.getMessage().contains("During shooting or recording")) {
                log.info("Live view already active — skipping startLiveView");
            } else {
                throw e;
            }
        }

        try {
            Instant before = Instant.now();
            CaptureResult result = backend.capture();
            Duration elapsed = Duration.between(before, Instant.now());
            log.info("Capture with cameradisplay='on' succeeded in {} ms", elapsed.toMillis());
        } catch (CameraException e) {
            log.error("Capture with cameradisplay='on' FAILED: {}", e.getMessage());
            // Don't fail the test — this is a diagnostic
        }

        // Stop live view and restart with cameradisplay='off'
        log.info("=== Stopping live view and restarting with cameradisplay='off' ===");
        backend.startLiveView("off");
        Thread.sleep(500);
        backend.startLiveView("medium"); // restart — but we need a way to set cameradisplay='off'
        // Note: the current startLiveView() always sends cameradisplay='on',
        // so we cannot test 'off' without a code change.
        // This test documents the gap.
        log.info("Diagnostic complete — see logs for timing differences");
    }

    // ── Test 8: Settle delay validation ─────────────────────────────────

    @Test
    @Order(8)
    void settleDelayComparison() throws Exception {
        assumeTrue(backend.supports(Operation.CAPTURE), "Camera must support CAPTURE");
        assumeTrue(backend.supports(Operation.FOCUS_CONTROL), "Camera must support FOCUS_CONTROL");

        int[] delaysMs = {0, 100, 300, 500, 1000};
        log.info("Testing capture success rate with different settle delays after setFocus...");

        for (int delay : delaysMs) {
            backend.setFocus(new FocusCommand(FocusCommand.Direction.FAR, 1));
            if (delay > 0) {
                Thread.sleep(delay);
            }

            Instant before = Instant.now();
            try {
                CaptureResult result = backend.capture();
                Duration elapsed = Duration.between(before, Instant.now());
                log.info("  Delay={} ms → capture succeeded in {} ms (total {} ms from focus)",
                        delay, elapsed.toMillis(), delay + elapsed.toMillis());
            } catch (CameraException e) {
                Duration elapsed = Duration.between(before, Instant.now());
                log.error("  Delay={} ms → capture FAILED after {} ms: {}",
                        delay, elapsed.toMillis(), e.getMessage());
            }

            // Return focus
            backend.setFocus(new FocusCommand(FocusCommand.Direction.NEAR, 1));
            Thread.sleep(500); // always settle before next test
        }
    }

    // ── Test 9: Confirm 503 retry mechanism works on real camera ────────

    @Test
    @Order(9)
    void captureRecoversWith503Retry() throws Exception {
        assumeTrue(backend.supports(Operation.CAPTURE), "Camera must support CAPTURE");
        assumeTrue(backend.supports(Operation.FOCUS_CONTROL), "Camera must support FOCUS_CONTROL");

        // Provoke "Device busy" by sending drivefocus and capturing immediately
        // — the retry mechanism in ccapiPost should handle any 503
        log.info("Provoking potential 503 by rapid focus+capture...");

        // Large focus step — more likely to keep the camera busy
        backend.setFocus(new FocusCommand(FocusCommand.Direction.FAR, 10));
        // Immediately capture — no settle
        Instant before = Instant.now();
        CaptureResult result = backend.capture();
        Duration elapsed = Duration.between(before, Instant.now());

        assertNotNull(result, "Capture must succeed (possibly after retries)");
        log.info("Capture completed in {} ms (retries may have occurred — check WARN logs)",
                elapsed.toMillis());

        // Return focus
        backend.setFocus(new FocusCommand(FocusCommand.Direction.NEAR, 10));
        Thread.sleep(500);
    }
}
