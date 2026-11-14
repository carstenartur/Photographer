package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.container.Cr3Track;
import com.photographer.focusstack.cr3.raw.codec.CrxHeader;
import java.awt.Rectangle;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;

/** Decodes the complete first row of each tile/plane before comparing it with the reference. */
@Tag("external-reference")
class LosslessFirstRowIT {
    private record ReferenceRows(int width, int height, int[][] rows) {}
    private record Measurement(String fixture, String inputHash, String referenceHash, int tile, int plane,
                               int width, int endBit, int finalK, int matched, String decodedHash, String expectedHash) {
        String tsv() {
            return "%s\t%s\t%s\t%d\t%d\t%d\t%d\t%d\t%d\t%s\t%s".formatted(
                    fixture, inputHash, referenceHash, tile, plane, width, endBit, finalK, matched,
                    decodedHash, expectedHash);
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "cr3.first-row.discovery.enabled", matches = "true")
    void discovery() throws Exception {
        var results = new ArrayList<Measurement>();
        var registeredHashes = LosslessOpeningSamplesIT.registeredPanelHashes();
        var entries = LibRawGuidedFirstDivergenceIT.parseRawLinearManifestEntries();
        assertThat(entries.stream().map(LibRawGuidedFirstDivergenceIT.ManifestEntry::fixture))
                .containsExactlyInAnyOrderElementsOf(KAdaptationFixtureSet.PANEL);
        for (var entry : entries) {
            Path raw = Path.of(System.getProperty("cr3.samples.dir", "src/test/resources/samples"), entry.fixture());
            String file = raw.getFileName().toString();
            Path tiff = raw.resolveSibling(file.substring(0, file.lastIndexOf('.') + 1) + entry.sidecar());
            String inputHash = registeredHashes.get(entry.fixture());
            assertThat(LosslessFirstBitControlIT.sha256(raw)).isEqualTo(inputHash);
            assertThat(LosslessFirstBitControlIT.sha256(tiff)).isEqualTo(entry.sha256());
            var reader = ImageIO.getImageReadersByFormatName("TIFF").next();
            try (var stream = ImageIO.createImageInputStream(tiff.toFile())) {
                reader.setInput(stream);
                assertThat(reader.getWidth(0)).isEqualTo(entry.width());
                assertThat(reader.getHeight(0)).isEqualTo(entry.height());
                var params = reader.getDefaultReadParam();
                params.setSourceRegion(new Rectangle(0, 0, entry.width(), 2));
                var raster = reader.read(0, params).getRaster();
                assertThat(raster.getNumBands()).isOne();
                assertThat(raster.getSampleModel().getSampleSize(0)).isEqualTo(entry.bitDepth());
                int[][] rows = new int[2][entry.width()];
                for (int y = 0; y < 2; y++) {
                    raster.getSamples(0, y, entry.width(), 1, 0, rows[y]);
                }
                inspect(entry.fixture(), raw, inputHash, entry.sha256(),
                        new ReferenceRows(entry.width(), entry.height(), rows), results);
            } finally {
                reader.dispose();
            }
        }
        report("discovery", results, 88);
    }

    @Test
    @EnabledIfSystemProperty(named = "cr3.first-row.holdout.enabled", matches = "true")
    void holdout() throws Exception {
        var results = new ArrayList<Measurement>();
        Path cache = Path.of(System.getProperty("cr3.opening.holdout.dir"));
        Path root = Path.of(System.getProperty("maven.multiModuleProjectDirectory", ".."));
        var registry = root.resolve("scripts/research/public-crx/lossless-holdout.tsv");
        var entries = Files.readAllLines(registry).stream().skip(1)
                .map(LosslessOpeningHoldoutIT.Sample::fromRegistry).toList();
        assertThat(entries).extracting(LosslessOpeningHoldoutIT.Sample::id)
                .containsExactly("3510", "4515", "4657", "4674");
        for (var entry : entries) {
            var raw = cache.resolve(entry.id() + ".cr3");
            var pgm = cache.resolve(entry.id() + ".pgm");
            assertThat(LosslessFirstBitControlIT.sha256(raw)).isEqualTo(entry.inputHash());
            assertThat(LosslessFirstBitControlIT.sha256(pgm)).isEqualTo(entry.pgmHash());
            try (var pixels = new RandomAccessFile(pgm.toFile(), "r")) {
                String header = pixels.readLine();
                assertThat(header).matches("P5 [1-9][0-9]* [1-9][0-9]* 65535");
                String[] fields = header.split(" ");
                int width = Integer.parseInt(fields[1]), height = Integer.parseInt(fields[2]);
                assertThat(pixels.length()).isEqualTo(pixels.getFilePointer() + 2L * width * height);
                int[][] rows = new int[2][width];
                for (int[] row : rows) {
                    for (int x = 0; x < width; x++) {
                        row[x] = pixels.readUnsignedShort();
                    }
                }
                inspect(entry.id(), raw, entry.inputHash(), entry.pgmHash(),
                        new ReferenceRows(width, height, rows), results);
            }
        }
        report("holdout", results, 32);
    }

    private static void inspect(String id, Path raw, String inputHash, String referenceHash,
                                ReferenceRows reference, List<Measurement> results) throws Exception {
        try (var in = ImageIO.createImageInputStream(raw.toFile())) {
            var container = Cr3Parser.parse(in);
            var tracks = container.tracks().stream().filter(t -> t.type() == Cr3Track.TrackType.CRX_BIG
                    || t.type() == Cr3Track.TrackType.CRX_RAW).toList();
            assertThat(tracks).hasSize(1);
            var track = tracks.getFirst();
            var cmp = track.cmp1();
            assertThat(cmp.bitsPerSample()).isEqualTo(14);
            assertThat(cmp.waveletLevels()).isZero();
            assertThat(cmp.imageWidth()).isEqualTo(reference.width());
            assertThat(cmp.imageHeight()).isEqualTo(reference.height());
            assertThat(cmp.tileHeight()).isEqualTo(reference.height());
            assertThat(cmp.tileWidth()).isPositive().isEven();
            assertThat(cmp.mdatHeaderSize()).isEqualTo(216);
            byte[] headerBytes = new byte[cmp.mdatHeaderSize() + 4];
            in.seek(track.dataOffset());
            in.readFully(headerBytes);
            var header = CrxHeader.parse(headerBytes, CrxHeader.detectVersion(container.cncvBox().versionString()));
            assertThat(header.headerSize()).isEqualTo(headerBytes.length);
            assertThat(header.tiles()).hasSize(2);
            for (int t = 0; t < header.tiles().size(); t++) {
                var planes = header.tiles().get(t).planes();
                assertThat(planes).extracting(CrxHeader.Plane::index).containsExactly(0, 1, 2, 3);
                for (var plane : planes) {
                    assertThat(plane.subbands()).hasSize(1);
                    var sub = plane.subbands().getFirst();
                    assertThat(sub.index()).isZero();
                    int x0 = t * cmp.tileWidth() + (plane.index() & 1);
                    int end = Math.min(reference.width(), (t + 1) * cmp.tileWidth());
                    int width = Math.ceilDiv(end - x0, 2);
                    assertThat(width).isPositive();
                    // Each admitted residual consumes at most 63 bits. The RG3 origin skips
                    // the first four declared subband bytes; stay within the physical extent.
                    int bytes = Math.toIntExact(Math.min(sub.dataSize() - 4, 8L * width));
                    assertThat(sub.dataOffset() + bytes).isLessThanOrEqualTo(track.dataSize());
                    in.seek(track.dataOffset() + sub.dataOffset());
                    byte[] prefix = new byte[bytes];
                    in.readFully(prefix);
                    var row = LosslessFirstRow.decode(prefix, cmp.bitsPerSample(), width);
                    int[] expected = new int[width];
                    int matched = 0;
                    for (int x = 0; x < width; x++) {
                        expected[x] = reference.rows()[plane.index() / 2][x0 + 2 * x];
                        if (row.samples()[x] == expected[x]) {
                            matched++;
                        }
                    }
                    results.add(new Measurement(id, inputHash, referenceHash, t, plane.index(), width,
                            row.endBit(), row.finalK(), matched, hash(row.samples()), hash(expected)));
                }
            }
        }
    }

    private static String hash(int[] row) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        for (int value : row) {
            digest.update((byte) (value >>> 8));
            digest.update((byte) value);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void report(String split, List<Measurement> results, int count) throws IOException {
        Path dir = Path.of(System.getProperty("cr3.research.output.dir", "target"), "lossless-first-row");
        Files.createDirectories(dir);
        var lines = new ArrayList<>(List.of("fixture\tinputSha256\treferenceSha256\ttile\tplane\twidth"
                + "\tendBit\tfinalK\tmatched\tdecodedRowSha256\texpectedRowSha256"));
        results.stream().map(Measurement::tsv).forEach(lines::add);
        Files.writeString(dir.resolve(split + ".tsv"), String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        assertThat(results).hasSize(count).allSatisfy(m -> {
            assertThat(m.matched()).as("%s tile %d plane %d", m.fixture(), m.tile(), m.plane()).isEqualTo(m.width());
            assertThat(m.decodedHash()).isEqualTo(m.expectedHash());
        });
    }
}
