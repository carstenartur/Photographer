package com.photographer.focusstack.astro;

import com.photographer.focusstack.camera.integration.AbstractPostgresIntegrationTest;
import com.photographer.focusstack.common.model.AstroSession;
import com.photographer.focusstack.common.model.AstroSessionStatus;
import com.photographer.focusstack.common.model.AstroStackingMode;
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

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"camera.mock.delay-ms=0"}
)
@AutoConfigureTestRestTemplate
class AstroRobustStackingFlowIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void setUpAuth() {
        restTemplate = restTemplate.withBasicAuth("admin", "TestAdminPassword!42");
    }

    @Test
    void medianSelectionIsPersistedExecutedAndReturnedAsProvenance()
            throws InterruptedException {
        String body = """
                {
                    "captureConfig": {
                        "frameCount": 5,
                        "exposureTimeMs": 100,
                        "iso": 1600,
                        "delayBetweenFramesMs": 0,
                        "imageFormat": "PNG",
                        "saveRaw": false,
                        "saveJpeg": true,
                        "captureDarkFrame": false,
                        "stackingConfig": {
                            "mode": "MEDIAN",
                            "sigmaThreshold": 2.5,
                            "maxIterations": 3,
                            "minimumSamples": 4
                        }
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
        AstroSession created = createResponse.getBody();
        assertNotNull(created);
        assertEquals(AstroStackingMode.MEDIAN,
                created.captureConfig().stackingConfig().mode());

        AstroSession terminal = waitForTerminal(created.id());

        assertEquals(AstroSessionStatus.COMPLETED, terminal.status(),
                () -> "Astro session failed: " + terminal.errorMessage());
        assertEquals(AstroStackingMode.MEDIAN,
                terminal.captureConfig().stackingConfig().mode());
        assertEquals(100, terminal.stackingProgressPercent());
        assertNotNull(terminal.stackingSummary());
        assertTrue(terminal.stackingSummary().contains("stacking=MEDIAN"));
    }

    @Test
    void omittedStackingConfigurationPreservesHistoricalMean()
            throws InterruptedException {
        String body = """
                {
                    "captureConfig": {
                        "frameCount": 1,
                        "exposureTimeMs": 100,
                        "iso": 800,
                        "delayBetweenFramesMs": 0,
                        "imageFormat": "PNG",
                        "saveRaw": false,
                        "saveJpeg": true,
                        "captureDarkFrame": false
                    }
                }
                """;

        ResponseEntity<AstroSession> response = restTemplate.postForEntity(
                "/api/astro/sessions",
                new HttpEntity<>(body, jsonHeaders()),
                AstroSession.class);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        AstroSession created = response.getBody();
        assertNotNull(created);
        assertEquals(AstroStackingMode.MEAN,
                created.captureConfig().stackingConfig().mode());

        AstroSession terminal = waitForTerminal(created.id());
        assertTrue(terminal.status() == AstroSessionStatus.COMPLETED
                        || terminal.status() == AstroSessionStatus.FAILED,
                () -> "Legacy Mean session unexpectedly ended as " + terminal.status());
        assertEquals(AstroStackingMode.MEAN,
                terminal.captureConfig().stackingConfig().mode());
    }

    private AstroSession waitForTerminal(String sessionId) throws InterruptedException {
        AstroSession session = null;
        for (int attempt = 0; attempt < 80; attempt++) {
            Thread.sleep(250);
            ResponseEntity<AstroSession> response = restTemplate.getForEntity(
                    "/api/astro/sessions/" + sessionId, AstroSession.class);
            assertEquals(HttpStatus.OK, response.getStatusCode());
            session = response.getBody();
            assertNotNull(session);
            if (session.status() == AstroSessionStatus.COMPLETED
                    || session.status() == AstroSessionStatus.FAILED
                    || session.status() == AstroSessionStatus.ABORTED) {
                return session;
            }
        }
        throw new AssertionError("Astro session did not reach a terminal state: " + session);
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
