package com.photographer.focusstack.cr3.research;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;

/** Discovery-set replay. The held-out and intervention checks use separate reference data. */
@Tag("external-reference")
@EnabledIfSystemProperty(named = "cr3.opening.samples.enabled", matches = "true")
class LosslessOpeningSamplesIT {
    private record Row(String file, String inputHash, String referenceHash,
                       ReferenceGuidedFirstDivergenceAnalyzer.PlaneMapping mapping,
                       int prefix18, int firstCode, LosslessOpeningSamples.Opening opening,
                       int referenceFirst, int referenceSecond) {
        String tsv() {
            return String.join("\t", file, inputHash, referenceHash,
                    Integer.toString(mapping.tileSequence()), Integer.toString(mapping.planeIndex()),
                    Integer.toString(mapping.fullSensorX(0)), Integer.toString(mapping.fullSensorY(0)),
                    Integer.toString(mapping.fullSensorX(1)), Integer.toString(prefix18),
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
            int previousSize = rows.size();
            LibRawGuidedFirstDivergenceIT.visitPlanes(entry, (mapping, depth, reference, payload) -> {
                // Decode solely from the payload before consulting either expected pixel.
                try {
                    var opening = LosslessOpeningSamples.decode(payload, depth);
                    rows.add(new Row(entry.fixture(), inputHash, entry.sha256(), mapping,
                            LosslessOpeningSamples.prefix18(payload), LosslessOpeningSamples.firstCode(payload),
                            opening, reference[mapping.referenceIndex(0, 0)], reference[mapping.referenceIndex(1, 0)]));
                } catch (IOException e) {
                    throw new AssertionError("Unexpected discovery payload in " + entry.fixture()
                            + " tile " + mapping.tileSequence() + " plane " + mapping.planeIndex(), e);
                }
            });
            assertThat(rows.size() - previousSize).as("streams per input").isEqualTo(8);
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

    private static Map<String, String> registeredPanelHashes() throws IOException {
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
