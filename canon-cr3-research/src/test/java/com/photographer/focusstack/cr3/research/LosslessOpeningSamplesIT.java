package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.container.Cr3Track;
import com.photographer.focusstack.cr3.raw.codec.CrxHeader;
import java.awt.Rectangle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;

/** Discovery-set replay. The held-out and intervention checks use separate reference data. */
@Tag("external-reference")
@EnabledIfSystemProperty(named = "cr3.opening.samples.enabled", matches = "true")
class LosslessOpeningSamplesIT {
    private record Row(String file, String inputHash, String referenceHash,
                       int tile, int plane, int x, int y,
                       int prefix18, int firstCode, LosslessOpeningSamples.Opening opening,
                       int referenceFirst, int referenceSecond) {
        String tsv() {
            return String.join("\t", file, inputHash, referenceHash,
                    Integer.toString(tile), Integer.toString(plane), Integer.toString(x), Integer.toString(y),
                    Integer.toString(x + 2), Integer.toString(prefix18),
                    Integer.toString(firstCode), Integer.toString(opening.first()),
                    Integer.toString(referenceFirst), Integer.toString(opening.secondLeadingZeros()),
                    Integer.toString(opening.secondCode()), Integer.toString(opening.endBit()),
                    Integer.toString(opening.second()), Integer.toString(referenceSecond));
        }
    }

    @Test
    void replayOnTheDiscoveryPanelWithNoReferenceSeed() throws Exception {
        Map<String, String> inputHashes = registeredPanelHashes();
        List<Row> rows = new ArrayList<>();
        var entries = LibRawGuidedFirstDivergenceIT.parseRawLinearManifestEntries();
        assertThat(entries.stream().map(LibRawGuidedFirstDivergenceIT.ManifestEntry::fixture))
                .containsExactlyInAnyOrderElementsOf(KAdaptationFixtureSet.PANEL);
        for (var entry : entries) {
            String inputHash = LibRawGuidedFirstDivergenceIT.fixtureSha256(entry);
            assertThat(inputHash).as("RG2 input %s", entry.fixture()).isEqualTo(inputHashes.get(entry.fixture()));
            readOpeningSites(entry, inputHash, rows);
        }
        assertThat(rows).hasSize(88);
        Path directory = Path.of(System.getProperty("cr3.research.output.dir", "target"), "lossless-opening-samples");
        Files.createDirectories(directory);
        var report = new StringBuilder("fixture\tinputSha256\treferenceSha256\ttile\tplane\tfirstX\ty\tsecondX"
                + "\tprefix18\tfirstCode14\tfirstDecoded\tfirstReference\tsecondLeadingZeros"
                + "\tsecondCode\tendBit\tsecondDecoded\tsecondReference\n");
        rows.forEach(row -> report.append(row.tsv()).append('\n'));
        Files.writeString(directory.resolve("results.tsv"), report);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.opening.first()).isEqualTo(row.referenceFirst);
            assertThat(row.opening.second()).isEqualTo(row.referenceSecond);
        });
    }

    private static void readOpeningSites(LibRawGuidedFirstDivergenceIT.ManifestEntry entry,
                                         String inputHash, List<Row> rows) throws Exception {
        Path raw = Path.of(System.getProperty("cr3.samples.dir", "src/test/resources/samples"), entry.fixture());
        String name = raw.getFileName().toString();
        Path tiff = raw.resolveSibling(name.substring(0, name.lastIndexOf('.') + 1) + entry.sidecar());
        assertThat(LosslessFirstBitControlIT.sha256(tiff)).as("reference hash for %s", entry.fixture())
                .isEqualTo(entry.sha256());

        var reader = ImageIO.getImageReadersByFormatName("TIFF").next();
        try (var compressed = ImageIO.createImageInputStream(raw.toFile());
             var reference = ImageIO.createImageInputStream(tiff.toFile())) {
            reader.setInput(reference);
            assertThat(reader.getWidth(0)).isEqualTo(entry.width());
            assertThat(reader.getHeight(0)).isEqualTo(entry.height());
            var container = Cr3Parser.parse(compressed);
            var tracks = container.tracks().stream().filter(t -> t.type() == Cr3Track.TrackType.CRX_BIG
                    || t.type() == Cr3Track.TrackType.CRX_RAW).toList();
            assertThat(tracks).hasSize(1);
            var track = tracks.getFirst();
            var cmp = track.cmp1();
            assertThat(cmp.imageWidth()).isEqualTo(entry.width());
            assertThat(cmp.imageHeight()).isEqualTo(entry.height());
            assertThat(cmp.tileHeight()).isEqualTo(entry.height());
            assertThat(cmp.tileWidth()).isPositive().isEven();
            assertThat(Math.ceilDiv(entry.width(), cmp.tileWidth())).isEqualTo(2);
            assertThat(cmp.waveletLevels()).isZero();
            assertThat(cmp.bitsPerSample()).isLessThanOrEqualTo(entry.bitDepth());
            assertThat(cmp.mdatHeaderSize()).isEqualTo(216);
            // On this panel CMP1 sizes the marker records. CrxHeader also consumes
            // the following four-byte zero separator when locating the first subband.
            byte[] headerBytes = new byte[cmp.mdatHeaderSize() + 4];
            compressed.seek(track.dataOffset());
            compressed.readFully(headerBytes);
            var version = CrxHeader.detectVersion(container.cncvBox().versionString());
            var header = CrxHeader.parse(headerBytes, version);
            assertThat(header.headerSize()).isEqualTo(cmp.mdatHeaderSize() + 4);
            long tileBytes = header.tiles().stream().mapToLong(CrxHeader.Tile::dataSize).sum();
            assertThat(cmp.mdatHeaderSize() + tileBytes).isEqualTo(track.dataSize());
            long subbandBytes = 0;
            assertThat(header.tiles()).hasSize(2);
            for (int tileIndex = 0; tileIndex < header.tiles().size(); tileIndex++) {
                var planes = header.tiles().get(tileIndex).planes();
                assertThat(planes).extracting(CrxHeader.Plane::index).containsExactly(0, 1, 2, 3);
                for (var plane : planes) {
                    assertThat(plane.subbands()).hasSize(1);
                    var subband = plane.subbands().getFirst();
                    assertThat(subband.index()).isZero();
                    subbandBytes += subband.dataSize();
                    // The longest admitted opening takes 95 bits.
                    byte[] payload = new byte[(int) Math.min(12L, subband.dataSize())];
                    assertThat(subband.dataOffset() + payload.length).isLessThanOrEqualTo(track.dataSize());
                    compressed.seek(track.dataOffset() + subband.dataOffset() - 4);
                    assertThat(compressed.readInt()).as("32 bits before extractor origin").isZero();
                    compressed.readFully(payload);
                    var opening = LosslessOpeningSamples.decode(payload, cmp.bitsPerSample());

                    int x = tileIndex * cmp.tileWidth() + (plane.index() & 1);
                    int y = plane.index() / 2;
                    assertThat(x + 2).isLessThan(entry.width());
                    var region = reader.getDefaultReadParam();
                    region.setSourceRegion(new Rectangle(x, y, 3, 1));
                    region.setSourceSubsampling(2, 1, 0, 0);
                    var raster = reader.read(0, region).getRaster();
                    assertThat(raster.getNumBands()).isEqualTo(1);
                    assertThat(raster.getSampleModel().getSampleSize(0)).isEqualTo(entry.bitDepth());
                    rows.add(new Row(entry.fixture(), inputHash, entry.sha256(), tileIndex, plane.index(), x, y,
                            LosslessOpeningSamples.prefix18(payload), LosslessOpeningSamples.firstCode(payload),
                            opening, raster.getSample(0, 0, 0), raster.getSample(1, 0, 0)));
                }
            }
            assertThat(subbandBytes).isEqualTo(tileBytes);
            assertThat(header.headerSize() + subbandBytes).isEqualTo(track.dataSize() + 4);
        } finally {
            reader.dispose();
        }
    }

    static Map<String, String> registeredPanelHashes() throws IOException {
        var file = Path.of("../docs/cr3-results/2026-11-14-crx/lossless-prefix.tsv");
        List<String> lines = Files.readAllLines(file);
        assertThat(lines.getFirst()).startsWith("fixture\tinputSha256\t");
        Map<String, String> hashes = new HashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] fields = line.split("\t", 3);
            String previous = hashes.putIfAbsent(fields[0], fields[1]);
            if (previous != null) {
                assertThat(previous).as("one input hash per fixture").isEqualTo(fields[1]);
            }
        }
        return hashes;
    }
}
