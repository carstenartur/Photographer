package com.photographer.focusstack.analysis.service;

import com.photographer.focusstack.common.analysis.SharpnessMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class ImageJProcessorTest {

    private final ImageJProcessor processor = new ImageJProcessor();

    @BeforeAll
    static void setHeadless() {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    void shouldCalculateSharpnessForSharpImage() throws IOException {
        byte[] sharpImage = createSharpTestImage();
        double score = processor.calculateSharpness(sharpImage);
        assertTrue(score > 0.0, "Sharp image should have positive sharpness, got: " + score);
    }

    @Test
    void shouldCalculateSharpnessForBlurryImage() throws IOException {
        byte[] blurryImage = createUniformImage();
        double score = processor.calculateSharpness(blurryImage);
        assertTrue(score < 0.05, "Uniform/blurry image should have low sharpness, got: " + score);
    }

    @Test
    void sharpImageShouldScoreHigherThanBlurry() throws IOException {
        byte[] sharpImage = createSharpTestImage();
        byte[] blurryImage = createUniformImage();

        double sharpScore = processor.calculateSharpness(sharpImage);
        double blurryScore = processor.calculateSharpness(blurryImage);

        assertTrue(sharpScore > blurryScore,
                "Sharp image (" + sharpScore + ") should score higher than blurry (" + blurryScore + ")");
    }

    @Test
    void shouldReturnZeroForNullInput() {
        assertEquals(0.0, processor.calculateSharpness(null));
    }

    @Test
    void shouldReturnZeroForEmptyInput() {
        assertEquals(0.0, processor.calculateSharpness(new byte[0]));
    }

    @Test
    void shouldReturnZeroForInvalidImageData() {
        assertEquals(0.0, processor.calculateSharpness(new byte[]{1, 2, 3, 4, 5}));
    }

    @Test
    void shouldDetectNoMotionForIdenticalImages() throws IOException {
        byte[] image = createSharpTestImage();
        MotionDetector.MotionResult result = processor.detectMotion(image, image, 0.15);
        assertEquals(0.0, result.motionScore(), 0.001);
        assertFalse(result.motionDetected());
    }

    @Test
    void shouldDetectMotionForDifferentImages() throws IOException {
        byte[] image1 = createSharpTestImage();
        byte[] image2 = createDifferentImage();
        MotionDetector.MotionResult result = processor.detectMotion(image1, image2, 0.15);
        assertTrue(result.motionScore() > 0.0,
                "Different images should have non-zero motion score, got: " + result.motionScore());
    }

    @Test
    void shouldReturnNoMotionForNullInputs() {
        MotionDetector.MotionResult result = processor.detectMotion(null, null, 0.15);
        assertFalse(result.motionDetected());
    }

    @Test
    void shouldCreateSharpnessMap() throws IOException {
        byte[] image = createSharpTestImage();
        SharpnessMap map = processor.createSharpnessMap(image, 32);
        assertNotNull(map);
        assertTrue(map.width() > 0, "Map should have positive width");
        assertTrue(map.height() > 0, "Map should have positive height");
        assertEquals(32, map.blockSize());
    }

    @Test
    void shouldDetectImageFileFormat() throws IOException {
        byte[] jpegImage = createTestImageAs("jpg");
        byte[] pngImage = createTestImageAs("png");
        byte[] cr3Header = createCr3HeaderBytes();

        assertTrue(processor.isImageFile(jpegImage), "Should detect JPEG");
        assertTrue(processor.isImageFile(pngImage), "Should detect PNG");
        assertTrue(processor.isImageFile(cr3Header), "Should detect CR3 signature");
        assertFalse(processor.isImageFile(new byte[]{0, 0, 0, 0}), "Should reject random bytes");
        assertFalse(processor.isImageFile(null), "Should reject null");
    }

    @Test
    void shouldGracefullyHandleInvalidCr3Payload() {
        byte[] invalidCr3 = createCr3HeaderBytes();
        assertEquals(0.0, processor.calculateSharpness(invalidCr3));
        MotionDetector.MotionResult result = processor.detectMotion(invalidCr3, invalidCr3, 0.1);
        assertNotNull(result);
        assertFalse(result.motionDetected());
    }

    @Test
    void shouldReportImageJVersion() {
        String version = processor.getVersion();
        assertNotNull(version);
        assertFalse(version.isEmpty(), "ImageJ version should not be empty");
    }

    // --- Test Image Generators ---

    private byte[] createSharpTestImage() throws IOException {
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g2d = img.createGraphics();
        // Create sharp edges: black/white checkerboard pattern
        for (int y = 0; y < 100; y += 10) {
            for (int x = 0; x < 100; x += 10) {
                g2d.setColor(((x + y) / 10 % 2 == 0) ? Color.BLACK : Color.WHITE);
                g2d.fillRect(x, y, 10, 10);
            }
        }
        g2d.dispose();
        return toPng(img);
    }

    private byte[] createUniformImage() throws IOException {
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g2d = img.createGraphics();
        g2d.setColor(Color.GRAY);
        g2d.fillRect(0, 0, 100, 100);
        g2d.dispose();
        return toPng(img);
    }

    private byte[] createDifferentImage() throws IOException {
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g2d = img.createGraphics();
        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, 100, 100);
        g2d.setColor(Color.BLACK);
        g2d.fillOval(20, 20, 60, 60);
        g2d.dispose();
        return toPng(img);
    }

    private byte[] createTestImageAs(String format) throws IOException {
        BufferedImage img = new BufferedImage(50, 50, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g2d = img.createGraphics();
        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, 50, 50);
        g2d.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, format, baos);
        return baos.toByteArray();
    }

    private byte[] toPng(BufferedImage img) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    private byte[] createCr3HeaderBytes() {
        byte[] bytes = new byte[16];
        bytes[0] = 0;
        bytes[1] = 0;
        bytes[2] = 0;
        bytes[3] = 16;
        bytes[4] = 'f';
        bytes[5] = 't';
        bytes[6] = 'y';
        bytes[7] = 'p';
        bytes[8] = 'c';
        bytes[9] = 'r';
        bytes[10] = 'x';
        bytes[11] = ' ';
        return bytes;
    }
}
