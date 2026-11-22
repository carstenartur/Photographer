package com.photographer.focusstack.cr3.imageio;

import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.image.Raster;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Full native-sensor comparisons for CC0 camera recordings and a pinned black-box decoder. */
class Cr3CameraCoverageIT {
    private static final Path REGISTRIES = Path.of("..", "scripts", "research", "public-crx");

    @TestFactory
    @EnabledIfSystemProperty(named = "cr3.camera.samples.dir", matches = ".+")
    Stream<DynamicTest> publicCameraSamplesMatchEveryNativeValue() throws IOException {
        return comparison("v1-cameras.tsv", "cr3.camera.samples.dir");
    }

    @TestFactory
    @EnabledIfSystemProperty(named = "cr3.adaptive-craw.samples.dir", matches = ".+")
    Stream<DynamicTest> adaptiveCrawSamplesMatchEveryNativeValue() throws IOException {
        return comparison("adaptive-craw.tsv", "cr3.adaptive-craw.samples.dir");
    }

    private static Stream<DynamicTest> comparison(String registry, String cacheProperty) throws IOException {
        Path cache = Path.of(System.getProperty(cacheProperty));
        List<String> rows = Files.readAllLines(REGISTRIES.resolve(registry));
        assertTrue(rows.size() > 1, "Expected at least one pinned camera fixture");
        return rows.stream().skip(1).map(row -> {
            String[] fields = row.split("\t", -1);
            assertEquals(7, fields.length, row);
            return DynamicTest.dynamicTest(fields[0] + " " + fields[1] + " " + fields[2],
                    () -> compareSensor(cache, fields[0], fields[3], fields[4]));
        });
    }

    private static void compareSensor(Path cache, String id, String inputHash, String referenceHash) throws Exception {
        Path input = cache.resolve(id + ".cr3");
        assertEquals(inputHash, sha256(input), input.toString());

        try (var stream = ImageIO.createImageInputStream(input.toFile())) {
            var readers = ImageIO.getImageReaders(stream);
            assertTrue(readers.hasNext(), "No provider recognized " + input);
            var reader = assertInstanceOf(CanonCr3ImageReader.class, readers.next());
            try {
                reader.setInput(stream);
                var param = new CanonCr3ImageReadParam();
                param.setDecodeOptions(Cr3DecodeOptions.rawSensor().maxWorkers(1));
                Raster sensor = reader.readRaster(0, param);
                compareReference(cache.resolve(id + ".pgm"), referenceHash, sensor);

                // Top-left includes optical black. The bottom-right region exercises both outer boundaries.
                int w = Math.min(32, sensor.getWidth()), h = Math.min(32, sensor.getHeight());
                for (Rectangle region : List.of(new Rectangle(0, 0, w, h),
                        new Rectangle(sensor.getWidth() - w, sensor.getHeight() - h, w, h))) {
                    param.setSourceRegion(region);
                    Raster portion = reader.readRaster(0, param);
                    assertEquals(region.width, portion.getWidth());
                    assertEquals(region.height, portion.getHeight());
                    assertArrayEquals(sensor.getSamples(region.x, region.y, w, h, 0, (int[]) null),
                            portion.getSamples(0, 0, w, h, 0, (int[]) null), region.toString());
                }
            } finally {
                reader.dispose();
            }
        }
    }

    private static void compareReference(Path pgm, String expectedHash, Raster sensor) throws Exception {
        int width = sensor.getWidth(), height = sensor.getHeight();
        assertEquals(1, sensor.getNumBands());
        assertEquals(16, sensor.getSampleModel().getSampleSize(0));
        byte[] header = ("P5 %d %d 65535\n".formatted(width, height)).getBytes(US_ASCII);
        var digest = MessageDigest.getInstance("SHA-256");
        try (var stream = new DigestInputStream(Files.newInputStream(pgm), digest);
             var reference = new DataInputStream(new BufferedInputStream(stream))) {
            assertArrayEquals(header, reference.readNBytes(header.length));
            byte[] expected = new byte[width * Short.BYTES];
            int[] row = new int[width];
            for (int y = 0; y < height; y++) {
                reference.readFully(expected);
                sensor.getSamples(0, y, width, 1, 0, row);
                for (int x = 0; x < width; x++) {
                    int value = (Byte.toUnsignedInt(expected[2 * x]) << 8) | Byte.toUnsignedInt(expected[2 * x + 1]);
                    if (row[x] != value) {
                        assertEquals(value, row[x], "native sensor site (%d,%d) of %s".formatted(x, y, pgm));
                    }
                }
            }
            assertEquals(-1, reference.read(), "Trailing PGM data");
        }
        assertEquals(expectedHash, HexFormat.of().formatHex(digest.digest()), pgm.toString());
    }

    private static String sha256(Path path) throws NoSuchAlgorithmException, IOException {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var contents = new DigestInputStream(Files.newInputStream(path), digest)) {
            contents.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
