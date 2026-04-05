package com.photographer.focusstack.camera.integration;

import com.photographer.focusstack.common.model.CameraProfile;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CameraServiceIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void cameraProfileCrudLifecycle() {
        CameraProfile profile = new CameraProfile(
                "int-cam-1", "Integration Camera", "mock", "localhost", 5, 20, 0.15);

        // Create
        ResponseEntity<CameraProfile> createResponse = restTemplate.postForEntity(
                "/api/cameras", profile, CameraProfile.class);
        assertEquals(HttpStatus.CREATED, createResponse.getStatusCode());
        assertNotNull(createResponse.getBody());
        assertEquals("int-cam-1", createResponse.getBody().id());

        // Read
        ResponseEntity<CameraProfile> getResponse = restTemplate.getForEntity(
                "/api/cameras/int-cam-1", CameraProfile.class);
        assertEquals(HttpStatus.OK, getResponse.getStatusCode());
        assertEquals("Integration Camera", getResponse.getBody().name());

        // Update
        CameraProfile updated = new CameraProfile(
                "int-cam-1", "Updated Camera", "ccapi", "192.168.1.100", 10, 40, 0.2);
        ResponseEntity<CameraProfile> updateResponse = restTemplate.exchange(
                "/api/cameras/int-cam-1", HttpMethod.PUT, new HttpEntity<>(updated), CameraProfile.class);
        assertEquals(HttpStatus.OK, updateResponse.getStatusCode());
        assertEquals("Updated Camera", updateResponse.getBody().name());

        // List — verify the created profile is present
        ResponseEntity<CameraProfile[]> listResponse = restTemplate.getForEntity(
                "/api/cameras", CameraProfile[].class);
        assertEquals(HttpStatus.OK, listResponse.getStatusCode());
        assertTrue(listResponse.getBody().length >= 1, "List should contain at least one camera");

        // Delete
        restTemplate.delete("/api/cameras/int-cam-1");
        ResponseEntity<CameraProfile> afterDelete = restTemplate.getForEntity(
                "/api/cameras/int-cam-1", CameraProfile.class);
        assertEquals(HttpStatus.NOT_FOUND, afterDelete.getStatusCode());
    }

    @Test
    void cameraStatusEndpoint() {
        CameraProfile profile = new CameraProfile(
                "int-cam-2", "Status Camera", "mock", "localhost", 5, 20, 0.15);
        restTemplate.postForEntity("/api/cameras", profile, CameraProfile.class);

        ResponseEntity<String> statusResponse = restTemplate.getForEntity(
                "/api/cameras/int-cam-2/status", String.class);
        assertEquals(HttpStatus.OK, statusResponse.getStatusCode());
        assertNotNull(statusResponse.getBody());
    }

    @Test
    void captureEndpoint() {
        CameraProfile profile = new CameraProfile(
                "int-cam-3", "Capture Camera", "mock", "localhost", 5, 20, 0.15);
        restTemplate.postForEntity("/api/cameras", profile, CameraProfile.class);

        ResponseEntity<String> captureResponse = restTemplate.postForEntity(
                "/api/cameras/int-cam-3/capture", null, String.class);
        assertEquals(HttpStatus.OK, captureResponse.getStatusCode());
        assertNotNull(captureResponse.getBody());
    }

    @Test
    void createAndRetrieveJob() {
        String body = """
                {
                    "cameraProfileId": "job-cam-1",
                    "parameters": {
                        "startPosition": 0,
                        "stepSize": 5,
                        "maxFrames": 10,
                        "abortThreshold": 0.05,
                        "motionDetectionEnabled": true,
                        "qualityMode": "BALANCED"
                    }
                }
                """;

        ResponseEntity<String> createResponse = restTemplate.postForEntity(
                "/api/jobs", new HttpEntity<>(body, jsonHeaders()), String.class);
        assertEquals(HttpStatus.CREATED, createResponse.getStatusCode());

        ResponseEntity<String> listResponse = restTemplate.getForEntity("/api/jobs", String.class);
        assertEquals(HttpStatus.OK, listResponse.getStatusCode());
        assertNotNull(listResponse.getBody());
    }

    private org.springframework.http.HttpHeaders jsonHeaders() {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return headers;
    }
}
