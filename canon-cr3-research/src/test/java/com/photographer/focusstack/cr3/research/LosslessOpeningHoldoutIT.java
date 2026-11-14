package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.raw.codec.SubbandExtractor;
import com.photographer.focusstack.cr3.raw.codec.SubbandExtractor.SubbandRef;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** RG3: compare the frozen candidate to the native, uncropped sensor PGM for each held-out file. */
@Tag("external-reference")
@EnabledIfSystemProperty(named = "cr3.opening.holdout.enabled", matches = "true")
class LosslessOpeningHoldoutIT {
    private static final String COLUMNS = "id\tmodel\tgroup\tinputSha256\tpgmSha256\ttile\tplane\twidth\theight"
            + "\tfullX\tfullY\tfirstReference\tfirstPredicted\tfirstOutcome\tsecondReference\tsecondPredicted"
            + "\tsecondOutcome\tsecondBranch\tq\tprefix18\tfirstCode\tdetail";

    private record Sample(String id, String model, String group, String inputHash, String pgmHash) {
        static Sample fromRegistry(String line) {
            String[] fields = line.split("\t", -1);
            assertThat(fields).hasSize(7);
            assertThat(fields[5]).isEqualTo("https://creativecommons.org/publicdomain/zero/1.0/");
            return new Sample(fields[0], fields[1], fields[2], fields[3], fields[4]);
        }
    }

    private record Measurement(String tsv, boolean matches) {}

    @Test
    void checkEveryPlaneOnAllFrozenFiles() throws Exception {
        String dir = System.getProperty("cr3.opening.holdout.dir");
        assertThat(dir).as("directory prepared by prepare-lossless-holdout.sh").isNotBlank();
        Path root = Path.of(System.getProperty("maven.multiModuleProjectDirectory", ".."));
        Path registry = root.resolve("scripts/research/public-crx/lossless-holdout.tsv");
        var samples = Files.readAllLines(registry).stream().skip(1).map(Sample::fromRegistry).toList();
        assertThat(samples).extracting(Sample::id).containsExactly("3510", "4515", "4657", "4674");
        var measurements = new ArrayList<Measurement>();
        for (var sample : samples) {
            inspect(sample, Path.of(dir), measurements);
        }
        Path out = Path.of(System.getProperty("cr3.research.output.dir", "target"), "opening-holdout");
        Files.createDirectories(out);
        Path evidence = out.resolve("results.tsv");
        var report = new ArrayList<>(List.of(COLUMNS));
        measurements.stream().map(Measurement::tsv).forEach(report::add);
        Files.writeString(evidence, String.join("\n", report) + "\n");
        assertThat(measurements).as("full holdout results in %s", evidence).hasSize(32)
                .allMatch(Measurement::matches, "both predictions match the native sensor");
    }

    private static void inspect(Sample sample, Path dir, List<Measurement> measurements) throws Exception {
        Path cr3 = dir.resolve(sample.id + ".cr3");
        Path pgm = dir.resolve(sample.id + ".pgm");
        assertThat(sha256(cr3)).isEqualTo(sample.inputHash);
        assertThat(sha256(pgm)).isEqualTo(sample.pgmHash);
        var refs = SubbandExtractor.list(cr3.toFile());
        assertThat(refs).as("sensor streams for %s", sample.id).isNotEmpty();
        SubbandRef first = refs.getFirst();
        int width = first.trackWidth(), height = first.trackHeight();
        assertThat(first.tileWidth()).isPositive();
        assertThat(first.tileHeight()).isEqualTo(height);
        int tileCount = Math.ceilDiv(width, first.tileWidth());
        int[][] multiplicity = new int[tileCount][4];

        byte[] header = ("P5 %d %d 65535\n".formatted(width, height)).getBytes(StandardCharsets.US_ASCII);
        try (var pixels = new RandomAccessFile(pgm.toFile(), "r");
             var compressed = new RandomAccessFile(cr3.toFile(), "r")) {
            byte[] actual = new byte[header.length];
            pixels.readFully(actual);
            assertThat(actual).isEqualTo(header);
            assertThat(pixels.length()).isEqualTo(header.length + 2L * width * height);
            for (var ref : refs) {
                assertThat(ref.tileSequence()).isBetween(0, tileCount - 1);
                assertThat(ref.planeIndex()).isBetween(0, 3);
                assertThat(ref.trackWidth()).isEqualTo(width);
                assertThat(ref.tileWidth()).isEqualTo(first.tileWidth());
                assertThat(ref.trackHeight()).isEqualTo(height);
                multiplicity[ref.tileSequence()][ref.planeIndex()]++;
                int x = ref.tileSequence() * first.tileWidth() + (ref.planeIndex() & 1);
                int y = ref.planeIndex() / 2;
                assertThat(x + 2).isLessThan(width);
                pixels.seek(header.length + 2L * ((long) y * width + x));
                int expectedFirst = pixels.readUnsignedShort();
                pixels.readUnsignedShort(); // intervening Bayer site belongs to the adjacent plane
                int expectedSecond = pixels.readUnsignedShort();
                compressed.seek(ref.dataOffsetInFile());
                byte[] bytes = new byte[(int) Math.min(16L, ref.dataSize())];
                compressed.readFully(bytes);
                var fields = new ArrayList<>(List.of(sample.id, sample.model, sample.group, sample.inputHash,
                        sample.pgmHash, "" + ref.tileSequence(), "" + ref.planeIndex(), "" + width,
                        "" + height, "" + x, "" + y));
                measurements.add(compare(ref, bytes, expectedFirst, expectedSecond, fields));
            }
        }
        for (int[] planes : multiplicity) {
            assertThat(planes).as("one measured entry per plane on %s", sample.id).containsExactly(1, 1, 1, 1);
        }
    }

    private static Measurement compare(SubbandRef ref, byte[] bytes, int expectedFirst,
                                       int expectedSecond, List<String> fields) {
        String first = "-", firstStatus = "UNSUPPORTED", second = "-", secondStatus = "UNSUPPORTED";
        String branch = "-", q = "-", prefix = "-", code = "-", detail = "-";
        try {
            if (ref.waveletLevels() != 0 || ref.subbandIndex() != 0) {
                throw new IOException("Expected lossless single-subband layout");
            }
            prefix = Integer.toString(LosslessOpeningSamples.prefix18(bytes));
            code = Integer.toString(LosslessOpeningSamples.firstCode(bytes));
            int predicted = LosslessOpeningSamples.first(bytes, ref.bitDepth());
            first = Integer.toString(predicted);
            firstStatus = predicted == expectedFirst ? "MATCH" : "MISMATCH";
            var opening = LosslessOpeningSamples.decode(bytes, ref.bitDepth());
            second = Integer.toString(opening.second());
            secondStatus = opening.second() == expectedSecond ? "MATCH" : "MISMATCH";
            q = Integer.toString(opening.secondLeadingZeros());
            branch = opening.secondLeadingZeros() == 41 ? "LITERAL" : "ORDINARY";
        } catch (IOException unsupported) {
            detail = unsupported.getMessage();
        }
        fields.addAll(List.of("" + expectedFirst, first, firstStatus, "" + expectedSecond, second, secondStatus,
                branch, q, prefix, code, detail));
        return new Measurement(String.join("\t", fields), firstStatus.equals("MATCH") && secondStatus.equals("MATCH"));
    }

    private static String sha256(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var stream = new DigestInputStream(Files.newInputStream(path), digest)) {
            stream.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
