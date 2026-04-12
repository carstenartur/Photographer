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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration test for the complete focus-stacking workflow:
 * <ol>
 *   <li>Create a mock camera profile</li>
 *   <li>Create a stacking job</li>
 *   <li>Start the job (async capture loop)</li>
 *   <li>Wait for the job to reach a terminal state</li>
 *   <li>Verify frames are persisted in the database</li>
 *   <li>Verify each frame's image data is retrievable via REST</li>
 * </ol>
 *
 * <p>This test uses the {@code MockCameraBackend} (configured via
 * {@code camera.mock.delay-ms=0} for speed) and exercises the entire
 * pipeline from REST controllers through the async capture loop to the
 * in-memory {@code FrameDataStore}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "camera.mock.delay-ms=0"
        }
)
@AutoConfigureTestRestTemplate
class FocusStackingFlowIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void setUpAuth() {
        restTemplate = restTemplate.withBasicAuth("admin", "focusstack");
    }

    /**
     * Full happy-path: create profile → create job → start → wait for
     * COMPLETED → verify frames in DB → download every frame image via REST.
     */
    @Test
    void fullStackingFlow_capturesFramesAndServesImages() throws Exception {
        // ── 1. Create a mock camera profile ─────────────────────────────
        CameraProfile profile = new CameraProfile(
                "it-mock-cam", "IT Mock Camera", "mock", "localhost",
                5, 20, 0.15);
        ResponseEntity<CameraProfile> profileResp = restTemplate.postForEntity(
                "/api/cameras", profile, CameraProfile.class);
        assertEquals(HttpStatus.CREATED, profileResp.getStatusCode(),
                "Camera profile creation should return 201");

        // ── 2. Create a stacking job ────────────────────────────────────
        String jobJson = """
                {
                    "cameraProfileId": "it-mock-cam",
                    "parameters": {
                        "startPosition": 0,
                        "stepSize": 5,
                        "maxFrames": 5,
                        "abortThreshold": 0.01,
                        "motionDetectionEnabled": false,
                        "qualityMode": "FAST"
                    }
                }
                """;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<StackingJob> createResp = restTemplate.postForEntity(
                "/api/jobs", new HttpEntity<>(jobJson, headers), StackingJob.class);
        assertEquals(HttpStatus.CREATED, createResp.getStatusCode(),
                "Job creation should return 201");
        StackingJob created = createResp.getBody();
        assertNotNull(created);
        assertNotNull(created.id());
        assertEquals("CREATED", created.status().name());

        String jobId = created.id();

        // ── 3. Start the job ────────────────────────────────────────────
        ResponseEntity<StackingJob> startResp = restTemplate.postForEntity(
                "/api/jobs/" + jobId + "/start", null, StackingJob.class);
        assertEquals(HttpStatus.ACCEPTED, startResp.getStatusCode(),
                "Starting the job should return 202 Accepted");

        // ── 4. Wait for the job to reach a terminal state ───────────────
        StackingJob finalJob = waitForTerminalState(jobId, 30);
        assertNotNull(finalJob, "Job should have reached a terminal state within timeout");
        assertEquals("COMPLETED", finalJob.status().name(),
                "Job should complete successfully, but was: " + finalJob.status()
                        + " — frames captured: " + (finalJob.frames() != null ? finalJob.frames().size() : 0));

        // ── 5. Verify frames are persisted ──────────────────────────────
        List<String> frames = finalJob.frames();
        assertNotNull(frames, "Completed job must have a frames list");
        assertFalse(frames.isEmpty(),
                "Completed job must have at least one captured frame");
        assertTrue(frames.size() >= 2,
                "Expected at least 2 frames but got " + frames.size());

        // ── 6. Verify each frame image is retrievable via REST ──────────
        for (int i = 0; i < frames.size(); i++) {
            String frameId = frames.get(i);
            ResponseEntity<byte[]> imageResp = restTemplate.getForEntity(
                    "/api/jobs/" + jobId + "/frames/" + frameId + "/image",
                    byte[].class);
            assertEquals(HttpStatus.OK, imageResp.getStatusCode(),
                    "Frame " + i + " (" + frameId + ") image should be retrievable (HTTP 200), "
                            + "but got " + imageResp.getStatusCode());
            assertNotNull(imageResp.getBody(),
                    "Frame " + i + " image body must not be null");
            assertTrue(imageResp.getBody().length > 0,
                    "Frame " + i + " image must have non-empty data");

            // Verify the response looks like an image (PNG magic bytes or JPEG)
            byte[] data = imageResp.getBody();
            boolean isPng = data.length >= 4
                    && (data[0] & 0xFF) == 0x89
                    && data[1] == 0x50;
            boolean isJpeg = data.length >= 2
                    && (data[0] & 0xFF) == 0xFF
                    && (data[1] & 0xFF) == 0xD8;
            assertTrue(isPng || isJpeg,
                    "Frame " + i + " should be a valid PNG or JPEG image");
        }
    }

    /**
     * Verify that a non-existent frame returns 404.
     */
    @Test
    void getFrameImage_unknownFrame_returns404() throws Exception {
        // Create profile and job, but don't start — so no frames
        CameraProfile profile = new CameraProfile(
                "it-mock-cam-404", "IT Mock Camera 404", "mock", "localhost",
                5, 20, 0.15);
        restTemplate.postForEntity("/api/cameras", profile, CameraProfile.class);

        String jobJson = """
                {
                    "cameraProfileId": "it-mock-cam-404",
                    "parameters": {
                        "startPosition": 0,
                        "stepSize": 5,
                        "maxFrames": 5,
                        "abortThreshold": 0.01,
                        "motionDetectionEnabled": false,
                        "qualityMode": "FAST"
                    }
                }
                """;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<StackingJob> createResp = restTemplate.postForEntity(
                "/api/jobs", new HttpEntity<>(jobJson, headers), StackingJob.class);
        String jobId = createResp.getBody().id();

        ResponseEntity<byte[]> imageResp = restTemplate.getForEntity(
                "/api/jobs/" + jobId + "/frames/nonexistent-frame-id/image",
                byte[].class);
        assertEquals(HttpStatus.NOT_FOUND, imageResp.getStatusCode(),
                "Unknown frame should return 404");
    }

    /**
     * Verify the ZIP export endpoint works for a completed job.
     */
    @Test
    void exportFrames_afterCompletion_returnsZip() throws Exception {
        // Create profile
        CameraProfile profile = new CameraProfile(
                "it-mock-cam-export", "IT Mock Camera Export", "mock", "localhost",
                5, 20, 0.15);
        restTemplate.postForEntity("/api/cameras", profile, CameraProfile.class);

        // Create and start job
        String jobJson = """
                {
                    "cameraProfileId": "it-mock-cam-export",
                    "parameters": {
                        "startPosition": 0,
                        "stepSize": 5,
                        "maxFrames": 3,
                        "abortThreshold": 0.01,
                        "motionDetectionEnabled": false,
                        "qualityMode": "FAST"
                    }
                }
                """;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<StackingJob> createResp = restTemplate.postForEntity(
                "/api/jobs", new HttpEntity<>(jobJson, headers), StackingJob.class);
        String jobId = createResp.getBody().id();

        restTemplate.postForEntity("/api/jobs/" + jobId + "/start", null, StackingJob.class);

        StackingJob finalJob = waitForTerminalState(jobId, 30);
        assertEquals("COMPLETED", finalJob.status().name());

        // Export
        ResponseEntity<byte[]> exportResp = restTemplate.getForEntity(
                "/api/jobs/" + jobId + "/export", byte[].class);
        assertEquals(HttpStatus.OK, exportResp.getStatusCode(),
                "Export should return 200");
        assertNotNull(exportResp.getBody());
        assertTrue(exportResp.getBody().length > 0,
                "ZIP export should contain data");

        // ZIP files start with PK magic bytes (0x50 0x4B)
        byte[] zip = exportResp.getBody();
        assertTrue(zip.length >= 4
                        && zip[0] == 0x50 && zip[1] == 0x4B,
                "Export should be a valid ZIP file");
    }

    // ── Helper ──────────────────────────────────────────────────────────

    /**
     * Polls the job status until it reaches a terminal state (COMPLETED, FAILED,
     * or CANCELLED) or the timeout is exceeded.
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
        return job; // Return last known state (may still be RUNNING)
    }
}
