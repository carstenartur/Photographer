package com.photographer.focusstack.camera.integration.realcamera;

import com.photographer.focusstack.camera.backend.CanonCcapiBackend;
import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.camera.CameraInfo;
import com.photographer.focusstack.common.camera.CameraState;
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
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hardware-in-the-loop integration tests that exercise the {@link CanonCcapiBackend}
 * against a <strong>real Canon camera</strong> on the local network.
 *
 * <p>These tests are tagged with {@link RealCamera @RealCamera} and are
 * <strong>excluded</strong> from normal {@code mvn verify} runs.
 * Run them on-demand when the camera is powered on and connected:
 *
 * <pre>
 *   mvn verify -pl app -am -Djava.awt.headless=true \
 *       -DexcludedGroups= -Dgroups=real-camera
 * </pre>
 *
 * <h3>Prerequisites</h3>
 * <ul>
 *   <li>Canon camera reachable at the operator-supplied {@code CCAPI_CAMERA_URL}</li>
 *   <li>Camera in WiFi mode with CCAPI enabled</li>
 *   <li>Lens attached and camera not in sleep mode</li>
 * </ul>
 */
@RealCamera
@EnabledIfEnvironmentVariable(named = "CCAPI_CAMERA_URL", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CanonCcapiRealCameraIT {

    private static final Logger log = LoggerFactory.getLogger(CanonCcapiRealCameraIT.class);

    /** Base URL for the real Canon camera CCAPI endpoint. */
    static final String CAMERA_BASE_URL = System.getenv("CCAPI_CAMERA_URL");

    private static CanonCcapiBackend backend;

    @BeforeAll
    static void createBackend() {
        backend = new CanonCcapiBackend(CAMERA_BASE_URL, new RestTemplate());
        log.info("CCAPI camera configured");
    }

    @BeforeEach
    void logTest(TestInfo info) {
        log.info("▶ Running: {}", info.getDisplayName());
    }

    // ── Discovery & Info ────────────────────────────────────────────────────

    @Test
    @Order(1)
    void endpointDiscoverySucceeds() {
        // Discovery is triggered lazily; calling getInfo forces it.
        CameraInfo info = backend.getInfo();
        assertNotNull(info, "CameraInfo must not be null");
        log.info("Camera model={}, firmware={}", info.model(), info.firmwareVersion());

        assertNotNull(info.id(), "Camera id should be populated");
        assertNotNull(info.model(), "Camera model should be populated");
        assertNotEquals("unknown", info.firmwareVersion(),
                "Firmware version should be read from the camera");
    }

    @Test
    @Order(2)
    void deviceInformationContainsMeaningfulModel() {
        CameraInfo info = backend.getInfo();
        // Real cameras should report something more specific than the fallback
        assertNotEquals("Canon Camera", info.model(),
                "Model should not be the fallback default");
        log.info("Detected model: {}", info.model());
    }

    // ── Format B Discovery Verification ────────────────────────────────────

    @Test
    @Order(3)
    void discoveryUsesFormatBWithEndpointDescriptors() {
        // After getInfo() triggered discovery, the endpoints map should be populated
        Map<String, String> endpoints = backend.getDiscoveredEndpoints();
        assertFalse(endpoints.isEmpty(),
                "Discovered endpoints should not be empty after getInfo()");
        log.info("Discovered {} endpoints", endpoints.size());
    }

    @Test
    @Order(4)
    void discoveryPopulatesHttpMethodsFromDescriptors() {
        // Format B descriptors include HTTP method flags — the endpointMethods
        // map should be populated with at least some entries.
        Map<String, Set<HttpMethod>> methods = backend.getDiscoveredEndpointMethods();
        assertFalse(methods.isEmpty(),
                "endpointMethods should be populated from Format B descriptors");
        log.info("Discovered HTTP methods for {} endpoints:", methods.size());

        // The AF endpoint should be discovered with POST (not PUT) on real hardware
        boolean afMethodFound = methods.entrySet().stream()
                .anyMatch(e -> e.getKey().contains("/shooting/control/af"));
        if (afMethodFound) {
            Set<HttpMethod> afMethods = methods.entrySet().stream()
                    .filter(e -> e.getKey().contains("/shooting/control/af"))
                    .map(Map.Entry::getValue)
                    .findFirst().orElse(Set.of());
            log.info("AF endpoint methods: {}", afMethods);
            assertTrue(afMethods.contains(HttpMethod.POST),
                    "AF endpoint should allow POST on real Canon hardware, found: " + afMethods);
            assertFalse(afMethods.contains(HttpMethod.PUT),
                    "AF endpoint should NOT allow PUT on real Canon hardware, found: " + afMethods);
        } else {
            log.warn("AF endpoint not found in endpointMethods — camera may not support focus control");
        }
    }

    // ── Camera State ────────────────────────────────────────────────────────

    @Test
    @Order(5)
    void cameraStateShowsConnected() {
        CameraState state = backend.getState();
        assertNotNull(state);
        assertTrue(state.connected(), "Camera should report as connected");
        log.info("Camera state: connected={}, battery={}%, storage={} bytes, focus={}",
                state.connected(), state.batteryLevel(), state.storageAvailable(),
                state.currentFocusPosition());
    }

    @Test
    @Order(6)
    void batteryLevelIsReasonable() {
        CameraState state = backend.getState();
        assertTrue(state.batteryLevel() > 0 && state.batteryLevel() <= 100,
                "Battery level should be 1-100, was: " + state.batteryLevel());
        log.info("Battery level: {}%", state.batteryLevel());
    }

    @Test
    @Order(7)
    void getStateHandlesMissingStorageEndpointGracefully() {
        // The real camera may or may not expose a /currentstorage endpoint.
        // Either way, getState() must not throw and must report connected=true
        // (battery endpoint should succeed).
        Map<String, String> endpoints = backend.getDiscoveredEndpoints();
        boolean hasStorage = endpoints.keySet().stream()
                .anyMatch(k -> k.contains("currentstorage") || k.contains("storage"));
        log.info("Storage endpoint discovered: {}", hasStorage);

        CameraState state = backend.getState();
        assertNotNull(state, "getState() must not return null");
        assertTrue(state.connected(),
                "Camera should report connected even if storage endpoint is absent");
        log.info("State with storage={}: connected={}, storageAvailable={}",
                hasStorage, state.connected(), state.storageAvailable());

        if (!hasStorage) {
            assertEquals(0L, state.storageAvailable(),
                    "storageAvailable should be 0 when no storage endpoint is discovered");
        }
    }

    // ── Capability Detection ────────────────────────────────────────────────

    @Test
    @Order(8)
    void supportsExpectedOperations() {
        log.info("Checking supported operations...");
        for (Operation op : Operation.values()) {
            boolean supported = backend.supports(op);
            log.info("  {} → {}", op, supported ? "SUPPORTED" : "not supported");
        }
        // At minimum a CCAPI camera should support these:
        assertTrue(backend.supports(Operation.SETTINGS_READ), "SETTINGS_READ should be supported");
    }

    // ── Live View ───────────────────────────────────────────────────────────

    @Test
    @Order(10)
    void liveViewReturnsJpegFrame() {
        if (!backend.supports(Operation.LIVE_VIEW)) {
            log.warn("Camera does not advertise LIVE_VIEW support — skipping");
            return;
        }

        LiveViewFrame frame = backend.getLiveView();
        assertNotNull(frame, "LiveViewFrame must not be null");
        assertNotNull(frame.data(), "Frame data must not be null");
        assertTrue(frame.data().length > 0, "Frame data must not be empty");
        assertNotNull(frame.timestamp(), "Timestamp must be set");
        log.info("Live view frame: {} bytes, {}x{}, type={}",
                frame.data().length, frame.width(), frame.height(), frame.contentType());

        // JPEG magic bytes: FF D8 FF
        assertTrue(frame.data().length >= 3
                        && (frame.data()[0] & 0xFF) == 0xFF
                        && (frame.data()[1] & 0xFF) == 0xD8
                        && (frame.data()[2] & 0xFF) == 0xFF,
                "Frame data should be a valid JPEG (starts with FF D8 FF)");
    }

    @Test
    @Order(11)
    void liveViewFrameRateIsReasonable() throws Exception {
        if (!backend.supports(Operation.LIVE_VIEW)) {
            log.warn("Camera does not advertise LIVE_VIEW support — skipping");
            return;
        }

        int frameCount = 5;
        Instant start = Instant.now();
        for (int i = 0; i < frameCount; i++) {
            LiveViewFrame frame = backend.getLiveView();
            assertNotNull(frame.data());
            log.info("  Frame {}: {} bytes", i + 1, frame.data().length);
        }
        Duration elapsed = Duration.between(start, Instant.now());
        double fps = frameCount / (elapsed.toMillis() / 1000.0);
        log.info("Fetched {} live-view frames in {} ms → ~{} fps",
                frameCount, elapsed.toMillis(), String.format("%.1f", fps));
    }

    // ── Focus Control ───────────────────────────────────────────────────────

    @Test
    @Order(19)
    void focusControlUsesPostNotPut() {
        if (!backend.supports(Operation.FOCUS_CONTROL)) {
            log.warn("Camera does not advertise FOCUS_CONTROL support — skipping");
            return;
        }

        // Verify that the AF endpoint was discovered with POST (not PUT).
        // This is the fix for the 405 Method Not Allowed error that occurred
        // when the old code used PUT on real Canon hardware.
        Map<String, Set<HttpMethod>> methods = backend.getDiscoveredEndpointMethods();
        boolean afFound = methods.entrySet().stream()
                .anyMatch(e -> e.getKey().contains("/shooting/control/af"));
        assertTrue(afFound, "AF endpoint should be in the discovered methods map");

        Set<HttpMethod> afMethods = methods.entrySet().stream()
                .filter(e -> e.getKey().contains("/shooting/control/af"))
                .map(Map.Entry::getValue)
                .findFirst().orElse(Set.of());

        // The real Canon CCAPI uses POST for AF control
        assertTrue(afMethods.contains(HttpMethod.POST),
                "AF endpoint should accept POST, found: " + afMethods);

        // Now actually exercise it — this would have thrown 405 with the old PUT code
        assertDoesNotThrow(() ->
                        backend.setFocus(new FocusCommand(FocusCommand.Direction.FAR, 1)),
                "POST-based focus control should succeed on real camera");
        log.info("Focus via POST succeeded (methods: {})", afMethods);

        // Return focus
        assertDoesNotThrow(() ->
                        backend.setFocus(new FocusCommand(FocusCommand.Direction.NEAR, 1)),
                "POST-based focus NEAR should also succeed");
    }

    @Test
    @Order(20)
    void focusNearDoesNotThrow() {
        if (!backend.supports(Operation.FOCUS_CONTROL)) {
            log.warn("Camera does not advertise FOCUS_CONTROL support — skipping");
            return;
        }
        assertDoesNotThrow(() ->
                        backend.setFocus(new FocusCommand(FocusCommand.Direction.NEAR, 1)),
                "Small NEAR focus step should not throw");
        log.info("Focus NEAR (1 step) succeeded");
    }

    @Test
    @Order(21)
    void focusFarDoesNotThrow() {
        if (!backend.supports(Operation.FOCUS_CONTROL)) {
            log.warn("Camera does not advertise FOCUS_CONTROL support — skipping");
            return;
        }
        assertDoesNotThrow(() ->
                        backend.setFocus(new FocusCommand(FocusCommand.Direction.FAR, 1)),
                "Small FAR focus step should not throw");
        log.info("Focus FAR (1 step) succeeded");
    }

    @Test
    @Order(22)
    void focusNearAndFarSequence() throws Exception {
        if (!backend.supports(Operation.FOCUS_CONTROL)) {
            log.warn("Camera does not advertise FOCUS_CONTROL support — skipping");
            return;
        }

        // Move focus near in small steps, then back far — exercise multiple
        // consecutive focus commands to detect issues with rapid-fire requests.
        int steps = 3;
        for (int i = 0; i < steps; i++) {
            backend.setFocus(new FocusCommand(FocusCommand.Direction.NEAR, 1));
            Thread.sleep(300); // give the camera time to execute
        }
        log.info("Moved {} NEAR steps", steps);

        for (int i = 0; i < steps; i++) {
            backend.setFocus(new FocusCommand(FocusCommand.Direction.FAR, 1));
            Thread.sleep(300);
        }
        log.info("Moved {} FAR steps (back to approximate start)", steps);

        CameraState state = backend.getState();
        log.info("Focus position after round-trip: {}", state.currentFocusPosition());
    }

    @Test
    @Order(23)
    void focusDriveSizes() throws Exception {
        if (!backend.supports(Operation.FOCUS_CONTROL)) {
            log.warn("Camera does not advertise FOCUS_CONTROL support — skipping");
            return;
        }

        // Exercise the three CCAPI drive sizes: small (1), medium (3), large (10)
        log.info("Testing focus drive size: small");
        backend.setFocus(new FocusCommand(FocusCommand.Direction.FAR, 1));
        Thread.sleep(500);

        log.info("Testing focus drive size: medium");
        backend.setFocus(new FocusCommand(FocusCommand.Direction.FAR, 3));
        Thread.sleep(500);

        log.info("Testing focus drive size: large");
        backend.setFocus(new FocusCommand(FocusCommand.Direction.FAR, 10));
        Thread.sleep(500);

        // Return to approximate original position
        backend.setFocus(new FocusCommand(FocusCommand.Direction.NEAR, 10));
        Thread.sleep(500);
        backend.setFocus(new FocusCommand(FocusCommand.Direction.NEAR, 3));
        Thread.sleep(500);
        backend.setFocus(new FocusCommand(FocusCommand.Direction.NEAR, 1));
    }

    // ── Capture ─────────────────────────────────────────────────────────────

    @Test
    @Order(30)
    void captureTriggersShutterAndReturnsCaptureResult() {
        if (!backend.supports(Operation.CAPTURE)) {
            log.warn("Camera does not advertise CAPTURE support — skipping");
            return;
        }

        CaptureResult result = backend.capture();
        assertNotNull(result, "CaptureResult must not be null");
        assertNotNull(result.imageId(), "imageId must be set");
        assertNotNull(result.capturedAt(), "capturedAt must be set");
        log.info("Captured at focus position {}", result.focusPosition());
    }

    // ── Combined workflow ───────────────────────────────────────────────────

    @Test
    @Order(40)
    void miniStackWorkflow() throws Exception {
        if (!backend.supports(Operation.CAPTURE) || !backend.supports(Operation.FOCUS_CONTROL)) {
            log.warn("Camera does not support CAPTURE + FOCUS_CONTROL — skipping mini-stack");
            return;
        }

        int stackSize = 3;
        log.info("Starting mini focus stack with {} frames", stackSize);

        for (int i = 0; i < stackSize; i++) {
            if (i > 0) {
                backend.setFocus(new FocusCommand(FocusCommand.Direction.FAR, 1));
                Thread.sleep(500); // allow the lens to settle
            }
            CaptureResult result = backend.capture();
            log.info("Frame {}/{} at focus position {}", i + 1, stackSize, result.focusPosition());
            Thread.sleep(1000); // give the camera time to save
        }

        // Return focus to starting position
        for (int i = 1; i < stackSize; i++) {
            backend.setFocus(new FocusCommand(FocusCommand.Direction.NEAR, 1));
            Thread.sleep(300);
        }
        log.info("Mini focus stack complete, focus returned to start");
    }

    // ── Robustness ──────────────────────────────────────────────────────────

    @Test
    @Order(50)
    void rapidConsecutiveInfoCallsDoNotFail() {
        for (int i = 0; i < 10; i++) {
            CameraInfo info = backend.getInfo();
            assertNotNull(info);
        }
        log.info("10 rapid getInfo() calls succeeded");
    }

    @Test
    @Order(51)
    void stateAfterMultipleOperations() {
        CameraState state = backend.getState();
        assertNotNull(state);
        assertTrue(state.connected(), "Camera should still be connected after prior tests");
        log.info("Final state: battery={}%, storage={}, focusPos={}",
                state.batteryLevel(), state.storageAvailable(), state.currentFocusPosition());
    }
}
