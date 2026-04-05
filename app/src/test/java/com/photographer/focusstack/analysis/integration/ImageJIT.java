package com.photographer.focusstack.analysis.integration;

import com.photographer.focusstack.analysis.service.ImageJProcessor;
import com.photographer.focusstack.common.analysis.SharpnessMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that verifies ImageJ processing works within the
 * Spring Boot application context. Tests both the ImageJProcessor bean
 * and the REST endpoints with actual image files.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ImageJIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ImageJProcessor imageJProcessor;

    @BeforeEach
    void setUpAuth() {
        restTemplate = restTemplate.withBasicAuth("admin", "admin");
    }

    @Test
    void imageJProcessorBeanShouldBeAvailable() {
        assertNotNull(imageJProcessor, "ImageJProcessor should be available as a Spring bean");
        assertNotNull(imageJProcessor.getVersion(), "ImageJ version should be available");
    }

    @Test
    void healthEndpointShouldReportImageJ() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/analysis/health", String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("ImageJ"), "Health should report ImageJ engine");
        assertTrue(response.getBody().contains("imageJVersion"), "Health should report ImageJ version");
    }

    @Test
    void shouldAnalyzeSharpnessOfPngImage() throws IOException {
        byte[] sharpImage = createSharpTestImage();

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("image", new NamedByteArrayResource("sharp.png", sharpImage));

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
    void shouldAnalyzeMotionBetweenPngImages() throws IOException {
        byte[] image1 = createSharpTestImage();
        byte[] image2 = createDifferentImage();

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("currentFrame", new NamedByteArrayResource("current.png", image1));
        body.add("previousFrame", new NamedByteArrayResource("previous.png", image2));

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
    void shouldCreateSharpnessMapFromPngImage() throws IOException {
        byte[] image = createSharpTestImage();

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("image", new NamedByteArrayResource("test.png", image));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/analysis/sharpness-map?blockSize=32",
                new HttpEntity<>(body, headers),
                String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("overallScore"));
        assertTrue(response.getBody().contains("scores"));
    }

    @Test
    void imageJProcessorShouldComputeHigherSharpnessForEdges() throws IOException {
        byte[] sharpImage = createSharpTestImage();
        byte[] blurryImage = createUniformImage();

        double sharpScore = imageJProcessor.calculateSharpness(sharpImage);
        double blurryScore = imageJProcessor.calculateSharpness(blurryImage);

        assertTrue(sharpScore > blurryScore,
                "Sharp image (" + sharpScore + ") should score higher than blurry (" + blurryScore + ")");
    }

    @Test
    void imageJProcessorShouldDetectMotionBetweenDifferentImages() throws IOException {
        byte[] image1 = createSharpTestImage();
        byte[] image2 = createDifferentImage();

        var result = imageJProcessor.detectMotion(image1, image2, 0.01);
        assertTrue(result.motionScore() > 0.0,
                "Should detect motion between different images, score: " + result.motionScore());
    }

    @Test
    void imageJProcessorShouldCreateBlockwiseSharpnessMap() throws IOException {
        byte[] image = createSharpTestImage();
        SharpnessMap map = imageJProcessor.createSharpnessMap(image, 32);

        assertNotNull(map);
        assertTrue(map.width() > 0);
        assertTrue(map.height() > 0);
        assertTrue(map.overallScore() >= 0.0);
        assertEquals(32, map.blockSize());

        // Verify scores array dimensions match width/height
        assertEquals(map.height(), map.scores().length);
        if (map.scores().length > 0) {
            assertEquals(map.width(), map.scores()[0].length);
        }
    }

    @Test
    void shouldStillWorkWithRawByteData() {
        // Existing raw byte analysis should still work
        byte[] rawData = new byte[640 * 480];
        for (int i = 0; i < rawData.length; i++) {
            rawData[i] = (byte) ((i * 7) % 256);
        }

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("image", new NamedByteArrayResource("image.bin", rawData));

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

    // --- Helper methods ---

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

    private byte[] createUniformImage() throws IOException {
        BufferedImage img = new BufferedImage(200, 200, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g2d = img.createGraphics();
        g2d.setColor(Color.GRAY);
        g2d.fillRect(0, 0, 200, 200);
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
