package com.photographer.focusstack.analysis.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class AnalysisServiceIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void setUpAuth() {
        restTemplate = restTemplate.withBasicAuth("admin", "focusstack");
    }

    @Test
    void sharpnessEndpoint() {
        byte[] imageData = createTestImageData();

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("image", new NamedByteArrayResource("image.bin", imageData));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/analysis/sharpness",
                new HttpEntity<>(body, headers),
                String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("sharpnessScore"));
    }

    @Test
    void motionEndpoint() {
        byte[] frame1 = createTestImageData();
        byte[] frame2 = createDifferentTestImageData();

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("currentFrame", new NamedByteArrayResource("current.bin", frame1));
        body.add("previousFrame", new NamedByteArrayResource("previous.bin", frame2));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/analysis/motion",
                new HttpEntity<>(body, headers),
                String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("motionScore"));
    }

    @Test
    void analyzeEndpoint() {
        byte[] imageData = createTestImageData();

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("image", new NamedByteArrayResource("image.bin", imageData));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/analysis/analyze",
                new HttpEntity<>(body, headers),
                String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void healthCheck() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/analysis/health", String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    private byte[] createTestImageData() {
        byte[] data = new byte[640 * 480];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) ((i * 7) % 256);
        }
        return data;
    }

    private byte[] createDifferentTestImageData() {
        byte[] data = new byte[640 * 480];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) ((i * 13 + 50) % 256);
        }
        return data;
    }

    private static class NamedByteArrayResource extends ByteArrayResource {
        private final String filename;

        NamedByteArrayResource(String filename, byte[] byteArray) {
            super(byteArray);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}
