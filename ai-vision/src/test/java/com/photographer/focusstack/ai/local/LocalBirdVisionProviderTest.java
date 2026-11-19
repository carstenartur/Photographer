package com.photographer.focusstack.ai.local;

import com.photographer.focusstack.ai.VisionContext;
import com.photographer.focusstack.ai.VisionFailureKind;
import com.photographer.focusstack.observer.events.BoundingBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalBirdVisionProviderTest {

    private static final VisionContext CONTEXT = VisionContext.wildlife();

    @TempDir
    Path temporaryDirectory;

    @Test
    void detectsBirdAndMapsBoxToOriginalNonSquarePhoto() throws Exception {
        try (var provider = new LocalBirdVisionProvider()) {
            var result = provider.detectObjects(photo(), CONTEXT);

            assertTrue(result.successful(), result.errorMessage());
            assertEquals("local-bird", result.provider());
            assertEquals("yolox-nano-0.1.1rc0", result.model());
            assertEquals("object_detection", result.taskType());
            assertEquals("bird", result.primaryLabel());
            assertTrue(result.confidence() > 0.75 && result.confidence() < 0.99);
            BoundingBox bird = result.boundingBoxes().getFirst();
            assertEquals("bird", bird.label());
            assertEquals(0.32, bird.x(), 0.04);
            assertEquals(0.16, bird.y(), 0.04);
            assertEquals(0.63, bird.width(), 0.06);
            assertEquals(0.83, bird.height(), 0.07);
        }
    }

    @Test
    void returnsSuccessfulEmptyDetectionForUniformImage() throws Exception {
        try (var provider = new LocalBirdVisionProvider()) {
            var result = provider.detectObjects(blankPng(), CONTEXT);

            assertTrue(result.successful(), result.errorMessage());
            assertTrue(result.boundingBoxes().isEmpty());
            assertEquals(0.0, result.confidence());
        }
    }

    @Test
    void rejectsMissingCorruptAndOversizedImageInputs() throws Exception {
        try (var provider = new LocalBirdVisionProvider()) {
            for (byte[] input : new byte[][]{null, {}, {1, 2, 3}, new byte[16 * 1024 * 1024 + 1]}) {
                assertEquals(VisionFailureKind.INVALID_REQUEST,
                        provider.detectObjects(input, CONTEXT).failureKind());
            }
        }
    }

    @Test
    void rejectsTruncatedJpegRatherThanUsingPartiallyDecodedPixels() throws Exception {
        try (var provider = new LocalBirdVisionProvider()) {
            byte[] image = photo();
            var result = provider.detectObjects(Arrays.copyOf(image, image.length / 2), CONTEXT);

            assertEquals(VisionFailureKind.INVALID_REQUEST, result.failureKind());
        }
    }

    @Test
    void rejectsOversizedDimensionsBeforeDecodingPixels() throws Exception {
        try (var provider = new LocalBirdVisionProvider()) {
            var result = provider.detectObjects(oversizedPngHeader(), CONTEXT);

            assertEquals(VisionFailureKind.INVALID_REQUEST, result.failureKind());
            assertTrue(result.errorMessage().contains("dimensions"), result.errorMessage());
        }
    }

    @Test
    void reportsMissingCorruptAndIncompatibleModelsAsFailures() throws Exception {
        byte[] image = photo();
        Path missing = temporaryDirectory.resolve("missing.onnx");
        Path corrupt = Files.write(temporaryDirectory.resolve("corrupt.onnx"), new byte[]{1, 2, 3});
        // Valid ONNX Identity graph returning [1,3,416,416] instead of YOLOX output.
        Path wrongShape = Files.write(temporaryDirectory.resolve("wrong-shape.onnx"), Base64.getDecoder().decode(
                "CAY6cQoaCgZpbWFnZXMSBm91dHB1dCIISWRlbnRpdHkSC3dyb25nX3NoYXBlWiIKBmltYWdlcxIYChYIARISCgIIAQoCCAMKAwigAwoDCKADYiIKBm91dHB1dBIYChYIARISCgIIAQoCCAMKAwigAwoDCKADQgIQCw=="));

        assertModelFailure(missing, image, VisionFailureKind.NOT_CONFIGURED);
        assertModelFailure(corrupt, image, VisionFailureKind.PERMANENT_PROVIDER_ERROR);
        assertModelFailure(wrongShape, image, VisionFailureKind.PERMANENT_PROVIDER_ERROR);
    }

    @Test
    void reusesOneSessionForConcurrentCallsAndRejectsUseAfterClose() throws Exception {
        Path model = temporaryDirectory.resolve("override.onnx");
        try (var bundled = getClass().getResourceAsStream("yolox_nano.onnx")) {
            Files.copy(Objects.requireNonNull(bundled), model);
        }
        byte[] image = photo();
        byte[] blank = blankPng();
        var provider = new LocalBirdVisionProvider(model);
        try (provider; var callers = Executors.newFixedThreadPool(2)) {
            var first = callers.submit(() -> provider.detectObjects(image, CONTEXT));
            var second = callers.submit(() -> provider.detectObjects(blank, CONTEXT));

            var firstResult = first.get(10, TimeUnit.SECONDS);
            var secondResult = second.get(10, TimeUnit.SECONDS);
            assertTrue(firstResult.successful(), firstResult.errorMessage());
            assertTrue(secondResult.successful(), secondResult.errorMessage());
            assertFalse(firstResult.boundingBoxes().isEmpty());
            assertTrue(secondResult.boundingBoxes().isEmpty());
            assertTrue(provider.isAvailable());
            assertNull(provider.availabilityError());

            Files.delete(model);
            var repeatedResult = provider.detectObjects(image, CONTEXT);
            assertTrue(repeatedResult.successful(), repeatedResult.errorMessage());
            assertEquals(firstResult.boundingBoxes(), repeatedResult.boundingBoxes());
        }

        provider.close();
        assertFalse(provider.isAvailable());
        var afterClose = provider.detectObjects(image, CONTEXT);
        assertEquals(VisionFailureKind.PERMANENT_PROVIDER_ERROR, afterClose.failureKind());
        assertEquals(afterClose.errorMessage(), provider.availabilityError());
    }

    private void assertModelFailure(Path model, byte[] image, VisionFailureKind kind) throws Exception {
        try (var provider = new LocalBirdVisionProvider(model)) {
            assertFalse(provider.isAvailable());
            var result = provider.detectObjects(image, CONTEXT);
            assertFalse(result.successful());
            assertEquals(kind, result.failureKind(), result.errorMessage());
            assertEquals(result.errorMessage(), provider.availabilityError());
        }
    }

    private byte[] photo() throws IOException {
        try (var fixture = getClass().getResourceAsStream("sparrow-amrum.jpg")) {
            return Objects.requireNonNull(fixture).readAllBytes();
        }
    }

    private static byte[] blankPng() throws IOException {
        var encoded = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB), "png", encoded);
        return encoded.toByteArray();
    }

    private static byte[] oversizedPngHeader() throws IOException {
        byte[] ihdr = {73, 72, 68, 82, 0, 0, 32, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0};
        CRC32 checksum = new CRC32();
        checksum.update(ihdr);
        var encoded = new ByteArrayOutputStream();
        try (var stream = new DataOutputStream(encoded)) {
            stream.writeLong(0x89504e470d0a1a0aL);
            stream.writeInt(13);
            stream.write(ihdr);
            stream.writeInt((int) checksum.getValue());
        }
        return encoded.toByteArray();
    }
}
