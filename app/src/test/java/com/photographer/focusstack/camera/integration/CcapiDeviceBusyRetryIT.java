package com.photographer.focusstack.camera.integration;

import com.photographer.focusstack.camera.backend.CanonCcapiBackend;
import com.photographer.focusstack.camera.exception.CameraException;
import com.photographer.focusstack.common.camera.CaptureResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that exercises the 503 "Device busy" retry mechanism in
 * {@link CanonCcapiBackend} against a <strong>real HTTP server</strong>.
 *
 * <p>Unlike the unit test {@code CanonCcapiBackendTest.capture_retriesOnDeviceBusy503}
 * which mocks the {@link RestTemplate}, this test starts a local HTTP server that
 * simulates a Canon CCAPI camera. This proves the retry logic works end-to-end
 * through the full HTTP stack (connection, headers, serialisation, status codes).
 *
 * <p>This test runs in normal CI ({@code mvn verify}) — no real camera required.
 */
class CcapiDeviceBusyRetryIT {

    private static final Logger log = LoggerFactory.getLogger(CcapiDeviceBusyRetryIT.class);

    private static HttpServer mockCamera;
    private static int port;

    /** Counts POST requests to the shutterbutton/manual endpoint. */
    private static final AtomicInteger shutterRequestCount = new AtomicInteger(0);

    /** Number of 503 responses to return before succeeding (per test scenario). */
    private static volatile int busyResponsesBeforeSuccess = 0;

    /** Counts POST requests to the drivefocus endpoint. */
    private static final AtomicInteger focusRequestCount = new AtomicInteger(0);

    /** Whether the drivefocus endpoint should return 503 Device busy. */
    private static volatile boolean focusBusy = false;

    @BeforeAll
    static void startMockCamera() throws IOException {
        mockCamera = HttpServer.create(new InetSocketAddress(0), 0);
        port = mockCamera.getAddress().getPort();

        // GET /ccapi — version discovery (Format A: version → URL string)
        mockCamera.createContext("/ccapi", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                String body = "{\"ver100\":\"/ccapi/ver100\"}";
                sendJson(exchange, 200, body);
            } else {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
            }
        });

        // GET /ccapi/ver100 — endpoint list
        // Note: /contents/sd is intentionally omitted so the capture method skips
        // DCIM polling (avoids 10s timeout). The IT focuses on 503 retry, not image download.
        mockCamera.createContext("/ccapi/ver100", exchange -> {
            if (exchange.getRequestURI().getPath().equals("/ccapi/ver100")) {
                String body = "["
                        + "\"/ccapi/ver100/deviceinformation\","
                        + "\"/ccapi/ver100/devicestatus/battery\","
                        + "\"/ccapi/ver100/shooting/liveview\","
                        + "\"/ccapi/ver100/shooting/liveview/flip\","
                        + "\"/ccapi/ver100/shooting/control/shutterbutton\","
                        + "\"/ccapi/ver100/shooting/control/shutterbutton/manual\","
                        + "\"/ccapi/ver100/shooting/control/drivefocus\","
                        + "\"/ccapi/ver100/shooting/control/af\","
                        + "\"/ccapi/ver100/event/polling\""
                        + "]";
                sendJson(exchange, 200, body);
            } else {
                // Fall through for sub-paths
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            }
        });

        // POST /ccapi/ver100/shooting/control/shutterbutton/manual
        // Returns 503 "Device busy" for the configured number of requests,
        // then returns 200 OK.
        mockCamera.createContext("/ccapi/ver100/shooting/control/shutterbutton/manual", exchange -> {
            readBody(exchange); // consume request body
            if ("POST".equals(exchange.getRequestMethod())) {
                int count = shutterRequestCount.incrementAndGet();
                if (count <= busyResponsesBeforeSuccess) {
                    log.info("Mock camera: shutterbutton/manual request {} → 503 Device busy", count);
                    sendJson(exchange, 503, "{\"message\":\"Device busy\"}");
                } else {
                    log.info("Mock camera: shutterbutton/manual request {} → 200 OK", count);
                    sendJson(exchange, 200, "{}");
                }
            } else {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
            }
        });

        // POST /ccapi/ver100/shooting/control/drivefocus
        mockCamera.createContext("/ccapi/ver100/shooting/control/drivefocus", exchange -> {
            readBody(exchange);
            if ("POST".equals(exchange.getRequestMethod())) {
                int count = focusRequestCount.incrementAndGet();
                if (focusBusy) {
                    log.info("Mock camera: drivefocus request {} → 503 Device busy", count);
                    sendJson(exchange, 503, "{\"message\":\"Device busy\"}");
                } else {
                    log.info("Mock camera: drivefocus request {} → 200 OK", count);
                    sendJson(exchange, 200, "{}");
                }
            } else {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
            }
        });

        // POST /ccapi/ver100/shooting/liveview — start live view
        mockCamera.createContext("/ccapi/ver100/shooting/liveview", exchange -> {
            readBody(exchange);
            sendJson(exchange, 200, "{}");
        });

        // GET /ccapi/ver100/shooting/liveview/flip — live view frame
        mockCamera.createContext("/ccapi/ver100/shooting/liveview/flip", exchange -> {
            // Return a minimal JPEG (FF D8 FF D9)
            byte[] fakeJpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9};
            exchange.getResponseHeaders().set("Content-Type", "image/jpeg");
            exchange.sendResponseHeaders(200, fakeJpeg.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(fakeJpeg);
            }
        });

        // GET /ccapi/ver100/event/polling — no new events
        mockCamera.createContext("/ccapi/ver100/event/polling", exchange -> {
            sendJson(exchange, 200, "{}");
        });

        // GET /ccapi/ver100/deviceinformation
        mockCamera.createContext("/ccapi/ver100/deviceinformation", exchange -> {
            sendJson(exchange, 200,
                    "{\"manufacturer\":\"Canon\",\"productname\":\"Mock Camera\","
                    + "\"guid\":\"mock-guid-123\",\"serialnumber\":\"MOCK001\","
                    + "\"macaddress\":\"00:00:00:00:00:00\",\"firmwareversion\":\"1.0.0\"}");
        });

        mockCamera.setExecutor(null); // default executor
        mockCamera.start();
        log.info("Mock CCAPI camera started on port {}", port);
    }

    @AfterAll
    static void stopMockCamera() {
        if (mockCamera != null) {
            mockCamera.stop(0);
            log.info("Mock CCAPI camera stopped");
        }
    }

    private void resetCounters() {
        shutterRequestCount.set(0);
        focusRequestCount.set(0);
        busyResponsesBeforeSuccess = 0;
        focusBusy = false;
    }

    private CanonCcapiBackend createBackend() {
        // Use the public 2-argument constructor (defaults are fine for the mock server
        // since it responds instantly — capture timeout just means we skip image download)
        return new CanonCcapiBackend("http://localhost:" + port, new RestTemplate());
    }

    // ── Test: Capture succeeds on first attempt (no 503) ────────────────

    @Test
    void capture_succeedsWithoutRetry() {
        resetCounters();
        busyResponsesBeforeSuccess = 0; // all requests succeed

        CanonCcapiBackend backend = createBackend();
        CaptureResult result = backend.capture();

        assertNotNull(result);
        assertNotNull(result.imageId());
        // The 3-phase shutter sequence sends 3 POSTs: half_press, full_press, release
        assertEquals(3, shutterRequestCount.get(),
                "3-phase shutter should send exactly 3 POST requests");
        log.info("Capture without retry: {} shutter requests", shutterRequestCount.get());
    }

    // ── Test: First half_press gets 503, retry succeeds ─────────────────

    @Test
    void capture_retriesOn503DeviceBusy() {
        resetCounters();
        busyResponsesBeforeSuccess = 1; // first POST → 503, rest → 200

        CanonCcapiBackend backend = createBackend();
        Instant before = Instant.now();
        CaptureResult result = backend.capture();
        Duration elapsed = Duration.between(before, Instant.now());

        assertNotNull(result, "Capture should succeed after retry");
        assertNotNull(result.imageId());
        // 1 failed + 3 successful = 4 total
        assertEquals(4, shutterRequestCount.get(),
                "Should have 1 failed + 3 successful = 4 total shutter requests");
        // Retry backoff is 500ms, so total time should be at least that
        assertTrue(elapsed.toMillis() >= 400,
                "Retry backoff should add at least 400ms, was " + elapsed.toMillis() + "ms");
        log.info("Capture with 1 retry: {} shutter requests in {} ms",
                shutterRequestCount.get(), elapsed.toMillis());
    }

    // ── Test: Multiple consecutive 503s, then success ───────────────────

    @Test
    void capture_retriesMultiple503sBeforeSuccess() {
        resetCounters();
        busyResponsesBeforeSuccess = 3; // first 3 POSTs → 503, then 200

        CanonCcapiBackend backend = createBackend();
        CaptureResult result = backend.capture();

        assertNotNull(result, "Capture should succeed after multiple retries");
        // 3 failed + 3 successful = 6 total
        assertEquals(6, shutterRequestCount.get(),
                "Should have 3 failed + 3 successful = 6 total shutter requests");
        log.info("Capture with 3 retries: {} shutter requests", shutterRequestCount.get());
    }

    // ── Test: Exceeds max retries → CameraException ─────────────────────

    @Test
    void capture_failsAfterMaxRetries() {
        resetCounters();
        busyResponsesBeforeSuccess = 100; // always 503

        CanonCcapiBackend backend = createBackend();
        CameraException ex = assertThrows(CameraException.class, backend::capture,
                "Should throw CameraException after exhausting retries");
        assertTrue(ex.getMessage().contains("Device busy") || ex.getMessage().contains("503"),
                "Exception message should mention Device busy or 503, was: " + ex.getMessage());
        log.info("Capture after max retries: {} shutter requests, exception: {}",
                shutterRequestCount.get(), ex.getMessage());
    }

    // ── Test: Focus drive gets 503, retried successfully ────────────────

    @Test
    void setFocus_retriesOn503DeviceBusy() {
        resetCounters();
        focusBusy = false; // focus succeeds normally

        CanonCcapiBackend backend = createBackend();
        // Trigger endpoint discovery
        backend.getInfo();

        // Now test focus with 503
        focusBusy = true;
        focusRequestCount.set(0);

        // setFocus uses ccapiPost which has retry logic — but all attempts will fail
        // because focusBusy=true always returns 503
        CameraException ex = assertThrows(CameraException.class,
                () -> backend.setFocus(
                        new com.photographer.focusstack.common.camera.FocusCommand(
                                com.photographer.focusstack.common.camera.FocusCommand.Direction.FAR, 1)),
                "Focus should fail after max retries when camera is permanently busy");
        assertTrue(ex.getMessage().contains("503") || ex.getMessage().contains("Device busy"),
                "Exception should mention 503 or Device busy");
        assertTrue(focusRequestCount.get() > 1,
                "Should have retried at least once, was " + focusRequestCount.get());
        log.info("Focus with 503: {} requests, exception: {}",
                focusRequestCount.get(), ex.getMessage());
    }

    // ── Test: Full capture flow (focus + capture) against mock server ────

    @Test
    void fullCaptureFlowWithIntermittent503() {
        resetCounters();
        busyResponsesBeforeSuccess = 2; // first 2 shutter POSTs return 503
        focusBusy = false;

        CanonCcapiBackend backend = createBackend();

        // Simulate StackingJobService flow: setFocus → capture
        assertDoesNotThrow(() -> backend.setFocus(
                new com.photographer.focusstack.common.camera.FocusCommand(
                        com.photographer.focusstack.common.camera.FocusCommand.Direction.FAR, 5)));

        CaptureResult result = backend.capture();
        assertNotNull(result, "Capture should succeed after retries");
        assertNotNull(result.imageId());

        log.info("Full flow: focus requests={}, shutter requests={}",
                focusRequestCount.get(), shutterRequestCount.get());
    }

    // ── Test: Capture timing includes realistic retry backoff ────────────

    @Test
    void retryBackoffIsExponential() {
        resetCounters();
        busyResponsesBeforeSuccess = 2; // 2 retries → exponential backoff: 500ms + 1000ms

        CanonCcapiBackend backend = createBackend();
        Instant before = Instant.now();
        CaptureResult result = backend.capture();
        Duration elapsed = Duration.between(before, Instant.now());

        assertNotNull(result);
        // 500ms + 1000ms = 1500ms minimum from backoff
        assertTrue(elapsed.toMillis() >= 1200,
                "Exponential backoff should take at least 1200ms for 2 retries, was "
                + elapsed.toMillis() + "ms");
        log.info("Exponential backoff: 2 retries took {} ms total", elapsed.toMillis());
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static void sendJson(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void readBody(HttpExchange exchange) throws IOException {
        // Consume the request body to prevent connection issues
        exchange.getRequestBody().readAllBytes();
    }
}
