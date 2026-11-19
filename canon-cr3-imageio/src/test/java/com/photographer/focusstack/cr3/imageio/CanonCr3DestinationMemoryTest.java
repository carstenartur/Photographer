package com.photographer.focusstack.cr3.imageio;

import java.awt.image.DataBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javax.imageio.stream.FileImageInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonCr3DestinationMemoryTest {

    @Test
    void selectingTheDecodedTypeFitsWithoutAllocatingASecondFullImage(@TempDir Path temporary) throws Exception {
        Path sample = Path.of(getClass().getResource("/samples/sample.cr3").toURI());
        Path stdout = temporary.resolve("stdout.txt");
        Path stderr = temporary.resolve("stderr.txt");
        var process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx192m", "-XX:+UseG1GC", "-XX:ActiveProcessorCount=2", "-Djava.awt.headless=true",
                "-cp", System.getProperty("java.class.path"), MatchingDestinationProbe.class.getName(), sample.toString())
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
                types.next(); // Default RGB8.
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
