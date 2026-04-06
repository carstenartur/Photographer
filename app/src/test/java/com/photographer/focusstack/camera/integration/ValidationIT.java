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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class ValidationIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void setUpAuth() {
        restTemplate = restTemplate.withBasicAuth("admin", "admin");
    }

    @Test
    void createProfile_withValidData_succeeds() {
        CameraProfile profile = new CameraProfile(
                "val-cam-1", "Valid Camera", "mock", "localhost", 5, 20, 0.15);
        ResponseEntity<CameraProfile> response = restTemplate.postForEntity(
                "/api/cameras", profile, CameraProfile.class);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("val-cam-1", response.getBody().id());
    }

    @Test
    void createProfile_withBlankName_returns400() {
        String json = """
                {
                    "id": "val-cam-bad",
                    "name": "",
                    "backendType": "mock",
                    "host": "localhost",
                    "defaultStepSize": 5,
                    "maxFrames": 20,
                    "movementThreshold": 0.15
                }
                """;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/cameras", new HttpEntity<>(json, headers), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void createProfile_withBlankHost_returns400() {
        String json = """
                {
                    "id": "val-cam-bad2",
                    "name": "Valid Name",
                    "backendType": "mock",
                    "host": "",
                    "defaultStepSize": 5,
                    "maxFrames": 20,
                    "movementThreshold": 0.15
                }
                """;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/cameras", new HttpEntity<>(json, headers), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void createProfile_withNegativeStepSize_returns400() {
        String json = """
                {
                    "id": "val-cam-bad3",
                    "name": "Valid Name",
                    "backendType": "mock",
                    "host": "localhost",
                    "defaultStepSize": -1,
                    "maxFrames": 20,
                    "movementThreshold": 0.15
                }
                """;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/cameras", new HttpEntity<>(json, headers), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void createJob_withValidData_succeeds() {
        String json = """
                {
                    "cameraProfileId": "job-cam-val",
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
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/jobs", new HttpEntity<>(json, headers), String.class);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("CREATED"));
    }

    @Test
    void createJob_withBlankCameraProfileId_returns400() {
        String json = """
                {
                    "cameraProfileId": "",
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
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/jobs", new HttpEntity<>(json, headers), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void createJob_withNegativeStepSize_returns400() {
        String json = """
                {
                    "cameraProfileId": "job-cam-x",
                    "parameters": {
                        "startPosition": 0,
                        "stepSize": -5,
                        "maxFrames": 10,
                        "abortThreshold": 0.05,
                        "motionDetectionEnabled": true,
                        "qualityMode": "BALANCED"
                    }
                }
                """;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/jobs", new HttpEntity<>(json, headers), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }
}
