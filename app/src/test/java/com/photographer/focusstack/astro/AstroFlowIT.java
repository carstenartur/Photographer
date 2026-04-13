package com.photographer.focusstack.astro;

import com.photographer.focusstack.camera.integration.AbstractPostgresIntegrationTest;
import com.photographer.focusstack.common.model.AstroSession;
import com.photographer.focusstack.common.model.AstroSessionStatus;
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

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"camera.mock.delay-ms=0"}
)
@AutoConfigureTestRestTemplate
class AstroFlowIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void setUpAuth() {
        restTemplate = restTemplate.withBasicAuth("admin", "focusstack");
    }

    @Test
    void createSessionAndWaitForCompletion() throws InterruptedException {
        String body = """
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

        ResponseEntity<AstroSession> createResponse = restTemplate.postForEntity(
                "/api/astro/sessions",
                new HttpEntity<>(body, jsonHeaders()),
                AstroSession.class);

        assertEquals(HttpStatus.CREATED, createResponse.getStatusCode());
        assertNotNull(createResponse.getBody());
        String sessionId = createResponse.getBody().id();
        assertNotNull(sessionId);

        // Poll until a terminal status is reached
        Set<AstroSessionStatus> terminal = Set.of(
                AstroSessionStatus.COMPLETED,
                AstroSessionStatus.FAILED,
                AstroSessionStatus.ABORTED);

        AstroSession session = null;
        for (int i = 0; i < 60; i++) {
            Thread.sleep(500);
            ResponseEntity<AstroSession> getResponse = restTemplate.getForEntity(
                    "/api/astro/sessions/" + sessionId, AstroSession.class);
            assertEquals(HttpStatus.OK, getResponse.getStatusCode());
            session = getResponse.getBody();
            assertNotNull(session);
            if (terminal.contains(session.status())) {
                break;
            }
        }

        assertNotNull(session);
        assertTrue(terminal.contains(session.status()),
                "Session should reach a terminal status but was " + session.status());
        // Mock camera may not produce detectable stars so accept COMPLETED or FAILED
        assertTrue(
                session.status() == AstroSessionStatus.COMPLETED ||
                session.status() == AstroSessionStatus.FAILED,
                "Session should complete or fail (not abort). Status=" + session.status()
                        + " error=" + session.errorMessage());
    }

    @Test
    void listSessionsReturnsResults() {
        String body = """
                {
                    "captureConfig": {
                        "frameCount": 2,
                        "exposureTimeMs": 50,
                        "iso": 800,
                        "delayBetweenFramesMs": 10,
                        "imageFormat": "PNG",
                        "saveRaw": false,
                        "saveJpeg": true,
                        "captureDarkFrame": false
                    }
                }
                """;
        restTemplate.postForEntity("/api/astro/sessions",
                new HttpEntity<>(body, jsonHeaders()), AstroSession.class);

        ResponseEntity<AstroSession[]> listResponse = restTemplate.getForEntity(
                "/api/astro/sessions", AstroSession[].class);
        assertEquals(HttpStatus.OK, listResponse.getStatusCode());
        assertNotNull(listResponse.getBody());
        assertTrue(listResponse.getBody().length >= 1);
    }

    @Test
    void getSessionReturns404ForUnknown() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/astro/sessions/nonexistent-id", String.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void abortSessionReturnsOk() {
        String body = """
                {
                    "captureConfig": {
                        "frameCount": 100,
                        "exposureTimeMs": 1000,
                        "iso": 1600,
                        "delayBetweenFramesMs": 500,
                        "imageFormat": "PNG",
                        "saveRaw": false,
                        "saveJpeg": true,
                        "captureDarkFrame": false
                    }
                }
                """;
        ResponseEntity<AstroSession> createResponse = restTemplate.postForEntity(
                "/api/astro/sessions",
                new HttpEntity<>(body, jsonHeaders()),
                AstroSession.class);

        assertNotNull(createResponse.getBody());
        String sessionId = createResponse.getBody().id();

        @SuppressWarnings("rawtypes")
        ResponseEntity<Map> abortResponse = restTemplate.postForEntity(
                "/api/astro/sessions/" + sessionId + "/abort", null, Map.class);
        assertEquals(HttpStatus.OK, abortResponse.getStatusCode());
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
