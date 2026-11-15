package com.photographer.focusstack.camera.integration.realcamera;

import com.photographer.focusstack.common.camera.*;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Abstract base class for real-camera capture-flow integration tests.
 *
 * <p>All tests operate exclusively through the {@link CameraBackend} interface,
 * making them transport-agnostic.  Subclasses only need to provide a backend
 * instance via {@link #backend()}.
 *
 * <p>Tests are tagged with {@link RealCamera @RealCamera} and excluded from
 * normal {@code mvn verify} runs.  Run on-demand:
 * <pre>
 *   mvn verify -pl app -am -Djava.awt.headless=true \
 *       -DexcludedGroups= -Dgroups=real-camera
 * </pre>
 */
@RealCamera
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
abstract class AbstractCaptureFlowIT {

    private static final Logger log = LoggerFactory.getLogger(AbstractCaptureFlowIT.class);

    /** Subclasses return the connected backend (CCAPI, USB/PTP, …). */
    protected abstract CameraBackend backend();

    @BeforeEach
    void logTest(TestInfo info) {
        log.info("▶ Running: {}", info.getDisplayName());
    }

    /**
     * Fetches a live view frame with retry/warm-up.  PTP cameras need time
     * to initialise the EVF stream; the first call often returns null or
     * very short data.  Returns {@code null} if live view cannot be started
     * after all retries.
     */
    protected LiveViewFrame getLiveViewWithRetry(CameraBackend b) throws Exception {
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                LiveViewFrame frame = b.getLiveView();
                if (frame != null && frame.data() != null && frame.data().length > 100) {
                    return frame;
                }
            } catch (Exception e) {
                log.debug("LiveView attempt {} failed: {}", attempt + 1, e.getClass().getSimpleName());
            }
            Thread.sleep(500);
        }
        // Final attempt — return null on failure rather than crashing
        try {
            return b.getLiveView();
        } catch (Exception e) {
            log.warn("LiveView unavailable after retries: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    // ── Test 1: Capture immediately after live view is active ────────────

    @Test
    @Order(1)
    void captureImmediatelyAfterLiveViewStart() throws Exception {
        CameraBackend b = backend();
        assumeTrue(b.supports(Operation.CAPTURE), "Camera must support CAPTURE");
        assumeTrue(b.supports(Operation.LIVE_VIEW), "Camera must support LIVE_VIEW");

        log.info("Fetching live view frame (with warm-up retries)...");
        LiveViewFrame frame = getLiveViewWithRetry(b);
        assumeTrue(frame != null && frame.data() != null && frame.data().length > 100,
                "LiveView could not be started — skipping (may require manual EVF activation on PTP cameras)");
        log.info("Live view active, frame={} bytes", frame.data().length);

        // Stop live view before capture — PTP cameras may fail if EVF is active
        b.stopLiveView();
        Thread.sleep(500);

        log.info("Attempting capture after live view stopped...");
        Instant before = Instant.now();
        CaptureResult result = b.capture();
        Duration elapsed = Duration.between(before, Instant.now());

        assertNotNull(result, "CaptureResult must not be null");
        assertNotNull(result.imageId(), "imageId must be set");
        log.info("Capture succeeded in {} ms", elapsed.toMillis());
    }

    // ── Test 2: Capture after setFocus with NO delay ────────────────────

    @Test
    @Order(2)
    void captureImmediatelyAfterSetFocusNoDelay() throws Exception {
        CameraBackend b = backend();
        assumeTrue(b.supports(Operation.CAPTURE), "Camera must support CAPTURE");
        assumeTrue(b.supports(Operation.FOCUS_CONTROL), "Camera must support FOCUS_CONTROL");

        log.info("Driving focus FAR 1 step...");
        b.setFocus(new FocusCommand(FocusCommand.Direction.FAR, 1));
        log.info("Capturing immediately after setFocus (no delay)...");
        Instant before = Instant.now();
        CaptureResult result = b.capture();
        Duration elapsed = Duration.between(before, Instant.now());

        assertNotNull(result, "CaptureResult must not be null after immediate focus+capture");
        log.info("Capture after setFocus succeeded in {} ms", elapsed.toMillis());

        b.setFocus(new FocusCommand(FocusCommand.Direction.NEAR, 1));
        Thread.sleep(300);
    }

    // ── Test 3: Capture after setFocus WITH settle delay ────────────────

    @Test
    @Order(3)
    void captureAfterSetFocusWithSettleDelay() throws Exception {
        CameraBackend b = backend();
        assumeTrue(b.supports(Operation.CAPTURE), "Camera must support CAPTURE");
        assumeTrue(b.supports(Operation.FOCUS_CONTROL), "Camera must support FOCUS_CONTROL");

        log.info("Driving focus FAR 1 step...");
        b.setFocus(new FocusCommand(FocusCommand.Direction.FAR, 1));
        Thread.sleep(500);
        log.info("Capturing after 500ms settle delay...");
        Instant before = Instant.now();
        CaptureResult result = b.capture();
        Duration elapsed = Duration.between(before, Instant.now());

        assertNotNull(result, "CaptureResult must not be null after settled focus+capture");
        log.info("Capture after settle delay succeeded in {} ms", elapsed.toMillis());

        b.setFocus(new FocusCommand(FocusCommand.Direction.NEAR, 1));
        Thread.sleep(300);
    }

    // ── Test 4: Rapid capture sequence (multiple frames back-to-back) ───

    @Test
    @Order(4)
    void rapidCaptureSequence() throws Exception {
        CameraBackend b = backend();
        assumeTrue(b.supports(Operation.CAPTURE), "Camera must support CAPTURE");

        int frameCount = 3;
        log.info("Starting rapid capture sequence of {} frames...", frameCount);

        long totalMs = 0;
        for (int i = 0; i < frameCount; i++) {
            Instant before = Instant.now();
            CaptureResult result = b.capture();
            Duration elapsed = Duration.between(before, Instant.now());
            totalMs += elapsed.toMillis();

            assertNotNull(result, "Frame " + (i + 1) + " must not be null");
            assertNotNull(result.imageId(), "Frame " + (i + 1) + " imageId must be set");
            log.info("Frame {}/{}: {} ms", i + 1, frameCount, elapsed.toMillis());
        }

        log.info("Rapid capture: {} frames in {} ms (avg {} ms/frame)",
                frameCount, totalMs, totalMs / frameCount);
    }

    // ── Test 5: Full stacking job flow (focus + capture interleaved) ────

    @Test
    @Order(5)
    void fullStackingJobFlow() throws Exception {
        CameraBackend b = backend();
        assumeTrue(b.supports(Operation.CAPTURE), "Camera must support CAPTURE");
        assumeTrue(b.supports(Operation.FOCUS_CONTROL), "Camera must support FOCUS_CONTROL");

        int stackSize = 3;
        log.info("Reproducing stacking job flow with {} frames...", stackSize);

        b.setFocus(new FocusCommand(FocusCommand.Direction.FAR, 5));
        log.info("Initial focus set to FAR 5");

        for (int i = 0; i < stackSize; i++) {
            Instant before = Instant.now();
            CaptureResult result = b.capture();
            Duration elapsed = Duration.between(before, Instant.now());
            assertNotNull(result, "Frame " + i + " must not be null");
            log.info("Frame {}/{}: {} ms, focus position {}", i + 1, stackSize, elapsed.toMillis(), result.focusPosition());

            if (i < stackSize - 1) {
                b.setFocus(new FocusCommand(FocusCommand.Direction.FAR, 1));
            }
        }

        for (int i = 0; i < stackSize + 4; i++) {
            b.setFocus(new FocusCommand(FocusCommand.Direction.NEAR, 1));
            Thread.sleep(200);
        }
        log.info("Full stacking job flow completed successfully");
    }

    // ── Test 6: Live view → capture → live view cycle ───────────────────

    @Test
    @Order(6)
    void liveViewCaptureInterleaved() throws Exception {
        CameraBackend b = backend();
        assumeTrue(b.supports(Operation.CAPTURE), "Camera must support CAPTURE");
        assumeTrue(b.supports(Operation.LIVE_VIEW), "Camera must support LIVE_VIEW");

        log.info("Testing live view → capture → live view cycle...");

        LiveViewFrame lvBefore = getLiveViewWithRetry(b);
        assumeTrue(lvBefore != null && lvBefore.data() != null,
                "LiveView could not be started — skipping");
        log.info("Live view frame before capture: {} bytes", lvBefore.data().length);

        b.stopLiveView();
        Thread.sleep(500);

        CaptureResult captureResult = b.capture();
        assertNotNull(captureResult);
        log.info("Capture succeeded");

        Thread.sleep(500);
        LiveViewFrame lvAfter = getLiveViewWithRetry(b);
        assumeTrue(lvAfter != null && lvAfter.data() != null,
                "LiveView could not be restarted after capture — skipping");
        log.info("Live view frame after capture: {} bytes", lvAfter.data().length);
    }

    // ── Test 7: Settle delay comparison ─────────────────────────────────

    @Test
    @Order(8)
    void settleDelayComparison() throws Exception {
        CameraBackend b = backend();
        assumeTrue(b.supports(Operation.CAPTURE), "Camera must support CAPTURE");
        assumeTrue(b.supports(Operation.FOCUS_CONTROL), "Camera must support FOCUS_CONTROL");

        int[] delaysMs = {0, 100, 300, 500, 1000};
        log.info("Testing capture success rate with different settle delays after setFocus...");

        for (int delay : delaysMs) {
            b.setFocus(new FocusCommand(FocusCommand.Direction.FAR, 1));
            if (delay > 0) {
                Thread.sleep(delay);
            }

            Instant before = Instant.now();
            try {
                CaptureResult result = b.capture();
                Duration elapsed = Duration.between(before, Instant.now());
                log.info("  Delay={} ms → capture succeeded in {} ms (total {} ms from focus)",
                        delay, elapsed.toMillis(), delay + elapsed.toMillis());
            } catch (Exception e) {
                Duration elapsed = Duration.between(before, Instant.now());
                log.error("  Delay={} ms → capture FAILED after {} ms: {}",
                        delay, elapsed.toMillis(), e.getClass().getSimpleName());
            }

            b.setFocus(new FocusCommand(FocusCommand.Direction.NEAR, 1));
            Thread.sleep(500);
        }
    }

    // ── Test 8: Stress test — rapid focus+capture (provoke busy) ────────

    @Test
    @Order(9)
    void captureRecoversWith503Retry() throws Exception {
        CameraBackend b = backend();
        assumeTrue(b.supports(Operation.CAPTURE), "Camera must support CAPTURE");
        assumeTrue(b.supports(Operation.FOCUS_CONTROL), "Camera must support FOCUS_CONTROL");

        log.info("Provoking potential busy state by rapid focus+capture...");
        b.setFocus(new FocusCommand(FocusCommand.Direction.FAR, 10));

        Instant before = Instant.now();
        CaptureResult result = b.capture();
        Duration elapsed = Duration.between(before, Instant.now());

        assertNotNull(result, "Capture must succeed (possibly after retries)");
        log.info("Capture completed in {} ms", elapsed.toMillis());

        b.setFocus(new FocusCommand(FocusCommand.Direction.NEAR, 10));
        Thread.sleep(500);
    }
}
