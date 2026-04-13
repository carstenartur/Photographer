package com.photographer.focusstack.camera.integration;

import com.photographer.focusstack.common.model.CameraProfile;
import com.photographer.focusstack.common.model.StackingJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration test proving that the focus-stacking pipeline
 * produces images that a browser can actually display.
 *
 * <p>This test goes beyond the basic {@link FocusStackingFlowIT} by
 * verifying that:
 * <ol>
 *   <li>The image endpoint returns a <strong>browser-displayable Content-Type</strong>
 *       ({@code image/png} or {@code image/jpeg}), not {@code application/octet-stream}</li>
 *   <li>The returned bytes are <strong>actually decodable</strong> by Java's
 *       {@link ImageIO} (the same codecs browsers use), proving the image
 *       is not a RAW file wrapped in a wrong extension</li>
 *   <li>The decoded image has <strong>valid dimensions</strong> (width &gt; 0,
 *       height &gt; 0)</li>
 *   <li>Every frame in a completed job is individually retrievable and
 *       displayable</li>
 * </ol>
 *
 * <p>The symptom this test catches: the UI shows "No image data" in the
 * frame thumbnails because the server either returns a 404, a non-image
 * content type, or raw bytes the browser cannot render (e.g. CR3 data
 * labelled as JPEG).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "camera.mock.delay-ms=0"
        }
)
@AutoConfigureTestRestTemplate
class ImageDisplayPipelineIT extends AbstractPostgresIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ImageDisplayPipelineIT.class);

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void setUpAuth() {
        restTemplate = restTemplate.withBasicAuth("admin", "focusstack");
    }

    /**
     * Full pipeline: create → start → complete → verify every frame image
     * is displayable with correct Content-Type and decodable by ImageIO.
     *
     * <p><strong>Proof:</strong> Every downloaded frame is saved to a temp
     * directory as a real image file.  The directory path is logged so you
     * can open the files in any image viewer after the test run.
     */
    @Test
    void capturedFrames_areDisplayableImages_withCorrectContentType() throws Exception {
        // ── 0. Prepare a proof directory ────────────────────────────────
        Path proofDir = Files.createTempDirectory("focusstack-proof-images-");
        log.info("╔══════════════════════════════════════════════════════════╗");
        log.info("║  PROOF IMAGES will be saved to:                        ║");
        log.info("║  {}",  proofDir.toAbsolutePath());
        log.info("╚══════════════════════════════════════════════════════════╝");

        // ── 1. Create a mock camera profile ─────────────────────────────
        String profileId = "it-display-cam";
        CameraProfile profile = new CameraProfile(
                profileId, "Display Pipeline Test Camera", "mock", "localhost",
                5, 20, 0.15);
        ResponseEntity<CameraProfile> profileResp = restTemplate.postForEntity(
                "/api/cameras", profile, CameraProfile.class);
        assertEquals(HttpStatus.CREATED, profileResp.getStatusCode(),
                "Camera profile creation should return 201");

        // ── 2. Create a stacking job with a small number of frames ──────
        String jobJson = """
                {
                    "cameraProfileId": "%s",
                    "parameters": {
                        "startPosition": 0,
                        "stepSize": 5,
                        "maxFrames": 4,
                        "abortThreshold": 0.01,
                        "motionDetectionEnabled": false,
                        "qualityMode": "FAST"
                    }
                }
                """.formatted(profileId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<StackingJob> createResp = restTemplate.postForEntity(
                "/api/jobs", new HttpEntity<>(jobJson, headers), StackingJob.class);
        assertEquals(HttpStatus.CREATED, createResp.getStatusCode());
        String jobId = createResp.getBody().id();

        // ── 3. Start the job and wait for completion ────────────────────
        restTemplate.postForEntity("/api/jobs/" + jobId + "/start", null, StackingJob.class);
        StackingJob finalJob = waitForTerminalState(jobId, 30);

        assertNotNull(finalJob, "Job should have reached a terminal state within timeout");
        assertEquals("COMPLETED", finalJob.status().name(),
                "Job should complete successfully, but was: " + finalJob.status()
                        + " — stopReason: " + finalJob.stopReason());

        List<String> frames = finalJob.frames();
        assertNotNull(frames, "Completed job must have a frames list");
        assertFalse(frames.isEmpty(), "Completed job must have at least one captured frame");

        log.info("Job {} completed with {} frames — downloading each via REST ...",
                jobId, frames.size());

        // ── 4. Download & verify every frame, save to disk ─────────────
        for (int i = 0; i < frames.size(); i++) {
            String frameId = frames.get(i);
            String imageUrl = "/api/jobs/" + jobId + "/frames/" + frameId + "/image";

            // Use exchange() to get full response with headers
            ResponseEntity<byte[]> imageResp = restTemplate.exchange(
                    imageUrl, HttpMethod.GET, null, byte[].class);

            // 4a. HTTP 200
            assertEquals(HttpStatus.OK, imageResp.getStatusCode(),
                    "Frame " + i + " (" + frameId + ") should return HTTP 200, "
                            + "but got " + imageResp.getStatusCode());

            // 4b. Response body is not empty
            byte[] data = imageResp.getBody();
            assertNotNull(data, "Frame " + i + " image body must not be null");
            assertTrue(data.length > 0, "Frame " + i + " image must have non-empty data");

            // 4c. Content-Type is a browser-displayable image type (NOT octet-stream)
            MediaType contentType = imageResp.getHeaders().getContentType();
            assertNotNull(contentType,
                    "Frame " + i + " response must have a Content-Type header");
            assertTrue(
                    MediaType.IMAGE_PNG.isCompatibleWith(contentType)
                            || MediaType.IMAGE_JPEG.isCompatibleWith(contentType),
                    "Frame " + i + " Content-Type must be image/png or image/jpeg, "
                            + "but was: " + contentType
                            + ". Browsers cannot display application/octet-stream.");

            // 4d. Bytes are actually decodable as an image (proves not RAW data)
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(data));
            assertNotNull(decoded,
                    "Frame " + i + " (" + data.length + " bytes, "
                            + contentType + ") could NOT be decoded by ImageIO. "
                            + "This means the browser also cannot display it — "
                            + "the data may be a RAW file (CR2/CR3) or corrupted. "
                            + "Magic bytes: " + magicBytesHex(data));

            // 4e. Decoded image has valid dimensions
            assertTrue(decoded.getWidth() > 0 && decoded.getHeight() > 0,
                    "Frame " + i + " decoded image has invalid dimensions: "
                            + decoded.getWidth() + "×" + decoded.getHeight());

            // 4f. Verify Content-Disposition header is set for inline display
            String disposition = imageResp.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
            assertNotNull(disposition,
                    "Frame " + i + " should have Content-Disposition header");
            assertTrue(disposition.startsWith("inline"),
                    "Frame " + i + " Content-Disposition should be 'inline' for browser display, "
                            + "but was: " + disposition);

            // ── 4g. SAVE image to disk as proof ────────────────────────
            String extension = MediaType.IMAGE_PNG.isCompatibleWith(contentType) ? "png" : "jpg";
            String fileName = String.format("frame_%02d_%s.%s", i + 1,
                    frameId.substring(0, 8), extension);
            Path imagePath = proofDir.resolve(fileName);
            Files.write(imagePath, data);

            log.info("  ✓ Frame #{}: {} — {}×{} px, {} bytes, Content-Type={}, saved → {}",
                    i + 1, frameId.substring(0, 8),
                    decoded.getWidth(), decoded.getHeight(),
                    data.length, contentType,
                    imagePath.getFileName());
        }

        log.info("══════════════════════════════════════════════════════════");
        log.info("  ALL {} FRAMES VERIFIED — real displayable images", frames.size());
        log.info("  Proof images saved to: {}", proofDir.toAbsolutePath());
        log.info("  Open the directory to inspect the images.");
        log.info("══════════════════════════════════════════════════════════");
    }

    /**
     * Verifies that the image endpoint correctly handles multiple frames
     * with consistent results (no intermittent failures).
     */
    @Test
    void allFrames_areConsistentlyAccessible_afterJobCompletion() throws Exception {
        String profileId = "it-consistent-cam";
        CameraProfile profile = new CameraProfile(
                profileId, "Consistency Test Camera", "mock", "localhost",
                5, 20, 0.15);
        restTemplate.postForEntity("/api/cameras", profile, CameraProfile.class);

        String jobJson = """
                {
                    "cameraProfileId": "%s",
                    "parameters": {
                        "startPosition": 0,
                        "stepSize": 5,
                        "maxFrames": 6,
                        "abortThreshold": 0.01,
                        "motionDetectionEnabled": false,
                        "qualityMode": "FAST"
                    }
                }
                """.formatted(profileId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<StackingJob> createResp = restTemplate.postForEntity(
                "/api/jobs", new HttpEntity<>(jobJson, headers), StackingJob.class);
        String jobId = createResp.getBody().id();

        restTemplate.postForEntity("/api/jobs/" + jobId + "/start", null, StackingJob.class);
        StackingJob finalJob = waitForTerminalState(jobId, 30);
        assertEquals("COMPLETED", finalJob.status().name());

        List<String> frames = finalJob.frames();
        assertTrue(frames.size() >= 2,
                "Expected at least 2 frames but got " + frames.size());

        // Access every frame image TWICE to verify caching/disk persistence
        for (int pass = 1; pass <= 2; pass++) {
            for (int i = 0; i < frames.size(); i++) {
                String frameId = frames.get(i);
                ResponseEntity<byte[]> resp = restTemplate.exchange(
                        "/api/jobs/" + jobId + "/frames/" + frameId + "/image",
                        HttpMethod.GET, null, byte[].class);
                assertEquals(HttpStatus.OK, resp.getStatusCode(),
                        "Pass " + pass + ", frame " + i + " should return 200");
                assertNotNull(resp.getBody(),
                        "Pass " + pass + ", frame " + i + " body should not be null");
                assertTrue(resp.getBody().length > 0,
                        "Pass " + pass + ", frame " + i + " should have data");

                // Verify image decodable on each pass
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(resp.getBody()));
                assertNotNull(img,
                        "Pass " + pass + ", frame " + i + " should be decodable by ImageIO");
            }
        }
    }

    /**
     * Verifies that an image request for a frame from a different job
     * (cross-job access) returns 404.
     */
    @Test
    void getFrameImage_fromWrongJob_returns404() throws Exception {
        String profileId = "it-cross-job-cam";
        CameraProfile profile = new CameraProfile(
                profileId, "Cross-Job Test Camera", "mock", "localhost",
                5, 20, 0.15);
        restTemplate.postForEntity("/api/cameras", profile, CameraProfile.class);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Create and run job A
        String jobJsonA = """
                {
                    "cameraProfileId": "%s",
                    "parameters": {
                        "startPosition": 0, "stepSize": 5, "maxFrames": 2,
                        "abortThreshold": 0.01, "motionDetectionEnabled": false,
                        "qualityMode": "FAST"
                    }
                }
                """.formatted(profileId);
        ResponseEntity<StackingJob> createA = restTemplate.postForEntity(
                "/api/jobs", new HttpEntity<>(jobJsonA, headers), StackingJob.class);
        String jobIdA = createA.getBody().id();
        restTemplate.postForEntity("/api/jobs/" + jobIdA + "/start", null, StackingJob.class);
        StackingJob jobA = waitForTerminalState(jobIdA, 30);
        assertEquals("COMPLETED", jobA.status().name());

        // Create job B (do NOT start, so it has no frames)
        String jobJsonB = """
                {
                    "cameraProfileId": "%s",
                    "parameters": {
                        "startPosition": 0, "stepSize": 5, "maxFrames": 2,
                        "abortThreshold": 0.01, "motionDetectionEnabled": false,
                        "qualityMode": "FAST"
                    }
                }
                """.formatted(profileId);
        ResponseEntity<StackingJob> createB = restTemplate.postForEntity(
                "/api/jobs", new HttpEntity<>(jobJsonB, headers), StackingJob.class);
        String jobIdB = createB.getBody().id();

        // Try to access job A's frame through job B → should be 404
        String frameFromA = jobA.frames().get(0);
        ResponseEntity<byte[]> resp = restTemplate.exchange(
                "/api/jobs/" + jobIdB + "/frames/" + frameFromA + "/image",
                HttpMethod.GET, null, byte[].class);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "Accessing job A's frame via job B's endpoint should return 404");
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /**
     * Polls the job status until it reaches a terminal state or the timeout
     * is exceeded.
     */
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

    /**
     * Returns the first few bytes of the data as hex for diagnostic messages.
     */
    private static String magicBytesHex(byte[] data) {
        if (data == null || data.length == 0) return "(empty)";
        int len = Math.min(data.length, 8);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", data[i] & 0xFF));
        }
        return sb.toString();
    }
}
