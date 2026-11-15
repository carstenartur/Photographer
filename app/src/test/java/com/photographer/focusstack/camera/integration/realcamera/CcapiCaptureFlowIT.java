package com.photographer.focusstack.camera.integration.realcamera;

import com.photographer.focusstack.camera.backend.CanonCcapiBackend;
import com.photographer.focusstack.camera.exception.CameraException;
import com.photographer.focusstack.common.camera.CameraBackend;
import com.photographer.focusstack.common.camera.CaptureResult;
import com.photographer.focusstack.common.camera.Operation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * CCAPI-specific real-camera capture-flow integration tests.
 *
 * <p>Inherits the transport-agnostic tests from {@link AbstractCaptureFlowIT}
 * and adds CCAPI-specific diagnostics (e.g.&nbsp;{@code cameradisplay} mode).
 *
 * <p>Run with:
 * <pre>
 *   mvn verify -pl app -am -Djava.awt.headless=true \
 *       -DexcludedGroups= -Dgroups=real-camera
 * </pre>
 */
@RealCamera
@EnabledIfEnvironmentVariable(named = "CCAPI_CAMERA_URL", matches = ".+")
class CcapiCaptureFlowIT extends AbstractCaptureFlowIT {

    private static final Logger log = LoggerFactory.getLogger(CcapiCaptureFlowIT.class);
    private static final String BASE_URL = CanonCcapiRealCameraIT.CAMERA_BASE_URL;

    private static CanonCcapiBackend ccapiBackend;
    private static RestTemplate rest;

    @BeforeAll
    static void createBackend() throws Exception {
        rest = new RestTemplate();
        ccapiBackend = new CanonCcapiBackend(BASE_URL, rest);
        log.info("CCAPI capture flow ready");
        ccapiBackend.getInfo();
        waitForCameraIdle();
    }

    @Override
    protected CameraBackend backend() {
        return ccapiBackend;
    }

    /**
     * Waits up to 30 seconds for the camera to become idle.
     * Stops live view (which blocks SD card access on the G5X Mark II),
     * then polls until the camera accepts SD card listing requests.
     */
    private static void waitForCameraIdle() throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                rest.postForObject(BASE_URL + "/ccapi/ver100/shooting/liveview",
                        java.util.Map.of("liveviewsize", "off", "cameradisplay", "on"), String.class);
                log.info("Live view stopped successfully");
                Thread.sleep(1000);
                break;
            } catch (org.springframework.web.client.HttpServerErrorException e) {
                log.debug("Stop live view attempt {}: {}", attempt + 1, e.getClass().getSimpleName());
                Thread.sleep(500);
            } catch (RestClientException e) {
                log.debug("Stop live view attempt {}: {}", attempt + 1, e.getClass().getSimpleName());
                break;
            }
        }

        for (int i = 0; i < 60; i++) {
            try {
                rest.getForObject(BASE_URL + "/ccapi/ver100/contents/sd", java.util.Map.class);
                log.info("Camera SD card accessible (waited {}ms)", i * 500);
                return;
            } catch (org.springframework.web.client.HttpServerErrorException e) {
                if (e.getStatusCode().value() == 503) {
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

    // ── CCAPI-specific: cameradisplay='on' vs 'off' diagnosis ───────────

    @Test
    @Order(7)
    void diagnoseCameraDisplayModeEffect() throws Exception {
        assumeTrue(ccapiBackend.supports(Operation.CAPTURE), "Camera must support CAPTURE");
        assumeTrue(ccapiBackend.supports(Operation.LIVE_VIEW), "Camera must support LIVE_VIEW");

        log.info("=== Testing with cameradisplay='on' (current behaviour) ===");
        try {
            ccapiBackend.startLiveView("medium");
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
            CaptureResult result = ccapiBackend.capture();
            Duration elapsed = Duration.between(before, Instant.now());
            log.info("Capture with cameradisplay='on' succeeded in {} ms", elapsed.toMillis());
        } catch (CameraException e) {
            log.error("Capture with cameradisplay='on' FAILED: {}", e.getClass().getSimpleName());
        }

        log.info("=== Stopping live view and restarting with cameradisplay='off' ===");
        ccapiBackend.startLiveView("off");
        Thread.sleep(500);
        ccapiBackend.startLiveView("medium");
        log.info("Diagnostic complete — see logs for timing differences");
    }
}
