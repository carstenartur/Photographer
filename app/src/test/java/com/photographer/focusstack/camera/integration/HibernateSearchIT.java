package com.photographer.focusstack.camera.integration;

import com.photographer.focusstack.camera.entity.CameraProfileEntity;
import com.photographer.focusstack.camera.repository.CameraProfileRepository;
import com.photographer.focusstack.camera.search.SearchService;
import com.photographer.focusstack.common.model.CameraProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HibernateSearchIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private CameraProfileRepository cameraProfileRepository;

    @Autowired
    private SearchService searchService;

    @BeforeEach
    void setUp() {
        cameraProfileRepository.deleteAll();
    }

    @Test
    void searchCameraProfiles_byName() {
        // Create profiles via REST API (which also indexes them)
        restTemplate.postForEntity("/api/cameras",
                new CameraProfile("search-cam-1", "Canon G5X Macro", "ccapi", "192.168.1.50", 5, 20, 0.1),
                CameraProfile.class);
        restTemplate.postForEntity("/api/cameras",
                new CameraProfile("search-cam-2", "Nikon D850 Landscape", "mock", "192.168.1.60", 10, 40, 0.2),
                CameraProfile.class);
        restTemplate.postForEntity("/api/cameras",
                new CameraProfile("search-cam-3", "Canon G7X Studio", "ccapi", "192.168.1.70", 3, 15, 0.08),
                CameraProfile.class);

        // Reindex to ensure search index is up to date
        searchService.reindexAll();

        // Search for "Canon"
        ResponseEntity<CameraProfile[]> response = restTemplate.getForEntity(
                "/api/cameras/search?q=Canon", CameraProfile[].class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(2, response.getBody().length);
    }

    @Test
    void searchCameraProfiles_byBackendType() {
        restTemplate.postForEntity("/api/cameras",
                new CameraProfile("search-bt-1", "Camera One", "ccapi", "192.168.1.1", 5, 20, 0.1),
                CameraProfile.class);
        restTemplate.postForEntity("/api/cameras",
                new CameraProfile("search-bt-2", "Camera Two", "mock", "192.168.1.2", 10, 40, 0.2),
                CameraProfile.class);

        searchService.reindexAll();

        ResponseEntity<CameraProfile[]> response = restTemplate.getForEntity(
                "/api/cameras/search?q=ccapi", CameraProfile[].class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().length);
        assertEquals("search-bt-1", response.getBody()[0].id());
    }

    @Test
    void searchCameraProfiles_noResults() {
        restTemplate.postForEntity("/api/cameras",
                new CameraProfile("search-nr-1", "Camera One", "mock", "localhost", 5, 20, 0.1),
                CameraProfile.class);

        searchService.reindexAll();

        ResponseEntity<CameraProfile[]> response = restTemplate.getForEntity(
                "/api/cameras/search?q=nonexistent", CameraProfile[].class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(0, response.getBody().length);
    }

    @Test
    void searchJobs_byStatus() {
        // Create a job first
        String jobJson = """
                {
                    "cameraProfileId": "search-job-cam",
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
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        restTemplate.postForEntity("/api/jobs",
                new org.springframework.http.HttpEntity<>(jobJson, headers), String.class);

        searchService.reindexAll();

        // Search by cameraProfileId (a keyword field that indexes strings directly)
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/jobs/search?q=search-job-cam", String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("search-job-cam"));
    }

    @Test
    void reindexEndpoint_succeeds() {
        ResponseEntity<Void> response = restTemplate.postForEntity(
                "/api/jobs/reindex", null, Void.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void searchServiceDirectly_cameraProfiles() {
        cameraProfileRepository.save(new CameraProfileEntity(
                "direct-1", "G5X Mark II", "ccapi", "192.168.1.50", 5, 20, 0.1));
        cameraProfileRepository.save(new CameraProfileEntity(
                "direct-2", "Mock Tester", "mock", "localhost", 10, 30, 0.2));

        searchService.reindexAll();

        List<CameraProfileEntity> results = searchService.searchCameraProfiles("G5X", 10);
        assertEquals(1, results.size());
        assertEquals("direct-1", results.get(0).getId());
    }
}
