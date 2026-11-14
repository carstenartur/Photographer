package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.Cr3Exception;
import com.photographer.focusstack.cr3.container.Cr3Track;
import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.decode.Cr3Decoder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Whole-sensor acceptance test for the public Java decoder on pinned original/reference pairs. */
@Tag("external-reference")
@EnabledIfSystemProperty(named = "cr3.full-sensor.mode", matches = "report|strict")
class FullSensorDecodeIT {
    record Fixture(String name, int width, int height, int levels, String inputHash, String referenceHash,
                   String referenceSuffix) {}

    static final List<Fixture> CRAW = List.of(
            new Fixture("eos-r", 6888, 4546, 3,
                    "1b3efe0664a28a0decb53e225cbfecdbc0dd27d85abdc4683fc2a4add5129b4d",
                    "6c3f4866c2f20a001ff9aad01a28900858ff998b4ffed7301b02373441f32b13", ".sample"),
            new Fixture("g5xm2", 5632, 3710, 3,
                    "16d7b944344004c866bf2f84c5673969e76adfe16e5fb4388da48c72bdd58058",
                    "76cc5944a3141f682c6940bae1cbf7726a20525e3cbbe6deb7e8347702720c68", ".sample"));

    enum Outcome { EXACT, PIXEL_MISMATCH, UNSUPPORTED, DECODE_ERROR }

    record Result(Fixture fixture, Outcome outcome, int compared, int mismatches,
                  String first, int maxError, String detail) {
        String row() {
            return String.join("\t", fixture.name, String.valueOf(fixture.width), String.valueOf(fixture.height),
                    "14", "4", String.valueOf(fixture.levels), outcome.name(), String.valueOf(compared),
                    compared == 0 ? "-" : String.valueOf(mismatches), first,
                    compared == 0 ? "-" : String.valueOf(maxError), detail);
        }

        static Result unavailable(Fixture fixture, Outcome outcome, String detail) {
            return new Result(fixture, outcome, 0, 0, "-", 0, detail.replaceAll("[\\r\\n\\t]", " "));
        }
    }

    @Test
    void compareEveryNativeSampleThroughPublicDecoder() throws Exception {
        List<Result> results = new ArrayList<>();
        Path samples = Path.of(System.getProperty("cr3.samples.dir", "src/test/resources/samples"));
        var inputs = LosslessOpeningSamplesIT.registeredPanelHashes();
        var entries = LibRawGuidedFirstDivergenceIT.parseRawLinearManifestEntries();
        assertEquals(KAdaptationFixtureSet.PANEL, entries.stream()
                .map(LibRawGuidedFirstDivergenceIT.ManifestEntry::fixture).toList());
        for (var entry : entries) {
            String name = entry.fixture().replaceFirst("\\.cr3$", "");
            var fixture = new Fixture(name, entry.width(), entry.height(), 0,
                    inputs.get(entry.fixture()), entry.sha256(), ".reference.tiff");
            results.add(inspect(fixture, samples));
        }

        String cache = System.getProperty("cr3.opening.holdout.dir");
        assertNotNull(cache, "Set cr3.opening.holdout.dir to the directory prepared by prepare-lossless-holdout.sh");
        for (var fixture : holdouts()) {
            results.add(inspect(fixture, Path.of(cache)));
        }

        boolean requireCraw = Boolean.getBoolean("cr3.full-sensor.require-craw");
        if (requireCraw || System.getProperty("cr3.public.craw.dir") != null) {
            results.addAll(inspectCraw());
        }
        Path output = writeReport("full-sensor-decode", results);
        boolean strict = "strict".equals(System.getProperty("cr3.full-sensor.mode"));
        for (Result result : results) {
            boolean exactRequired = (strict && result.fixture.levels == 0) || requireCraw && result.fixture.levels > 0;
            boolean accepted = result.outcome == Outcome.EXACT || (!exactRequired
                    && (result.outcome == Outcome.UNSUPPORTED || (!strict && result.outcome == Outcome.PIXEL_MISMATCH)));
            assertTrue(accepted, () -> result.row() + "\nAll fixture results: " + output);
        }
    }

    private static List<Fixture> holdouts() throws IOException {
        Path root = Path.of(System.getProperty("maven.multiModuleProjectDirectory", ".."));
        var registry = Files.readAllLines(root.resolve("scripts/research/public-crx/lossless-holdout.tsv"));
        List<Fixture> fixtures = new ArrayList<>();
        for (String row : registry.subList(1, registry.size())) {
            String[] fields = row.split("\t", -1);
            assertEquals(7, fields.length);
            int[] size = switch (fields[0]) {
                case "3510" -> new int[] {5632, 3710};
                case "4515" -> new int[] {6888, 4546};
                case "4657" -> new int[] {6288, 4056};
                case "4674" -> new int[] {6384, 4224};
                default -> throw new IllegalArgumentException("Unregistered camera file " + fields[0]);
            };
            fixtures.add(new Fixture(fields[0], size[0], size[1], 0, fields[3], fields[4], ".pgm"));
        }
        assertEquals(List.of("3510", "4515", "4657", "4674"), fixtures.stream().map(Fixture::name).toList());
        return fixtures;
    }

    static List<Result> inspectCraw() throws Exception {
        String cache = System.getProperty("cr3.public.craw.dir");
        assertNotNull(cache, "Set cr3.public.craw.dir to the directory prepared by verify-public-crx.sh");
        List<Result> results = new ArrayList<>();
        for (var fixture : CRAW) {
            results.add(inspect(fixture, Path.of(cache)));
        }
        return results;
    }

    static Path writeReport(String name, List<Result> results) throws IOException {
        Path output = Path.of(System.getProperty("cr3.research.output.dir", "target"), name, "results.tsv");
        Files.createDirectories(output.getParent());
        var rows = new ArrayList<>(List.of("fixture\twidth\theight\tbits\tplanes\tlevels\tstatus\tcompared"
                + "\tmismatches\tfirst(x,y,reference,photographer)\tmaxAbsDifference\tdetail"));
        results.stream().map(Result::row).forEach(rows::add);
        Files.writeString(output, String.join("\n", rows) + "\n");
        return output;
    }

    private static Result inspect(Fixture fixture, Path directory) throws Exception {
        Path cr3 = directory.resolve(fixture.name + ".cr3");
        Path reference = directory.resolve(fixture.name + fixture.referenceSuffix);
        assertEquals(fixture.inputHash, LosslessFirstBitControlIT.sha256(cr3), cr3.toString());
        assertEquals(fixture.referenceHash, LosslessFirstBitControlIT.sha256(reference), reference.toString());
        BufferedImage sensor;
        try (Cr3Decoder decoder = new Cr3Decoder()) {
            decoder.setInput(cr3);
            var cmp = decoder.getContainer().tracks().stream()
                    .filter(track -> track.type() == Cr3Track.TrackType.CRX_BIG
                            || track.type() == Cr3Track.TrackType.CRX_RAW)
                    .findFirst().orElseThrow().cmp1();
            assertEquals(fixture.width, cmp.imageWidth());
            assertEquals(fixture.height, cmp.imageHeight());
            assertEquals(14, cmp.bitsPerSample());
            assertEquals(4, cmp.numPlanes());
            assertEquals(fixture.levels, cmp.waveletLevels());
            sensor = decoder.decode(Cr3DecodeOptions.rawLinear().bitDepth(Cr3DecodeOptions.BitDepth.SIXTEEN)
                    .cropToActiveArea(false).applyBlackLevel(false));
        } catch (Cr3Exception | IOException | RuntimeException e) {
            String message = String.valueOf(e.getMessage());
            Outcome outcome = fixture.levels > 0
                    && message.startsWith("C-RAW (lossy) decoding is not yet fully implemented.")
                    ? Outcome.UNSUPPORTED : Outcome.DECODE_ERROR;
            return Result.unavailable(fixture, outcome, message);
        }
        if (sensor == null || sensor.getWidth() != fixture.width || sensor.getHeight() != fixture.height
                || sensor.getType() != BufferedImage.TYPE_USHORT_GRAY) {
            return Result.unavailable(fixture, Outcome.DECODE_ERROR, "Expected full-size, unsigned 16-bit Bayer");
        }
        if (fixture.referenceSuffix.endsWith(".tiff")) {
            var reader = ImageIO.getImageReadersByFormatName("TIFF").next();
            try (var stream = ImageIO.createImageInputStream(reference.toFile())) {
                reader.setInput(stream);
                assertEquals(fixture.width, reader.getWidth(0));
                assertEquals(fixture.height, reader.getHeight(0));
                // Read complete TIFF strips in groups, bounding memory independently of sensor height.
                int blockHeight = Math.max(1, reader.getTileHeight(0));
                int rowsPerBlock = blockHeight * Math.max(1, 128 / blockHeight);
                var param = reader.getDefaultReadParam();
                return compare(fixture, sensor, new ReferenceRow() {
                    private Raster block;

                    @Override
                    public void read(int y, int[] samples) throws IOException {
                        int localY = y % rowsPerBlock;
                        if (localY == 0) {
                            int height = Math.min(rowsPerBlock, fixture.height - y);
                            param.setSourceRegion(new Rectangle(0, y, fixture.width, height));
                            block = reader.read(0, param).getRaster();
                            assertEquals(1, block.getNumBands());
                        }
                        block.getSamples(0, localY, fixture.width, 1, 0, samples);
                    }
                });
            } finally {
                reader.dispose();
            }
        }
        try (var pgm = new DataInputStream(new BufferedInputStream(Files.newInputStream(reference)))) {
            byte[] header = ("P5 %d %d 65535\n".formatted(fixture.width, fixture.height))
                    .getBytes(StandardCharsets.US_ASCII);
            assertArrayEquals(header, pgm.readNBytes(header.length));
            Result result = compare(fixture, sensor, (y, row) -> {
                for (int x = 0; x < row.length; x++) {
                    row[x] = pgm.readUnsignedShort();
                }
            });
            assertEquals(-1, pgm.read());
            return result;
        }
    }

    @FunctionalInterface
    private interface ReferenceRow {
        void read(int y, int[] samples) throws IOException;
    }

    private static Result compare(Fixture fixture, BufferedImage sensor, ReferenceRow reference) throws IOException {
        int mismatches = 0, maximumError = 0;
        String first = "-";
        int[] expected = new int[fixture.width], actual = new int[fixture.width];
        int nativeMax = (1 << 14) - 1;
        for (int y = 0; y < fixture.height; y++) {
            reference.read(y, expected);
            sensor.getRaster().getSamples(0, y, fixture.width, 1, 0, actual);
            for (int x = 0; x < fixture.width; x++) {
                // RAW_LINEAR represents native values as floor(native * 65535 / nativeMax).
                int scaled = actual[x];
                int value = (int) Math.ceilDiv((long) scaled * nativeMax, 65535);
                if ((long) value * 65535 / nativeMax != scaled) {
                    return Result.unavailable(fixture, Outcome.DECODE_ERROR,
                            "Invalid 14-bit output representation at %d,%d: %d".formatted(x, y, scaled));
                }
                if (expected[x] != value) {
                    if (mismatches == 0) {
                        first = "%d,%d,%d,%d".formatted(x, y, expected[x], value);
                    }
                    mismatches++;
                    maximumError = Math.max(maximumError, Math.abs(expected[x] - value));
                }
            }
        }
        return new Result(fixture, mismatches == 0 ? Outcome.EXACT : Outcome.PIXEL_MISMATCH,
                fixture.width * fixture.height, mismatches, first, maximumError, "-");
    }
}
