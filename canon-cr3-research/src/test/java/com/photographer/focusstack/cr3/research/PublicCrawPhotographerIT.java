package com.photographer.focusstack.cr3.research;

import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

import com.photographer.focusstack.cr3.Cr3Exception;
import com.photographer.focusstack.cr3.container.Cr3Track;
import com.photographer.focusstack.cr3.decode.Cr3DecodeOptions;
import com.photographer.focusstack.cr3.decode.Cr3Decoder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Compare the public C-RAW files via Photographer's explicit, uncropped RAW entry point. */
@Tag("external-reference")
@EnabledIfSystemProperty(named = "cr3.public.craw.enabled", matches = "true")
class PublicCrawPhotographerIT {
    private record Fixture(String name, int width, int height, String inputHash, String pgmHash) {
    }

    private static final Fixture[] FIXTURES = {
        new Fixture("eos-r", 6888, 4546,
                "1b3efe0664a28a0decb53e225cbfecdbc0dd27d85abdc4683fc2a4add5129b4d",
                "6c3f4866c2f20a001ff9aad01a28900858ff998b4ffed7301b02373441f32b13"),
        new Fixture("g5xm2", 5632, 3710,
                "16d7b944344004c866bf2f84c5673969e76adfe16e5fb4388da48c72bdd58058",
                "76cc5944a3141f682c6940bae1cbf7726a20525e3cbbe6deb7e8347702720c68")
    };

    @Test
    void compareFullSensorValues() throws Exception {
        Path directory = Path.of(System.getProperty("cr3.public.craw.dir"));
        StringBuilder result = new StringBuilder(
                "fixture\twidth\theight\tbits\tplanes\tlevels\tstatus\tcompared\tmismatches\tfirst(x,y,reference,photographer)\tmaxAbsDifference\tdetail\n");
        for (Fixture fixture : FIXTURES) {
            Path cr3 = directory.resolve(fixture.name + ".cr3");
            Path reference = directory.resolve(fixture.name + ".sample");
            assertEquals(fixture.inputHash, fileHash(cr3));
            assertEquals(fixture.pgmHash, fileHash(reference));
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
                assertEquals(3, cmp.waveletLevels());
                result.append("%s\t%d\t%d\t%d\t%d\t%d\t".formatted(
                        fixture.name, cmp.imageWidth(), cmp.imageHeight(), cmp.bitsPerSample(),
                        cmp.numPlanes(), cmp.waveletLevels()));
                BufferedImage sensor;
                try {
                    sensor = decoder.decode(Cr3DecodeOptions.rawLinear()
                            .bitDepth(Cr3DecodeOptions.BitDepth.SIXTEEN)
                            .cropToActiveArea(false).applyBlackLevel(false));
                } catch (Cr3Exception e) {
                    // An unrelated failure must not masquerade as the known C-RAW limitation.
                    if (!e.getMessage().startsWith("C-RAW (lossy) decoding is not yet fully implemented.")) {
                        throw e;
                    }
                    result.append("UNSUPPORTED\t0\t-\t-\t-\t").append(e.getMessage()).append('\n');
                    continue;
                }
                compare(reference, fixture, sensor, cmp.bitsPerSample(), result);
            }
        }
        Path output = Path.of(System.getProperty("cr3.research.output.dir", "target"))
                .resolve("public-craw-photographer/results.tsv");
        Files.createDirectories(output.getParent());
        Files.writeString(output, result);
    }

    private static void compare(Path reference, Fixture fixture, BufferedImage image, int bits,
                                StringBuilder result) throws Exception {
        assertEquals(fixture.width, image.getWidth());
        assertEquals(fixture.height, image.getHeight());
        assertEquals(BufferedImage.TYPE_USHORT_GRAY, image.getType());
        int max = (1 << bits) - 1;
        long mismatches = 0;
        int maximumError = 0;
        String first = "-";
        try (var pgm = new DataInputStream(new BufferedInputStream(Files.newInputStream(reference)))) {
            byte[] header = ("P5 %d %d 65535\n".formatted(fixture.width, fixture.height))
                    .getBytes(StandardCharsets.US_ASCII);
            assertArrayEquals(header, pgm.readNBytes(header.length));
            for (int y = 0; y < fixture.height; y++) {
                for (int x = 0; x < fixture.width; x++) {
                    int expected = pgm.readUnsignedShort();
                    int scaled = image.getRaster().getSample(x, y, 0);
                    // RAW_LINEAR encodes floor(native * 65535 / max); invert that bijection.
                    int nativeValue = (int) Math.ceilDiv((long) scaled * max, 65535);
                    if ((long) nativeValue * 65535 / max != scaled) {
                        throw new IllegalStateException("Non-native scaled sample at " + x + "," + y);
                    }
                    if (expected != nativeValue) {
                        if (mismatches == 0) {
                            first = "%d,%d,%d,%d".formatted(x, y, expected, nativeValue);
                        }
                        mismatches++;
                        maximumError = Math.max(maximumError, Math.abs(expected - nativeValue));
                    }
                }
            }
            assertEquals(-1, pgm.read());
        }
        result.append("COMPARED\t%d\t%d\t%s\t%d\t-\n".formatted(
                (long) fixture.width * fixture.height, mismatches, first, maximumError));
    }

    private static String fileHash(Path file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
