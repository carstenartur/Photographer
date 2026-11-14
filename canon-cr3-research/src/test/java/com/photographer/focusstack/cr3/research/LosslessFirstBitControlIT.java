package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.raw.codec.SubbandExtractor;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/** Changes only the first bit of one lossless subband, then compares external sensor outputs. */
@Tag("external-reference")
@EnabledIfSystemProperty(named = "cr3.first-bit.control.enabled", matches = "true")
class LosslessFirstBitControlIT {
    private static final String DNGLAB_SHA256 =
            "d45334659d008b010966f56c3a472677f510a1775d78debd45f5078612ac0683";
    private static final String HEADER = "fixture\tinputSha256\tmutatedSha256\toffset\tbyteBefore\tbyteAfter\twidth"
            + "\theight\tfirstTileWidth\tstatus\trc\tmismatches\tfirstMismatch(x,y,base,changed)\tmaxDelta"
            + "\tbounds(x0,y0,x1,y1)\tparityCounts(00,10,01,11)\toutOfTile\tmatchedFirstRowTargetPrefix"
            + "\tbaselinePgmSha256\tmutatedPgmSha256\n";
    private record Sample(String name, String inputHash) {}
    private static final List<Sample> SAMPLES = List.of(
            new Sample("eosr-iso-low.cr3", "b919df484f0c3765f707974dc5d33a1c28143180981200cdf695133fb0e721b3"),
            new Sample("sample-wide.cr3", "da35045099960ca2743da38effb984e296da6cb5bca07fa340990a90f0faa1ff"));

    @Test
    void compareOriginalAndFirstBitMutation(@TempDir Path scratch) throws Exception {
        String binary = System.getProperty("cr3.first-bit.dnglab");
        assertThat(binary).as("path to DNGLab 0.8.0 for Linux x86-64").isNotBlank();
        Path tool = Path.of(binary).toAbsolutePath();
        assertThat(sha256(tool)).as("DNGLab 0.8.0 release binary").isEqualTo(DNGLAB_SHA256);
        Path fixtures = Path.of(System.getProperty("cr3.samples.dir", "src/test/resources/samples"));
        var report = new StringBuilder(HEADER);
        for (Sample sample : SAMPLES) {
            report.append(run(sample, fixtures.resolve(sample.name()), tool, scratch)).append('\n');
        }
        Path output = Path.of(System.getProperty("cr3.research.output.dir", "target"), "first-bit-control");
        Files.createDirectories(output);
        Files.writeString(output.resolve("results.tsv"), report, StandardCharsets.UTF_8);
    }

    private static String run(Sample sample, Path original, Path tool, Path scratch) throws Exception {
        assertThat(sha256(original)).as(sample.name()).isEqualTo(sample.inputHash());
        var selected = SubbandExtractor.list(original.toFile()).stream()
                .filter(ref -> ref.tileSequence() == 0 && ref.planeIndex() == 0 && ref.subbandIndex() == 0)
                .toList();
        assertThat(selected).as("first lossless tile/plane/subband").hasSize(1);
        var ref = selected.getFirst();
        assertThat(ref.waveletLevels()).isZero();
        assertThat(ref.tileHeight()).isEqualTo(ref.trackHeight());
        assertThat(ref.tileWidth()).isPositive();
        assertThat(ref.dataSize()).isPositive();
        Path mutated = Files.copy(original, scratch.resolve("mutant.cr3"), StandardCopyOption.REPLACE_EXISTING);
        int before;
        try (var file = new RandomAccessFile(mutated.toFile(), "rw")) {
            file.seek(ref.dataOffsetInFile());
            before = file.readUnsignedByte();
            file.seek(ref.dataOffsetInFile());
            file.writeByte(before ^ 0x80);
        }
        String prefix = "%s\t%s\t%s\t%d\t%02x\t%02x\t%d\t%d\t%d\t".formatted(
                sample.name(), sample.inputHash(), sha256(mutated), ref.dataOffsetInFile(), before, before ^ 0x80,
                ref.trackWidth(), ref.trackHeight(), ref.tileWidth());
        Path base = scratch.resolve("original.pgm");
        Path changed = scratch.resolve("changed.pgm");
        Path stderr = scratch.resolve("stderr.txt");
        assertThat(decode(tool, original, base, stderr)).as("decode original %s", sample.name()).isZero();
        byte[] header = ("P5 %d %d 65535\n".formatted(ref.trackWidth(), ref.trackHeight()))
                .getBytes(StandardCharsets.US_ASCII);
        verifyPgm(base, header, ref);
        String baseSha = sha256(base);
        int exit = decode(tool, mutated, changed, stderr);
        if (exit != 0) {
            // Classify only the pinned binary's explicit image rejection. System/tool failures must fail the run.
            String diagnostic;
            try (var in = Files.newInputStream(stderr)) {
                diagnostic = new String(in.readNBytes(8192), StandardCharsets.UTF_8);
            }
            if (exit != 2 || !diagnostic.contains("Error: Decoder failed: Failed to decode image, possibly corrupt image:")) {
                throw new IOException("Unexpected DNGLab failure (" + exit + "): " + diagnostic);
            }
            return prefix + "DECODER_REJECTED\t2\t-\t-\t-\t-\t-\t-\t-\t" + baseSha + "\t-";
        }
        verifyPgm(changed, header, ref);
        return prefix + compare(base, changed, header.length, ref) + "\t" + baseSha + "\t" + sha256(changed);
    }

    static int decode(Path tool, Path file, Path pgm, Path stderr) throws Exception {
        var process = new ProcessBuilder(tool.toString(), "analyze", "--raw-pixel", file.toString())
                .redirectOutput(pgm.toFile()).redirectError(stderr.toFile()).start();
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                throw new IOException("DNGLab exceeded the 60-second limit");
            }
            return process.exitValue();
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        }
    }

    static void verifyPgm(Path file, byte[] header, SubbandExtractor.SubbandRef ref) throws IOException {
        long size = header.length + 2L * ref.trackWidth() * ref.trackHeight();
        try (var input = Files.newInputStream(file)) {
            if (Files.size(file) != size || !Arrays.equals(header, input.readNBytes(header.length))) {
                throw new IOException("Expected complete, full-sensor, 16-bit DNGLab PGM");
            }
        }
    }

    private static String compare(Path base, Path changed, int headerSize, SubbandExtractor.SubbandRef ref)
            throws IOException {
        int width = ref.trackWidth(), height = ref.trackHeight();
        int count = 0, outsideTile = 0, maxDelta = 0;
        int minX = width, minY = height, maxX = -1, maxY = -1;
        int matchedPrefix = Math.ceilDiv(ref.tileWidth(), 2);
        int[] parity = new int[4];
        String first = "-";
        try (var a = new DataInputStream(new BufferedInputStream(Files.newInputStream(base)));
             var b = new DataInputStream(new BufferedInputStream(Files.newInputStream(changed)))) {
            a.skipNBytes(headerSize);
            b.skipNBytes(headerSize);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int oldValue = a.readUnsignedShort();
                    int newValue = b.readUnsignedShort();
                    if (oldValue == newValue) continue;
                    if (count == 0) first = "%d,%d,%d,%d".formatted(x, y, oldValue, newValue);
                    count++;
                    parity[(x & 1) + 2 * (y & 1)]++;
                    if (x >= ref.tileWidth()) outsideTile++;
                    if (y == 0 && x < ref.tileWidth() && (x & 1) == 0) matchedPrefix = Math.min(matchedPrefix, x / 2);
                    maxDelta = Math.max(maxDelta, Math.abs(oldValue - newValue));
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        String status = "NO_OBSERVED_CHANGE";
        String bounds = "-";
        if (count > 0) {
            status = outsideTile > 0 || parity[0] != count ? "CHANGED_BEYOND_TARGET" : "CHANGED_TARGET_ONLY";
            bounds = "%d,%d,%d,%d".formatted(minX, minY, maxX, maxY);
        }
        return "%s\t0\t%d\t%s\t%d\t%s\t%d,%d,%d,%d\t%d\t%d".formatted(
                status, count, first, maxDelta, bounds, parity[0], parity[1], parity[2], parity[3], outsideTile, matchedPrefix);
    }

    static String sha256(Path path) throws IOException, NoSuchAlgorithmException {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = new DigestInputStream(Files.newInputStream(path), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
