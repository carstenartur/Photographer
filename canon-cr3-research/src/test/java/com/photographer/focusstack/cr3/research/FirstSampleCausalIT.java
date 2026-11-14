package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.raw.codec.SubbandExtractor;
import com.photographer.focusstack.cr3.raw.codec.SubbandExtractor.SubbandRef;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/** Bounded, optional black-box interventions on two first-party lossless subbands. */
@Tag("external-reference")
@EnabledIfSystemProperty(named = "cr3.first-sample.control.enabled", matches = "true")
class FirstSampleCausalIT {
    private static final String DNG_SHA = "d45334659d008b010966f56c3a472677f510a1775d78debd45f5078612ac0683";
    private record Input(String file, String originalHash, String pgmHash, int sample, int transplant) {}
    private static final List<Input> INPUTS = List.of(
            new Input("eosr-iso-low.cr3", "b919df484f0c3765f707974dc5d33a1c28143180981200cdf695133fb0e721b3",
                    "e56d1e34d5c70dbc22b0f9c1c82a455aaefa42c605e7290ce1c70f686d4d4813", 2048, 0x2ff7),
            new Input("sample-wide.cr3", "da35045099960ca2743da38effb984e296da6cb5bca07fa340990a90f0faa1ff",
                    "805d18d774771cc2ab32f8fedfa495ecaa9b210b22aec85c574b14463445c33c", 2052, 0x2fff));
    private static final String HEADER = "fixture\ttrackOffset\tintervention\tmutantSha256\tprefix18\tnativeWord\tbranch\texpectedFirst\tstatus\tfirstValue\tsecondValue"
            + "\tfirstDifference(x,y,old,new)\tchangedSites\toutsideTile\toutsidePlane\tpgmSha256\n";

    @Test
    void measureFirstSample(@TempDir Path work) throws Exception {
        Path dnglab = Path.of(System.getProperty("cr3.first-bit.dnglab")).toAbsolutePath();
        assertThat(LosslessFirstBitControlIT.sha256(dnglab)).isEqualTo(DNG_SHA);
        Path directory = Path.of(System.getProperty("cr3.samples.dir", "src/test/resources/samples"));
        var report = new StringBuilder(HEADER);
        var suffix = new StringBuilder("fixture\ttrackOffset\tbit\tmutantSha256\tbaselineFirst\tbaselineSecond"
                + "\texpectedFirst\texpectedSecond\tstatus\tfirstValue\tsecondValue\tfirstDifference(x,y,old,new)"
                + "\tchangedSites\toutsideTile\toutsidePlane\tpgmSha256\n");
        for (Input input : INPUTS) measure(input, directory.resolve(input.file()), dnglab, work, report, suffix);
        Path output = Path.of(System.getProperty("cr3.research.output.dir", "target"), "first-sample-causality");
        Files.createDirectories(output);
        Path fields = output.resolve("results.tsv");
        Path remainders = output.resolve("suffix.tsv");
        Files.writeString(fields, report, StandardCharsets.UTF_8);
        Files.writeString(remainders, suffix, StandardCharsets.UTF_8);
        // Freeze every intervention, status and full output digest, including unscored controls.
        assertThat(LosslessFirstBitControlIT.sha256(fields)).as("RG3 field interventions")
                .isEqualTo("5f26e11d54717425407810cbbaaf876bac550fb726acaad2c50c2863b72285a6");
        assertThat(LosslessFirstBitControlIT.sha256(remainders)).as("RG3b remainder interventions")
                .isEqualTo("1fbafdcfa40127bf5d3ace2265aa6fb4942bd765ae73d581580077069f0c994b");
    }

    private static void measure(Input input, Path original, Path dnglab, Path work, StringBuilder report, StringBuilder suffix)
            throws Exception {
        assertThat(LosslessFirstBitControlIT.sha256(original)).isEqualTo(input.originalHash());
        var selected = SubbandExtractor.list(original.toFile()).stream()
                .filter(r -> r.tileSequence() == 0 && r.planeIndex() == 0 && r.subbandIndex() == 0).toList();
        assertThat(selected).hasSize(1);
        var ref = selected.getFirst();
        assertThat(ref.waveletLevels()).isZero();
        assertThat(ref.bitDepth()).isEqualTo(14);
        assertThat(ref.dataSize()).isGreaterThan(4);
        Path pgm = work.resolve("base.pgm"), mutant = work.resolve("mutant.cr3");
        Path changed = work.resolve("changed.pgm"), stderr = work.resolve("stderr");
        byte[] header = ("P5 %d %d 65535\n".formatted(ref.trackWidth(), ref.trackHeight())).getBytes(StandardCharsets.US_ASCII);
        assertThat(LosslessFirstBitControlIT.decode(dnglab, original, pgm, stderr)).isZero();
        LosslessFirstBitControlIT.verifyPgm(pgm, header, ref);
        assertThat(LosslessFirstBitControlIT.sha256(pgm)).isEqualTo(input.pgmHash());
        int baselineSecond;
        try (var base = new DataInputStream(Files.newInputStream(pgm))) {
            base.skipNBytes(header.length);
            assertThat(base.readUnsignedShort()).isEqualTo(input.sample());
            base.skipNBytes(2); // (1, 0) belongs to another plane.
            baselineSecond = base.readUnsignedShort();
        }
        // Both selected fixtures complete their ordinary second code within five bytes.
        Files.copy(original, mutant, StandardCopyOption.REPLACE_EXISTING);
        try (var bytes = new RandomAccessFile(mutant.toFile(), "rw")) {
            bytes.seek(ref.dataOffsetInFile());
            byte[] prefix = new byte[5];
            bytes.readFully(prefix);
            assertThat(LosslessOpeningSamples.first(prefix, ref.bitDepth())).isEqualTo(input.sample());
            for (String action : interventions()) {
                byte[] edit = prefix.clone();
                if (action.equals("transplant")) {
                    edit[2] = (byte) ((edit[2] & 0xc0) | input.transplant() >>> 8);
                    edit[3] = (byte) input.transplant();
                } else {
                    int bit = Integer.parseInt(action);
                    edit[bit / 8] ^= (byte) (0x80 >>> (bit % 8));
                }
                bytes.seek(ref.dataOffsetInFile());
                bytes.write(edit);
                int word = LosslessOpeningSamples.firstCode(edit);
                int prefix18 = LosslessOpeningSamples.prefix18(edit);
                String forecast = prefix18 == 128
                        ? Integer.toString(LosslessOpeningSamples.first(edit, ref.bitDepth())) : "-";
                report.append(input.file()).append('\t').append(ref.dataOffsetInFile()).append('\t').append(action)
                        .append('\t').append(LosslessFirstBitControlIT.sha256(mutant)).append('\t').append(prefix18).append('\t').append(word).append('\t')
                        .append((word & 1) == 0 ? "EVEN" : "ODD").append('\t')
                        .append(forecast).append('\t');
                boolean accepted = accepted(dnglab, mutant, changed, stderr);
                if (accepted) {
                    LosslessFirstBitControlIT.verifyPgm(changed, header, ref);
                    var diff = compare(pgm, changed, header.length, ref);
                    if (prefix18 == 128) {
                        assertThat(diff.firstValue()).as("first after %s", action).isEqualTo(Integer.parseInt(forecast));
                    }
                    report.append("ACCEPTED\t").append(diff.tsv()).append('\t')
                            .append(LosslessFirstBitControlIT.sha256(changed));
                } else {
                    report.append("REJECTED\t-\t-\t-\t-\t-\t-\t-");
                }
                report.append('\n');
            }

            var baseline = LosslessOpeningSamples.decode(prefix, ref.bitDepth());
            assertThat(baseline.secondLeadingZeros()).isLessThan(41);
            assertThat(baseline.second()).isEqualTo(baselineSecond);
            for (int bit = baseline.endBit() - 2; bit < baseline.endBit(); bit++) {
                byte[] edit = prefix.clone();
                edit[bit / 8] ^= (byte) (0x80 >>> (bit % 8));
                var forecast = LosslessOpeningSamples.decode(edit, ref.bitDepth());
                bytes.seek(ref.dataOffsetInFile());
                bytes.write(edit);
                suffix.append(input.file()).append('\t').append(ref.dataOffsetInFile()).append('\t').append(bit)
                        .append('\t').append(LosslessFirstBitControlIT.sha256(mutant)).append('\t')
                        .append(baseline.first()).append('\t').append(baseline.second()).append('\t')
                        .append(forecast.first()).append('\t').append(forecast.second()).append('\t');
                if (accepted(dnglab, mutant, changed, stderr)) {
                    LosslessFirstBitControlIT.verifyPgm(changed, header, ref);
                    var diff = compare(pgm, changed, header.length, ref);
                    assertThat(diff.firstValue()).as("first after remainder bit %d", bit).isEqualTo(forecast.first());
                    assertThat(diff.secondValue()).as("second after remainder bit %d", bit).isEqualTo(forecast.second());
                    suffix.append("ACCEPTED\t").append(diff.tsv()).append('\t').append(LosslessFirstBitControlIT.sha256(changed));
                } else {
                    suffix.append("REJECTED\t-\t-\t-\t-\t-\t-\t-");
                }
                suffix.append('\n');
            }
        }
    }

    private static boolean accepted(Path dnglab, Path mutant, Path changed, Path stderr) throws Exception {
        int status = LosslessFirstBitControlIT.decode(dnglab, mutant, changed, stderr);
        if (status == 0) return true;
        String diagnostic = Files.readString(stderr);
        if (status == 2 && diagnostic.contains("Error: Decoder failed: Failed to decode image, possibly corrupt image:")) {
            return false;
        }
        throw new IOException("Unexpected DNGLab failure: " + status + " " + diagnostic);
    }

    private static List<String> interventions() {
        return List.of("10", "11", "17", "18", "19", "20", "21", "22", "23", "24", "25", "26", "27",
                "28", "29", "30", "31", "32", "33", "transplant");
    }

    private record Changes(int firstValue, int secondValue, String firstDifference, int changedSites,
                           int outsideTile, int outsidePlane) {
        String tsv() {
            return "%d\t%d\t%s\t%d\t%d\t%d".formatted(firstValue, secondValue, firstDifference, changedSites, outsideTile, outsidePlane);
        }
    }

    private static Changes compare(Path base, Path changed, int skip, SubbandRef ref) throws IOException {
        int width = ref.trackWidth();
        byte[] a = new byte[2 * width], b = new byte[2 * width];
        int changedSites = 0, outsideTile = 0, outsidePlane = 0, firstValue = -1, secondValue = -1;
        String first = "-";
        try (var oldData = new DataInputStream(Files.newInputStream(base));
             var newData = new DataInputStream(Files.newInputStream(changed))) {
            oldData.skipNBytes(skip);
            newData.skipNBytes(skip);
            for (int y = 0; y < ref.trackHeight(); y++) {
                oldData.readFully(a);
                newData.readFully(b);
                if (y == 0) {
                    firstValue = u16(b, 0);
                    secondValue = u16(b, 4); // same plane, uncropped coordinate (2, 0)
                }
                if (Arrays.equals(a, b)) continue;
                for (int x = 0; x < width; x++) {
                    int idx = 2 * x;
                    if (a[idx] == b[idx] && a[idx + 1] == b[idx + 1]) continue;
                    if (changedSites++ == 0) first = "%d,%d,%d,%d".formatted(x, y, u16(a, idx), u16(b, idx));
                    if (x >= ref.tileWidth() || y >= ref.tileHeight()) outsideTile++;
                    if (((x | y) & 1) != 0) outsidePlane++;
                }
            }
        }
        return new Changes(firstValue, secondValue, first, changedSites, outsideTile, outsidePlane);
    }
    private static int u16(byte[] bytes, int index) {
        return (Byte.toUnsignedInt(bytes[index]) << 8) | Byte.toUnsignedInt(bytes[index + 1]);
    }
}
