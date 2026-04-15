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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for DELETE endpoints on stacking jobs and astro sessions.
 * Validates the full data lifecycle: create → complete → delete.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "camera.mock.delay-ms=0"
        }
)
@AutoConfigureTestRestTemplate
class DataLifecycleIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void setUpAuth() {
        restTemplate = restTemplate.withBasicAuth("admin", "focusstack");
    }

    @Test
    void deleteCompletedStackingJob() throws Exception {
        // Create camera profile
        CameraProfile profile = new CameraProfile(
                "lifecycle-cam", "Lifecycle Camera", "mock", "localhost", 5, 5, 0.1);
        restTemplate.postForEntity("/api/cameras", profile, CameraProfile.class);

        // Create and start a job
        String jobJson = """
                {
                    "cameraProfileId": "lifecycle-cam",
                    "parameters": {
                        "startPosition": 0,
                        "stepSize": 10,
                        "maxFrames": 3,
                        "abortThreshold": 0.001,
                        "motionDetectionEnabled": false,
                        "qualityMode": "FAST"
                    }
                }
                """;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<StackingJob> createResp = restTemplate.postForEntity(
                "/api/jobs", new HttpEntity<>(jobJson, headers), StackingJob.class);
        assertEquals(HttpStatus.CREATED, createResp.getStatusCode());
        String jobId = createResp.getBody().id();

        // Start the job
        restTemplate.postForEntity("/api/jobs/" + jobId + "/start", null, StackingJob.class);

        // Wait for completion
        StackingJob job = null;
        for (int i = 0; i < 30; i++) {
            TimeUnit.MILLISECONDS.sleep(500);
            job = restTemplate.getForObject("/api/jobs/" + jobId, StackingJob.class);
            if ("COMPLETED".equals(job.status().name()) || "FAILED".equals(job.status().name())) {
                break;
            }
        }
        assertNotNull(job);
        assertEquals("COMPLETED", job.status().name());
        assertTrue(job.frames().size() > 0, "Expected at least 1 captured frame");

        // DELETE the completed job
        ResponseEntity<Void> deleteResp = restTemplate.exchange(
                "/api/jobs/" + jobId, HttpMethod.DELETE, null, Void.class);
        assertEquals(HttpStatus.NO_CONTENT, deleteResp.getStatusCode());

        // Verify it's gone
        ResponseEntity<String> getResp = restTemplate.getForEntity(
                "/api/jobs/" + jobId, String.class);
        // The service throws CameraException("Job not found") which maps to 400 via GlobalExceptionHandler
        assertEquals(HttpStatus.BAD_REQUEST, getResp.getStatusCode(),
                "Expected 400 BAD_REQUEST after deletion, got " + getResp.getStatusCode());
    }

    @Test
    void deleteNonExistentJob_returns404() {
        ResponseEntity<Void> resp = restTemplate.exchange(
                "/api/jobs/nonexistent-id", HttpMethod.DELETE, null, Void.class);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    void deleteCompletedAstroSession() throws Exception {
        // Create an astro session (auto-starts) — all capture fields required
        String sessionJson = """
                {
                    "captureConfig": {
                        "frameCount": 3,
                        "exposureTimeMs": 100,
                        "iso": 1600,
                        "delayBetweenFramesMs": 50,
                        "imageFormat": "PNG",
                        "saveRaw": false,
                        "saveJpeg": true,
                        "captureDarkFrame": false
                    },
                    "focusConfig": {
                        "strategy": "MANUAL_FIXED",
                        "scanSteps": 0,
                        "scanStepSize": 0
                    }
                }
                """;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> createResp = restTemplate.postForEntity(
                "/api/astro/sessions", new HttpEntity<>(sessionJson, headers), String.class);
        assertEquals(HttpStatus.CREATED, createResp.getStatusCode());

        // Extract session ID from response using proper JSON parsing
        String body = createResp.getBody();
        assertNotNull(body);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = mapper.readTree(body);
        String sessionId = node.get("id").asText();
        assertNotNull(sessionId);

        // Wait for terminal state
        String status = null;
        for (int i = 0; i < 30; i++) {
            TimeUnit.MILLISECONDS.sleep(500);
            ResponseEntity<String> getResp = restTemplate.getForEntity(
                    "/api/astro/sessions/" + sessionId, String.class);
            if (getResp.getStatusCode() == HttpStatus.OK) {
                String respBody = getResp.getBody();
                if (respBody != null && (respBody.contains("COMPLETED") || respBody.contains("FAILED"))) {
                    status = respBody.contains("COMPLETED") ? "COMPLETED" : "FAILED";
                    break;
                }
            }
        }
        assertNotNull(status, "Astro session did not reach terminal state");

        // DELETE the session
        ResponseEntity<Void> deleteResp = restTemplate.exchange(
                "/api/astro/sessions/" + sessionId, HttpMethod.DELETE, null, Void.class);
        assertEquals(HttpStatus.NO_CONTENT, deleteResp.getStatusCode());

        // Verify it's gone
        ResponseEntity<String> getResp = restTemplate.getForEntity(
                "/api/astro/sessions/" + sessionId, String.class);
        assertEquals(HttpStatus.NOT_FOUND, getResp.getStatusCode());
    }

    @Test
    void deleteNonExistentAstroSession_returns404() {
        ResponseEntity<Void> resp = restTemplate.exchange(
                "/api/astro/sessions/nonexistent-id", HttpMethod.DELETE, null, Void.class);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }
}
