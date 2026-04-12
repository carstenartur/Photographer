package com.photographer.focusstack.camera.integration;

import com.photographer.focusstack.common.model.CaptureSession;
import com.photographer.focusstack.common.model.HdrSettings;
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
class HdrTimelapseIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void setUpAuth() {
        restTemplate = restTemplate.withBasicAuth("admin", "focusstack");
    }

    @Test
    void createAndGetSession() {
        HdrSettings settings = HdrSettings.defaults();
        HttpEntity<HdrSettings> entity = new HttpEntity<>(settings, jsonHeaders());

        ResponseEntity<CaptureSession> createResponse = restTemplate.postForEntity(
                "/api/hdr-timelapse", entity, CaptureSession.class);
        assertEquals(HttpStatus.CREATED, createResponse.getStatusCode());
        assertNotNull(createResponse.getBody());
        assertEquals("hdr-timelapse", createResponse.getBody().type());

        String sessionId = createResponse.getBody().sessionId();
        assertNotNull(sessionId);

        ResponseEntity<CaptureSession> getResponse = restTemplate.getForEntity(
                "/api/hdr-timelapse/" + sessionId, CaptureSession.class);
        assertEquals(HttpStatus.OK, getResponse.getStatusCode());
        assertEquals(sessionId, getResponse.getBody().sessionId());
    }

    @Test
    void stopSession() {
        HdrSettings settings = HdrSettings.defaults();
        HttpEntity<HdrSettings> entity = new HttpEntity<>(settings, jsonHeaders());

        ResponseEntity<CaptureSession> createResponse = restTemplate.postForEntity(
                "/api/hdr-timelapse", entity, CaptureSession.class);
        String sessionId = createResponse.getBody().sessionId();

        ResponseEntity<Void> stopResponse = restTemplate.postForEntity(
                "/api/hdr-timelapse/" + sessionId + "/stop", null, Void.class);
        assertEquals(HttpStatus.OK, stopResponse.getStatusCode());
    }

    @Test
    void getBracketsForNewSession() {
        HdrSettings settings = HdrSettings.defaults();
        HttpEntity<HdrSettings> entity = new HttpEntity<>(settings, jsonHeaders());

        ResponseEntity<CaptureSession> createResponse = restTemplate.postForEntity(
                "/api/hdr-timelapse", entity, CaptureSession.class);
        String sessionId = createResponse.getBody().sessionId();

        ResponseEntity<String> bracketsResponse = restTemplate.getForEntity(
                "/api/hdr-timelapse/" + sessionId + "/brackets", String.class);
        assertEquals(HttpStatus.OK, bracketsResponse.getStatusCode());
        assertNotNull(bracketsResponse.getBody());
    }

    @Test
    void sessionNotFound() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/hdr-timelapse/nonexistent-id", String.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
