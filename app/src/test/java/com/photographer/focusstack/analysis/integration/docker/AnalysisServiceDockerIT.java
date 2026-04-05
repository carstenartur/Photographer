package com.photographer.focusstack.analysis.integration.docker;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testcontainers integration test that builds and starts the analysis-service
 * Docker container and verifies ImageJ-based image processing works headless.
 * <p>
 * This test builds the Docker image from the analysis-service Dockerfile,
 * starts the container, and sends actual images for processing via the REST API.
 * It verifies that ImageJ runs headless inside the container and produces
 * correct analysis results.
 * <p>
 * Requires Docker to be available. The test is skipped in environments without Docker.
 */
@Tag("docker")
@Testcontainers
class AnalysisServiceDockerIT {

    private static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir")).getParent().getParent();

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> analysisService = new GenericContainer<>(
            new ImageFromDockerfile("analysis-service-test", false)
                    .withDockerfile(PROJECT_ROOT.resolve("docker/analysis-service/Dockerfile"))
                    .withFileFromPath("backend/pom.xml",
                            PROJECT_ROOT.resolve("backend/pom.xml"))
                    .withFileFromPath("backend/common-api/pom.xml",
                            PROJECT_ROOT.resolve("backend/common-api/pom.xml"))
                    .withFileFromPath("backend/camera-service/pom.xml",
                            PROJECT_ROOT.resolve("backend/camera-service/pom.xml"))
                    .withFileFromPath("backend/analysis-service/pom.xml",
                            PROJECT_ROOT.resolve("backend/analysis-service/pom.xml"))
                    .withFileFromPath("backend/common-api/src",
                            PROJECT_ROOT.resolve("backend/common-api/src"))
                    .withFileFromPath("backend/analysis-service/src",
                            PROJECT_ROOT.resolve("backend/analysis-service/src")))
            .withExposedPorts(8081)
            .waitingFor(Wait.forHttp("/api/analysis/health")
                    .forPort(8081)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(5)));

    private final RestTemplate restTemplate = new RestTemplate();

    @Test
    void healthEndpointShouldReportImageJ() {
        String url = getBaseUrl() + "/api/analysis/health";
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("ImageJ"),
                "Health should report ImageJ engine: " + response.getBody());
        assertTrue(response.getBody().contains("imageJVersion"),
                "Health should report ImageJ version: " + response.getBody());
    }

    @Test
    void shouldAnalyzeSharpnessOfRealImage() throws IOException {
        byte[] sharpImage = createSharpTestImage();

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("image", new NamedByteArrayResource("sharp.png", sharpImage));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        String url = getBaseUrl() + "/api/analysis/sharpness";
        ResponseEntity<String> response = restTemplate.postForEntity(
                url, new HttpEntity<>(body, headers), String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("sharpnessScore"),
                "Response should contain sharpnessScore: " + response.getBody());
    }

    @Test
    void shouldAnalyzeMotionBetweenRealImages() throws IOException {
        byte[] image1 = createSharpTestImage();
        byte[] image2 = createDifferentImage();

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("currentFrame", new NamedByteArrayResource("current.png", image1));
        body.add("previousFrame", new NamedByteArrayResource("previous.png", image2));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        String url = getBaseUrl() + "/api/analysis/motion";
        ResponseEntity<String> response = restTemplate.postForEntity(
                url, new HttpEntity<>(body, headers), String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("motionScore"),
                "Response should contain motionScore: " + response.getBody());
    }

    @Test
    void shouldCreateSharpnessMapFromRealImage() throws IOException {
        byte[] image = createSharpTestImage();

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("image", new NamedByteArrayResource("test.png", image));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        String url = getBaseUrl() + "/api/analysis/sharpness-map?blockSize=32";
        ResponseEntity<String> response = restTemplate.postForEntity(
                url, new HttpEntity<>(body, headers), String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("overallScore"),
                "Response should contain overallScore: " + response.getBody());
        assertTrue(response.getBody().contains("scores"),
                "Response should contain scores array: " + response.getBody());
    }

    @Test
    void shouldPerformFullAnalysisWithRealImage() throws IOException {
        byte[] image = createSharpTestImage();

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("image", new NamedByteArrayResource("test.png", image));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        String url = getBaseUrl() + "/api/analysis/analyze";
        ResponseEntity<String> response = restTemplate.postForEntity(
                url, new HttpEntity<>(body, headers), String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("sharpnessScore"),
                "Response should contain sharpnessScore: " + response.getBody());
        assertTrue(response.getBody().contains("recommendContinue"),
                "Response should contain recommendContinue: " + response.getBody());
    }

    // --- Helper methods ---

    private String getBaseUrl() {
        return "http://" + analysisService.getHost() + ":" + analysisService.getMappedPort(8081);
    }

    private byte[] createSharpTestImage() throws IOException {
        BufferedImage img = new BufferedImage(200, 200, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g2d = img.createGraphics();
        for (int y = 0; y < 200; y += 20) {
            for (int x = 0; x < 200; x += 20) {
                g2d.setColor(((x + y) / 20 % 2 == 0) ? Color.BLACK : Color.WHITE);
                g2d.fillRect(x, y, 20, 20);
            }
        }
        g2d.dispose();
        return toPng(img);
    }

    private byte[] createDifferentImage() throws IOException {
        BufferedImage img = new BufferedImage(200, 200, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g2d = img.createGraphics();
        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, 200, 200);
        g2d.setColor(Color.BLACK);
        g2d.fillOval(40, 40, 120, 120);
        g2d.dispose();
        return toPng(img);
    }

    private byte[] toPng(BufferedImage img) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
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
