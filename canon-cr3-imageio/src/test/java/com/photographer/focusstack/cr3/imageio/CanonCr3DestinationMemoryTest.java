package com.photographer.focusstack.cr3.imageio;

import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.DataBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javax.imageio.stream.FileImageInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonCr3DestinationMemoryTest {

    @Test
    void selectingTheDecodedTypeFitsWithoutAllocatingASecondFullImage(@TempDir Path temporary) throws Exception {
        runProbe(temporary, MatchingDestinationProbe.class);
    }

    @Test
    void rotatedPartialCopyFitsWithOnlyTwoPackedImagesLive(@TempDir Path temporary) throws Exception {
        runProbe(temporary, RotatedPartialProbe.class);
    }

    private void runProbe(Path temporary, Class<?> probe) throws Exception {
        Path sample = Path.of(getClass().getResource("/samples/sample.cr3").toURI());
        Path stdout = temporary.resolve("stdout.txt");
        Path stderr = temporary.resolve("stderr.txt");
        var process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx192m", "-XX:+UseG1GC", "-XX:ActiveProcessorCount=2", "-Djava.awt.headless=true",
                "-cp", System.getProperty("java.class.path"), probe.getName(), sample.toString())
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        boolean exited;
        try {
            exited = process.waitFor(60, TimeUnit.SECONDS);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
        String diagnostic = Files.readString(stderr) + Files.readString(stdout);
        assertTrue(exited, () -> "Limited-heap destination read timed out:\n" + diagnostic);
        assertEquals(0, process.exitValue(), () -> "Limited-heap destination read failed:\n" + diagnostic);
    }

    public static final class RotatedPartialProbe {
        public static void main(String[] arguments) throws Exception {
            var reader = (CanonCr3ImageReader) new CanonCr3ImageReaderSpi().createReaderInstance();
            try (var input = new FileImageInputStream(Path.of(arguments[0]).toFile())) {
                reader.setInput(input);
                reader.getImageMetadata(0);
                reader.getDecoder().getMetadata().setOrientation(6);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                var region = new Rectangle(1, 1, width - 2, height - 2);
                assertTrue(12L * region.width * region.height > Runtime.getRuntime().maxMemory(),
                        "The child heap must exclude simultaneous halo, rotated output and destination");
                var param = reader.getDefaultReadParam();
                param.setSourceRegion(region);
                param.setDestinationOffset(new Point(1, 1));
                var options = param.getDecodeOptions().bitDepth(Cr3DecodeOptions.BitDepth.EIGHT)
                        .memoryMode(Cr3DecodeOptions.MemoryMode.LOW_MEMORY);
                var image = reader.read(0, param);
                assertEquals(width - 1, image.getWidth());
                assertEquals(height - 1, image.getHeight());
                assertEquals(0xff000000, image.getRGB(0, 0));
                var patch = reader.getDecoder().decodeRawProcessedRegion(options, new Rectangle(17, 23, 4, 3));
                assertArrayEquals(patch.getRaster().getPixels(0, 0, 4, 3, (int[]) null),
                        image.getRaster().getPixels(17, 23, 4, 3, (int[]) null));
            } finally {
                reader.dispose();
            }
        }
    }

    /** Separate heap: the 20-megapixel RGB16 image fits, but a second full copy does not. */
    public static final class MatchingDestinationProbe {
        public static void main(String[] arguments) throws Exception {
            var reader = new CanonCr3ImageReaderSpi().createReaderInstance();
            try (var input = new FileImageInputStream(Path.of(arguments[0]).toFile())) {
                reader.setInput(input);
                long imageBytes = 6L * reader.getWidth(0) * reader.getHeight(0);
                if (2 * imageBytes <= Runtime.getRuntime().maxMemory()) {
                    throw new AssertionError("The fixture must require more than half of the child heap");
                }
                var types = reader.getImageTypes(0);
                var requested = types.next(); // Advertised banded RGB16 sRGB.
                var param = reader.getDefaultReadParam();
                param.setDestinationType(requested);

                var image = reader.read(0, param);

                if (image.getWidth() != reader.getWidth(0) || image.getHeight() != reader.getHeight(0)
                        || image.getRaster().getTransferType() != DataBuffer.TYPE_USHORT
                        || image.getRaster().getNumBands() != 3
                        || !requested.getColorModel().equals(image.getColorModel())
                        || !requested.getSampleModel(image.getWidth(), image.getHeight()).equals(image.getSampleModel())) {
                    throw new AssertionError("The selected RGB16 destination type was not preserved");
                }
            } finally {
                reader.dispose();
            }
        }
    }
}
