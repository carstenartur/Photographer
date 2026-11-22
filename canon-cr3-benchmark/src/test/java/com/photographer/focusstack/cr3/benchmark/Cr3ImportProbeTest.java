package com.photographer.focusstack.cr3.benchmark;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class Cr3ImportProbeTest {
    @TempDir Path directory;

    @Test
    void defaultModeReportsAutomaticMemoryPolicyAndCameraOrientation() throws Exception {
        var result = run("--input", fixture().toString());

        assertTrue(result.output().contains("\"requested_memory_mode\":\"AUTO\""), result.output());
        assertTrue(result.output().contains("\"orientation_override\":0"), result.output());
        assertTrue(result.output().contains("\"channels\":3,\"bits\":8"), result.output());
    }

    @ParameterizedTest
    @CsvSource({"auto,AUTO", "lOw_MeMoRy,LOW_MEMORY", "fAsT,FAST"})
    void recordsAndAppliesCaseInsensitiveMemoryModes(String requested, String normalized) throws Exception {
        var result = assertDoesNotThrow(() -> run("--input", fixture().toString(), "--mode", "rgb16linear",
                "--memory-mode", requested, "--orientation", "6"));

        assertTrue(result.output().contains("\"requested_memory_mode\":\"" + normalized + "\""), result.output());
        assertTrue(result.output().contains("\"orientation_override\":6"), result.output());
        assertTrue(result.output().contains("\"channels\":3,\"bits\":16"), result.output());
        assertEquals(normalized.equals("LOW_MEMORY"), result.error().contains("CR3 low-memory: requested"),
                result.error());
    }

    @ParameterizedTest
    @CsvSource({"rgb8,3,8", "rgb16,3,16", "rgb16linear,3,16", "sensor,1,16", "sensor-path,1,16"})
    void forcedMemoryPolicyKeepsTheRequestedPrecision(String mode, int channels, int bits) throws Exception {
        var result = assertDoesNotThrow(() -> run("--input", fixture().toString(), "--mode", mode,
                "--memory-mode", "LOW_MEMORY"));

        assertTrue(result.output().contains("\"channels\":" + channels + ",\"bits\":" + bits), result.output());
        assertTrue(result.output().contains("\"orientation_override\":0"), result.output());
        assertTrue(result.error().contains("CR3 low-memory: requested"), result.error());
    }

    @Test
    void orientationOverrideRotatesEveryFullPrecisionPixel() throws Exception {
        Path source = fixture();
        Path normal = directory.resolve("normal.ppm");
        Path rotated = directory.resolve("rotated.ppm");
        assertDoesNotThrow(() -> run("--input", source.toString(), "--mode", "rgb16linear",
                "--memory-mode", "FAST", "--orientation", "1", "--dump", normal.toString()));
        assertDoesNotThrow(() -> run("--input", source.toString(), "--mode", "rgb16linear",
                "--memory-mode", "LOW_MEMORY", "--orientation", "6", "--dump", rotated.toString()));
        byte[] pixels = pixels(normal);
        var expected = new ByteArrayOutputStream();
        // Clockwise rotation of a b / c d is c a / d b; each RGB16 pixel has six bytes.
        expected.write(pixels, 12, 6);
        expected.write(pixels, 0, 6);
        expected.write(pixels, 18, 6);
        expected.write(pixels, 6, 6);

        assertFalse(Arrays.equals(pixels, expected.toByteArray()), "The fixture must distinguish orientation");
        assertArrayEquals(expected.toByteArray(), pixels(rotated));
    }

    @ParameterizedTest
    @CsvSource({"--memory-mode,FAST", "--memory-mode,LOW_MEMORY", "--orientation,1", "--workers,1",
            "--subsample-x,2"})
    void defaultModeRejectsOptionsThatImageIoReadWouldIgnore(String option, String value) {
        var failure = assertThrows(IllegalArgumentException.class, () -> Cr3ImportProbe.main(new String[]{
                "--input", directory.resolve("missing.cr3").toString(), "--mode", "default", option, value}));
        assertTrue(failure.getMessage().contains("default"), failure.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"sensor", "sensor-path"})
    void sensorModesRejectOrientationOverrides(String mode) {
        var failure = assertThrows(IllegalArgumentException.class, () -> Cr3ImportProbe.main(new String[]{
                "--input", directory.resolve("missing.cr3").toString(), "--mode", mode, "--orientation", "1"}));
        assertTrue(failure.getMessage().contains(mode), failure.getMessage());
    }

    @ParameterizedTest
    @CsvSource({"--memory-mode,compact", "--orientation,-1", "--orientation,0", "--orientation,9",
            "--orientation,one", "--subsample-x,0", "--subsample-y,one"})
    void invalidOptionsFailBeforeOpeningTheInput(String option, String value) {
        var failure = assertThrows(IllegalArgumentException.class, () -> Cr3ImportProbe.main(new String[]{
                "--input", directory.resolve("missing.cr3").toString(), "--mode", "rgb16", option, value}));
        assertTrue(failure.getMessage().contains(option), failure.getMessage());
    }

    @Test
    void readsTheRequestedRgb16Subsampling() throws Exception {
        var result = run("--input", fixture().toString(), "--mode", "rgb16",
                "--subsample-x", "2", "--subsample-y", "1");

        assertTrue(result.output().contains("\"subsample_x\":2,\"subsample_y\":1"), result.output());
        assertTrue(result.output().contains("\"width\":1,\"height\":2,\"channels\":3,\"bits\":16"),
                result.output());
    }

    @Test
    void legacyReadOverloadKeepsImageIoDefaultsAndNativeSensorCodes() throws Exception {
        Path png = directory.resolve("pixel.png");
        var pixel = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        pixel.setRGB(0, 0, 0x123456);
        assertTrue(ImageIO.write(pixel, "png", png.toFile()));

        assertEquals(0xff123456, Cr3ImportProbe.read(png, "default", 0).getRGB(0, 0));
        var sensor = Cr3ImportProbe.read(fixture(), "sensor", 1);
        assertEquals(16, sensor.getSampleModel().getSampleSize(0));
        assertArrayEquals(new int[]{8192, 8193, 8191, 8194},
                sensor.getRaster().getPixels(0, 0, 2, 2, (int[]) null));
        assertThrows(IllegalArgumentException.class,
                () -> Cr3ImportProbe.read(png, "default", 1));
    }

    @Test
    void malformedRecognizedCr3ReportsAnIoErrorWhenOrientationIsRequested() throws Exception {
        Path broken = directory.resolve("truncated.cr3");
        // Keep the complete CR3 file-type box and the next box's header, but not its contents.
        Files.write(broken, Arrays.copyOf(Files.readAllBytes(fixture()), 28));
        assertThrows(IOException.class, () -> run("--input", broken.toString(), "--mode", "rgb16",
                "--orientation", "6"));
    }

    @Test
    void explicitPolicyStillEnforcesThePhotographerProvider() throws Exception {
        Path png = directory.resolve("pixel.png");
        assertTrue(ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", png.toFile()));
        var failure = assertThrows(IOException.class, () -> Cr3ImportProbe.main(new String[]{
                "--input", png.toString(), "--mode", "rgb16", "--memory-mode", "FAST"}));
        assertTrue(failure.getMessage().contains("Expected Photographer CanonCr3ImageReader"), failure.getMessage());
    }

    private Path fixture() throws IOException {
        Path path = directory.resolve("four-sites.cr3");
        if (!Files.exists(path)) {
            try (var input = getClass().getResourceAsStream("/four-sites.cr3")) {
                assertNotNull(input);
                Files.copy(input, path);
            }
        }
        return path;
    }

    private static byte[] pixels(Path pnm) throws IOException {
        byte[] bytes = Files.readAllBytes(pnm);
        byte[] header = "P6\n2 2\n65535\n".getBytes(StandardCharsets.US_ASCII);
        assertArrayEquals(header, Arrays.copyOf(bytes, header.length));
        assertEquals(header.length + 24, bytes.length);
        return Arrays.copyOfRange(bytes, header.length, bytes.length);
    }

    private static Captured run(String... arguments) throws Exception {
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();
        PrintStream previousOutput = System.out;
        PrintStream previousError = System.err;
        try (var stdout = new PrintStream(output, true, StandardCharsets.UTF_8);
             var stderr = new PrintStream(error, true, StandardCharsets.UTF_8)) {
            System.setOut(stdout);
            System.setErr(stderr);
            Cr3ImportProbe.main(arguments);
        } finally {
            System.setOut(previousOutput);
            System.setErr(previousError);
        }
        return new Captured(output.toString(StandardCharsets.UTF_8), error.toString(StandardCharsets.UTF_8));
    }

    private record Captured(String output, String error) {}
}
