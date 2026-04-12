package com.photographer.focusstack.camera.integration;

import com.photographer.focusstack.common.model.CaptureSession;
import com.photographer.focusstack.common.model.FaceOrbitSettings;
import com.photographer.focusstack.common.model.QualityCheck;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class FaceOrbitIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void setUpAuth() {
        restTemplate = restTemplate.withBasicAuth("admin", "focusstack");
    }

    @Test
    void createAndGetSession() {
        FaceOrbitSettings settings = FaceOrbitSettings.defaults();
        HttpEntity<FaceOrbitSettings> entity = new HttpEntity<>(settings, jsonHeaders());

        ResponseEntity<CaptureSession> createResponse = restTemplate.postForEntity(
                "/api/face-orbit", entity, CaptureSession.class);
        assertEquals(HttpStatus.CREATED, createResponse.getStatusCode());
        assertNotNull(createResponse.getBody());
        assertEquals("face-orbit", createResponse.getBody().type());

        String sessionId = createResponse.getBody().sessionId();
        assertNotNull(sessionId);

        ResponseEntity<CaptureSession> getResponse = restTemplate.getForEntity(
                "/api/face-orbit/" + sessionId, CaptureSession.class);
        assertEquals(HttpStatus.OK, getResponse.getStatusCode());
        assertEquals(sessionId, getResponse.getBody().sessionId());
    }

    @Test
    void captureNextStep() {
        FaceOrbitSettings settings = FaceOrbitSettings.defaults();
        HttpEntity<FaceOrbitSettings> entity = new HttpEntity<>(settings, jsonHeaders());

        ResponseEntity<CaptureSession> createResponse = restTemplate.postForEntity(
                "/api/face-orbit", entity, CaptureSession.class);
        String sessionId = createResponse.getBody().sessionId();

        ResponseEntity<QualityCheck> captureResponse = restTemplate.postForEntity(
                "/api/face-orbit/" + sessionId + "/capture", null, QualityCheck.class);
        assertEquals(HttpStatus.OK, captureResponse.getStatusCode());
        assertNotNull(captureResponse.getBody());
        assertNotNull(captureResponse.getBody().frameId());
    }

    @Test
    void completeSession() {
        FaceOrbitSettings settings = FaceOrbitSettings.defaults();
        HttpEntity<FaceOrbitSettings> entity = new HttpEntity<>(settings, jsonHeaders());

        ResponseEntity<CaptureSession> createResponse = restTemplate.postForEntity(
                "/api/face-orbit", entity, CaptureSession.class);
        String sessionId = createResponse.getBody().sessionId();

        // Capture at least one step first
        restTemplate.postForEntity(
                "/api/face-orbit/" + sessionId + "/capture", null, QualityCheck.class);

        ResponseEntity<CaptureSession> completeResponse = restTemplate.postForEntity(
                "/api/face-orbit/" + sessionId + "/complete", null, CaptureSession.class);
        assertEquals(HttpStatus.OK, completeResponse.getStatusCode());
        assertEquals("COMPLETED", completeResponse.getBody().status().name());
    }

    @Test
    void getQualityChecks() {
        FaceOrbitSettings settings = FaceOrbitSettings.defaults();
        HttpEntity<FaceOrbitSettings> entity = new HttpEntity<>(settings, jsonHeaders());

        ResponseEntity<CaptureSession> createResponse = restTemplate.postForEntity(
                "/api/face-orbit", entity, CaptureSession.class);
        String sessionId = createResponse.getBody().sessionId();

        ResponseEntity<String> qualityResponse = restTemplate.getForEntity(
                "/api/face-orbit/" + sessionId + "/quality", String.class);
        assertEquals(HttpStatus.OK, qualityResponse.getStatusCode());
    }

    @Test
    void sessionNotFound() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/face-orbit/nonexistent-id", String.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
