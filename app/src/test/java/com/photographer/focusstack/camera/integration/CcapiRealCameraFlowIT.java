package com.photographer.focusstack.camera.integration;

import com.photographer.focusstack.common.model.CameraProfile;
import com.photographer.focusstack.common.model.StackingJob;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that exercises the <strong>complete focus-stacking pipeline</strong>
 * using a realistic mock CCAPI HTTP server that returns <strong>real JPEG image data</strong>.
 *
 * <p>This test simulates a Canon camera by providing:
 * <ul>
 *   <li>CCAPI endpoint discovery ({@code /ccapi}, {@code /ccapi/ver100})</li>
 *   <li>3-phase shutter release ({@code shutterbutton/manual})</li>
 *   <li>Focus drive ({@code drivefocus})</li>
 *   <li>Event polling with image URLs ({@code event/polling})</li>
 *   <li>SD card content browsing ({@code contents/sd})</li>
 *   <li>Actual JPEG image download from simulated SD card paths</li>
 * </ul>
 *
 * <p>The full pipeline is tested end-to-end:
 * <ol>
 *   <li>Create camera profile (type {@code canon-ccapi})</li>
 *   <li>Create stacking job via REST</li>
 *   <li>Start job → async capture loop uses {@code CanonCcapiBackend}</li>
 *   <li>Backend discovers endpoints, triggers shutter, downloads images</li>
 *   <li>StackingJobService stores frames via FrameDataStore</li>
 *   <li>Test verifies each frame is retrievable as a valid JPEG via REST</li>
 * </ol>
 *
 * <p>This test catches bugs that mock-backend tests cannot, such as:
 * <ul>
 *   <li>Image data lost during CCAPI response parsing</li>
 *   <li>Event/polling URL extraction failures</li>
 *   <li>SD card content browse timing issues</li>
 *   <li>JPEG bytes corrupted by RestTemplate message converters</li>
 *   <li>Early-stop analysis killing the job prematurely</li>
 * </ul>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // Disable mock delay — the CCAPI backend handles timing itself
                "camera.mock.delay-ms=0",
                // Use a temp directory for frame storage
                "focusstack.storage.images-dir=${java.io.tmpdir}/focusstack-ccapi-it-images"
        }
)
@AutoConfigureTestRestTemplate
class CcapiRealCameraFlowIT extends AbstractPostgresIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(CcapiRealCameraFlowIT.class);

    @Autowired
    private TestRestTemplate restTemplate;

    private static HttpServer mockCamera;
    private static int cameraPort;

    /** Counts shutter triggers to generate unique image filenames. */
    private static final AtomicInteger captureCount = new AtomicInteger(0);

    /** Tracks the latest captured image filename for event/polling responses. */
    private static volatile String latestImageName = null;

    /** Pre-generated JPEG images (different per frame to enable motion/sharpness analysis). */
    private static byte[][] testJpegs;

    @BeforeAll
    static void startMockCcapiCamera() throws IOException {
        // Generate 10 distinct JPEG test images (64x64 pixels, different colors)
        testJpegs = new byte[10][];
        for (int i = 0; i < testJpegs.length; i++) {
            testJpegs[i] = generateTestJpeg(64, 64, i);
            log.info("Generated test JPEG #{}: {} bytes", i, testJpegs[i].length);
        }

        mockCamera = HttpServer.create(new InetSocketAddress(0), 0);
        cameraPort = mockCamera.getAddress().getPort();

        // ── GET /ccapi — version discovery ──
        mockCamera.createContext("/ccapi", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            // Only respond to exact /ccapi path, not sub-paths
            if (!"/ccapi".equals(exchange.getRequestURI().getPath())) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            sendJson(exchange, 200, "{\"ver100\":\"/ccapi/ver100\"}");
        });

        // ── GET /ccapi/ver100 — endpoint list ──
        mockCamera.createContext("/ccapi/ver100", exchange -> {
            String path = exchange.getRequestURI().getPath();

            // Sub-path routing
            if (path.startsWith("/ccapi/ver100/shooting/")) {
                handleShootingEndpoint(exchange);
                return;
            }
            if (path.startsWith("/ccapi/ver100/contents/")) {
                handleContentsEndpoint(exchange);
                return;
            }
            if (path.startsWith("/ccapi/ver100/event/")) {
                handleEventEndpoint(exchange);
                return;
            }
            if (path.equals("/ccapi/ver100/deviceinformation")) {
                sendJson(exchange, 200,
                        "{\"manufacturer\":\"Canon\",\"productname\":\"Canon EOS R5\","
                                + "\"serialnumber\":\"TEST001\",\"firmwareversion\":\"1.8.0\","
                                + "\"macaddress\":\"AA:BB:CC:DD:EE:FF\"}");
                return;
            }
            if (path.equals("/ccapi/ver100/devicestatus/battery")) {
                sendJson(exchange, 200, "{\"name\":\"battery\",\"level\":\"full\"}");
                return;
            }

            // Root /ccapi/ver100 — return endpoint list
            if (path.equals("/ccapi/ver100")) {
                String body = "["
                        + "\"/ccapi/ver100/deviceinformation\","
                        + "\"/ccapi/ver100/devicestatus/battery\","
                        + "\"/ccapi/ver100/shooting/liveview\","
                        + "\"/ccapi/ver100/shooting/liveview/flip\","
                        + "\"/ccapi/ver100/shooting/control/shutterbutton\","
                        + "\"/ccapi/ver100/shooting/control/shutterbutton/manual\","
                        + "\"/ccapi/ver100/shooting/control/drivefocus\","
                        + "\"/ccapi/ver100/shooting/control/af\","
                        + "\"/ccapi/ver100/event/polling\","
                        + "\"/ccapi/ver100/contents/sd\""
                        + "]";
                sendJson(exchange, 200, body);
                return;
            }

            log.warn("Mock camera: unhandled path {}", path);
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });

        mockCamera.setExecutor(null);
        mockCamera.start();
        log.info("Mock CCAPI camera started on port {} with {} test JPEGs",
                cameraPort, testJpegs.length);
    }

    @AfterAll
    static void stopMockCcapiCamera() {
        if (mockCamera != null) {
            mockCamera.stop(0);
            log.info("Mock CCAPI camera stopped");
        }
    }

    @BeforeEach
    void setUpAuth() {
        restTemplate = restTemplate.withBasicAuth("admin", "focusstack");
    }

    // ══════════════════════════════════════════════════════════════════════
    // Test: Full CCAPI pipeline → images must be retrievable as valid JPEGs
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void ccapiFullFlow_capturesRealJpegImages_andServesThemViaRest() throws Exception {
        // Reset capture counter
        captureCount.set(0);
        latestImageName = null;

        // ── 1. Create canon-ccapi camera profile pointing at our mock server ──
        String profileId = "ccapi-it-cam-" + System.currentTimeMillis();
        CameraProfile profile = new CameraProfile(
                profileId,
                "CCAPI IT Camera",
                "canon-ccapi",
                "http://localhost:" + cameraPort,  // direct URL to mock server
                5,   // stepSize
                20,  // maxFrames
                0.15 // movementThreshold
        );
        ResponseEntity<CameraProfile> profileResp = restTemplate.postForEntity(
                "/api/cameras", profile, CameraProfile.class);
        assertEquals(HttpStatus.CREATED, profileResp.getStatusCode(),
                "Camera profile creation should return 201");
        log.info("Created CCAPI camera profile: {}", profileId);

        // ── 2. Create stacking job with motion detection disabled ──
        //    (to prevent early stop — we want all 5 frames captured)
        String jobJson = String.format("""
                {
                    "cameraProfileId": "%s",
                    "parameters": {
                        "startPosition": 0,
                        "stepSize": 3,
                        "maxFrames": 5,
                        "abortThreshold": 0.0,
                        "motionDetectionEnabled": false,
                        "qualityMode": "FAST"
                    }
                }
                """, profileId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<StackingJob> createResp = restTemplate.postForEntity(
                "/api/jobs", new HttpEntity<>(jobJson, headers), StackingJob.class);
        assertEquals(HttpStatus.CREATED, createResp.getStatusCode(),
                "Job creation should return 201");
        StackingJob created = createResp.getBody();
        assertNotNull(created);
        String jobId = created.id();
        log.info("Created stacking job: {}", jobId);

        // ── 3. Start the job ──
        ResponseEntity<StackingJob> startResp = restTemplate.postForEntity(
                "/api/jobs/" + jobId + "/start", null, StackingJob.class);
        assertEquals(HttpStatus.ACCEPTED, startResp.getStatusCode(),
                "Starting the job should return 202 Accepted");
        log.info("Started stacking job: {}", jobId);

        // ── 4. Wait for the job to complete ──
        StackingJob finalJob = waitForTerminalState(jobId, 60);
        assertNotNull(finalJob, "Job should have reached a terminal state within timeout");
        log.info("Job {} finished with status {} and {} frames",
                jobId, finalJob.status(), finalJob.frames() != null ? finalJob.frames().size() : 0);

        assertEquals("COMPLETED", finalJob.status().name(),
                "Job should complete successfully but was: " + finalJob.status());

        // ── 5. Verify frames were captured ──
        List<String> frames = finalJob.frames();
        assertNotNull(frames, "Completed job must have a frames list");
        assertTrue(frames.size() >= 2,
                "Expected at least 2 frames but got " + frames.size());
        log.info("Job captured {} frames: {}", frames.size(), frames);

        // ── 6. KEY ASSERTION: Each frame must be retrievable as a valid JPEG ──
        int framesWithValidImages = 0;
        for (int i = 0; i < frames.size(); i++) {
            String frameId = frames.get(i);
            ResponseEntity<byte[]> imageResp = restTemplate.getForEntity(
                    "/api/jobs/" + jobId + "/frames/" + frameId + "/image",
                    byte[].class);

            log.info("Frame {} ({}): HTTP {} — {} bytes",
                    i, frameId, imageResp.getStatusCode(),
                    imageResp.getBody() != null ? imageResp.getBody().length : "null");

            assertEquals(HttpStatus.OK, imageResp.getStatusCode(),
                    "Frame " + i + " (" + frameId + ") should be retrievable (HTTP 200) "
                            + "but got " + imageResp.getStatusCode()
                            + ". This means the image data was LOST in the pipeline!");

            byte[] data = imageResp.getBody();
            assertNotNull(data,
                    "Frame " + i + " image body must not be null. "
                            + "The CanonCcapiBackend.capture() returned null imageData.");
            assertTrue(data.length > 100,
                    "Frame " + i + " should be a real JPEG (got only " + data.length + " bytes). "
                            + "Possible causes: event/polling returned wrong URL, "
                            + "SD card browse failed, or RestTemplate corrupted the bytes.");

            // Verify JPEG magic bytes
            assertTrue(data.length >= 2
                            && (data[0] & 0xFF) == 0xFF
                            && (data[1] & 0xFF) == 0xD8,
                    "Frame " + i + " should start with JPEG magic bytes (FF D8) "
                            + "but starts with "
                            + String.format("0x%02X 0x%02X", data[0] & 0xFF, data[1] & 0xFF)
                            + ". The image bytes were corrupted during transfer.");

            framesWithValidImages++;
        }

        assertEquals(frames.size(), framesWithValidImages,
                "ALL frames must have valid JPEG images, but only "
                        + framesWithValidImages + "/" + frames.size() + " were valid. "
                        + "This is the bug: the camera captures images but they're not displayed.");

        log.info("✓ All {} frames have valid JPEG images — pipeline is healthy!", frames.size());
    }

    /**
     * Test that verifies the image content type is correctly set to image/jpeg
     * for JPEG files served through the REST API.
     */
    @Test
    void ccapiFrameImage_hasCorrectContentType() throws Exception {
        captureCount.set(0);
        latestImageName = null;

        String profileId = "ccapi-it-ct-" + System.currentTimeMillis();
        CameraProfile profile = new CameraProfile(
                profileId, "CCAPI Content-Type Test", "canon-ccapi",
                "http://localhost:" + cameraPort, 3, 10, 0.15);
        restTemplate.postForEntity("/api/cameras", profile, CameraProfile.class);

        String jobJson = String.format("""
                {
                    "cameraProfileId": "%s",
                    "parameters": {
                        "startPosition": 0, "stepSize": 3, "maxFrames": 2,
                        "abortThreshold": 0.0, "motionDetectionEnabled": false,
                        "qualityMode": "FAST"
                    }
                }
                """, profileId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<StackingJob> createResp = restTemplate.postForEntity(
                "/api/jobs", new HttpEntity<>(jobJson, headers), StackingJob.class);
        String jobId = createResp.getBody().id();
        restTemplate.postForEntity("/api/jobs/" + jobId + "/start", null, StackingJob.class);

        StackingJob finalJob = waitForTerminalState(jobId, 60);
        assertEquals("COMPLETED", finalJob.status().name());
        assertFalse(finalJob.frames().isEmpty());

        String frameId = finalJob.frames().get(0);
        ResponseEntity<byte[]> imageResp = restTemplate.getForEntity(
                "/api/jobs/" + jobId + "/frames/" + frameId + "/image",
                byte[].class);
        assertEquals(HttpStatus.OK, imageResp.getStatusCode());
        // The filename is capture-<uuid>.jpg, so Content-Type should be image/jpeg
        MediaType contentType = imageResp.getHeaders().getContentType();
        assertNotNull(contentType, "Content-Type header should be set");
        log.info("Frame Content-Type: {}", contentType);
        assertEquals("image", contentType.getType(),
                "Content-Type should be image/* for JPEG data");
    }

    // ══════════════════════════════════════════════════════════════════════
    // Mock CCAPI endpoint handlers
    // ══════════════════════════════════════════════════════════════════════

    private static void handleShootingEndpoint(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        readBody(exchange);

        // POST /shooting/control/shutterbutton/manual — 3-phase shutter
        if (path.endsWith("/shutterbutton/manual") && "POST".equals(exchange.getRequestMethod())) {
            // Track the capture — generate a new image on the "SD card"
            // The 3-phase sequence sends half_press, full_press, release.
            // We generate a new image on each "release" (3rd call per capture).
            int count = captureCount.incrementAndGet();
            // Every 3rd shutter request is the "release" that produces an image
            if (count % 3 == 0) {
                int imageIndex = count / 3;
                latestImageName = String.format("IMG_%04d.JPG", imageIndex);
                log.info("Mock camera: shutter release #{} → new image {}", imageIndex, latestImageName);
            } else {
                log.debug("Mock camera: shutter phase {} (count={})", count % 3, count);
            }
            sendJson(exchange, 200, "{}");
            return;
        }

        // POST /shooting/control/drivefocus
        if (path.endsWith("/drivefocus") && "POST".equals(exchange.getRequestMethod())) {
            log.debug("Mock camera: drivefocus");
            sendJson(exchange, 200, "{}");
            return;
        }

        // POST/DELETE /shooting/liveview — start/stop live view
        if (path.endsWith("/liveview")) {
            sendJson(exchange, 200, "{}");
            return;
        }

        // GET /shooting/liveview/flip — live view frame
        if (path.endsWith("/liveview/flip") && "GET".equals(exchange.getRequestMethod())) {
            byte[] jpeg = testJpegs[0];
            exchange.getResponseHeaders().set("Content-Type", "image/jpeg");
            exchange.sendResponseHeaders(200, jpeg.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(jpeg);
            }
            return;
        }

        // POST /shooting/control/shutterbutton (legacy)
        if (path.endsWith("/shutterbutton") && "POST".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 200, "{}");
            return;
        }

        // POST /shooting/control/af
        if (path.endsWith("/af") && "POST".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 200, "{}");
            return;
        }

        log.warn("Mock camera: unhandled shooting path {} {}", exchange.getRequestMethod(), path);
        exchange.sendResponseHeaders(404, -1);
        exchange.close();
    }

    private static void handleEventEndpoint(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();

        // GET /event/polling — return the latest captured image URL
        if (path.endsWith("/event/polling") && "GET".equals(exchange.getRequestMethod())) {
            String imageName = latestImageName;
            if (imageName != null) {
                // Return a response that includes the new image URL
                // The CanonCcapiBackend.extractContentUrls() searches recursively for
                // URLs containing "/contents/" and ending with an image extension
                String imageUrl = "/ccapi/ver100/contents/sd/100CANON/" + imageName;
                String body = "{\"addedcontents\":[\""
                        + imageUrl + "\"]}";
                log.info("Mock camera: event/polling → returning image URL: {}", imageUrl);
                // Clear the latest image so the next poll returns empty
                latestImageName = null;
                sendJson(exchange, 200, body);
            } else {
                sendJson(exchange, 200, "{}");
            }
            return;
        }

        log.warn("Mock camera: unhandled event path {}", path);
        exchange.sendResponseHeaders(404, -1);
        exchange.close();
    }

    private static void handleContentsEndpoint(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();

        // GET /contents/sd — list folders
        if (path.equals("/ccapi/ver100/contents/sd") && "GET".equals(exchange.getRequestMethod())) {
            String body = "{\"dir\":["
                    + "{\"name\":\"100CANON\",\"attrib\":\"D\"}"
                    + "]}";
            sendJson(exchange, 200, body);
            return;
        }

        // GET /contents/sd/100CANON — list images in folder
        if (path.equals("/ccapi/ver100/contents/sd/100CANON") && "GET".equals(exchange.getRequestMethod())) {
            int imageCount = Math.max(1, captureCount.get() / 3);
            StringBuilder entries = new StringBuilder();
            OffsetDateTime baseTime = OffsetDateTime.of(2026, 4, 12, 10, 0, 0, 0, ZoneOffset.UTC);
            for (int i = 1; i <= imageCount; i++) {
                if (entries.length() > 0) entries.append(",");
                String dt = baseTime.plusMinutes(i).toString();
                entries.append(String.format(
                        "{\"name\":\"IMG_%04d.JPG\",\"size\":%d,\"datetime\":\"%s\",\"attrib\":\"A\"}",
                        i, testJpegs[i % testJpegs.length].length, dt));
            }
            String body = "{\"dir\":[" + entries + "]}";
            sendJson(exchange, 200, body);
            return;
        }

        // GET /contents/sd/100CANON/IMG_XXXX.JPG — download actual JPEG
        if (path.startsWith("/ccapi/ver100/contents/sd/100CANON/IMG_")
                && path.toUpperCase().endsWith(".JPG")
                && "GET".equals(exchange.getRequestMethod())) {
            // Extract image number from filename
            String fileName = path.substring(path.lastIndexOf('/') + 1);
            int imageNum;
            try {
                imageNum = Integer.parseInt(fileName.substring(4, 8));
            } catch (NumberFormatException e) {
                imageNum = 1;
            }
            byte[] jpeg = testJpegs[imageNum % testJpegs.length];
            log.info("Mock camera: serving {} ({} bytes JPEG)", fileName, jpeg.length);

            exchange.getResponseHeaders().set("Content-Type", "image/jpeg");
            exchange.sendResponseHeaders(200, jpeg.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(jpeg);
            }
            return;
        }

        log.warn("Mock camera: unhandled contents path {}", path);
        exchange.sendResponseHeaders(404, -1);
        exchange.close();
    }

    // ══════════════════════════════════════════════════════════════════════
    // Helper methods
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Generates a real JPEG image with distinct visual content per frame index.
     * Each frame has a different background color and a gradient pattern so that
     * sharpness analysis produces different scores per frame.
     */
    private static byte[] generateTestJpeg(int width, int height, int frameIndex) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();

        // Different background color per frame
        float hue = (frameIndex * 0.12f) % 1.0f;
        Color bgColor = Color.getHSBColor(hue, 0.6f, 0.8f);
        g2d.setColor(bgColor);
        g2d.fillRect(0, 0, width, height);

        // Draw a gradient pattern (simulates different focus planes)
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // Create detail pattern that varies per frame
                double detail = Math.sin((x + frameIndex * 7) * 0.3)
                        * Math.cos((y + frameIndex * 11) * 0.3);
                if (detail > 0.5) {
                    int bright = (int) (detail * 60);
                    image.setRGB(x, y, image.getRGB(x, y) + (bright << 16) + (bright << 8) + bright);
                }
            }
        }

        // Draw frame number marker
        g2d.setColor(Color.WHITE);
        g2d.drawString("F" + frameIndex, 5, 15);
        g2d.dispose();

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "JPEG", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate test JPEG", e);
        }
    }

    private static void sendJson(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void readBody(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
    }

    private StackingJob waitForTerminalState(String jobId, int timeoutSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
        StackingJob job = null;
        while (System.currentTimeMillis() < deadline) {
            ResponseEntity<StackingJob> resp = restTemplate.getForEntity(
                    "/api/jobs/" + jobId, StackingJob.class);
            if (resp.getStatusCode() == HttpStatus.OK && resp.getBody() != null) {
                job = resp.getBody();
                String status = job.status().name();
                if ("COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status)) {
                    return job;
                }
            }
            Thread.sleep(500);
        }
        return job;
    }
}
