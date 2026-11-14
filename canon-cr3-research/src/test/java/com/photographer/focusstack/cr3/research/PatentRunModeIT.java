package com.photographer.focusstack.cr3.research;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** Issue #332 predeclares the fixed sources, baseline, elementary branch and scoring. */
@Tag("external-reference")
@EnabledIfSystemProperty(named = "cr3.patent.run-mode.enabled", matches = "true")
class PatentRunModeIT {
    private record Row(String fixture, String inputSha256, String tiffHash, long firstOneBit,
                       int tile, int plane, int x0, int y0,
                       int seed, PatentRunModeProbe.Prefix baseline,
                       PatentRunModeProbe.Prefix candidate, boolean guardAtBaselineMismatch) {
        String family() {
            return fixture.startsWith("eosr-") ? "EOS R" : "G5 X Mark II";
        }

        String comparison() {
            return PatentRunModeProbe.compare(baseline, candidate);
        }

        String tsv() {
            return String.join("\t", fixture, inputSha256, tiffHash, "" + firstOneBit, family(), "" + tile, "" + plane,
                    "" + x0, "" + y0, "" + seed, "" + baseline.matched(), "" + baseline.bitStart(),
                    "" + baseline.bitEnd(), "" + baseline.expected(), "" + baseline.reconstructed(),
                    "" + guardAtBaselineMismatch, "" + candidate.matched(), candidate.stop().name(),
                    "" + candidate.bitStart(), "" + candidate.bitEnd(), "" + candidate.expected(),
                    candidate.reconstructed() == null ? "" : candidate.reconstructed().toString(),
                    "" + candidate.guardAtStop(), candidate.flagOffsets(), comparison());
        }
    }

    @Test
    void compareModeFlagWithRg1OnFixedPanel() throws Exception {
        List<LibRawGuidedFirstDivergenceIT.ManifestEntry> entries =
                LibRawGuidedFirstDivergenceIT.parseRawLinearManifestEntries();
        assertThat(entries.stream().map(LibRawGuidedFirstDivergenceIT.ManifestEntry::fixture))
                .containsExactlyInAnyOrderElementsOf(KAdaptationFixtureSet.PANEL);
        var rows = new ArrayList<Row>();
        for (var entry : entries) {
            int start = rows.size();
            String inputSha256 = LibRawGuidedFirstDivergenceIT.fixtureSha256(entry);
            LibRawGuidedFirstDivergenceIT.visitPlanes(entry, (mapping, depth, mosaic, payload) -> {
                int[] firstRow = new int[Math.min(PatentRunModeProbe.LIMIT, mapping.planeWidth())];
                for (int x = 0; x < firstRow.length; x++) firstRow[x] = mosaic[mapping.referenceIndex(x, 0)];
                var baseline = PatentRunModeProbe.measure(payload, depth, firstRow, false);
                var candidate = PatentRunModeProbe.measure(payload, depth, firstRow, true);
                assertThat(baseline.stop()).as("RG1 first mismatch").isEqualTo(PatentRunModeProbe.Stop.MISMATCH);
                boolean eligible = baseline.matched() == 0 || firstRow[baseline.matched() - 1] == 0;
                rows.add(new Row(entry.fixture(), inputSha256, entry.sha256(),
                        PatentRunModeProbe.firstOneBit(payload), mapping.tileSequence(),
                        mapping.planeIndex(), mapping.fullSensorX(0), mapping.fullSensorY(0),
                        firstRow[0], baseline, candidate, eligible));
            });
            assertThat(rows.size() - start).as("fixed two-tile/four-plane fixture").isEqualTo(8);
        }
        assertThat(rows).hasSize(88);
        assertThat(rows.stream().filter(r -> r.baseline.matched() == 1).count()).isEqualTo(71);
        assertThat(median(rows.stream().map(r -> r.baseline.matched()).toList())).isEqualTo(1.0);
        Path output = Path.of(System.getProperty("cr3.research.output.dir", "target"), "patent-run-mode");
        Files.createDirectories(output);
        var tsv = new StringBuilder("fixture\tinputSha256\treferenceSha256\tfirstOneBit\tfamily\ttile\tplane\tfullX0\tfullY0\tseed"
                + "\tbaselinePrefix\tbaselineBitStart\tbaselineBitEnd\tbaselineExpected\tbaselineDecoded"
                + "\tguardAtBaselineMismatch\tcandidatePrefix\tcandidateStop\tcandidateBitStart\tcandidateBitEnd"
                + "\tcandidateExpected\tcandidateDecoded\tguardAtCandidateStop\tflagOffset=value\tcomparison\n");
        for (Row row : rows) tsv.append(row.tsv()).append('\n');
        Files.writeString(output.resolve("results.tsv"), tsv);
        var summary = new StringBuilder("family\tstreams\tbaselineMedian\tcandidateMedianLowerBound"
                + "\teligibleAtBaselineMismatch\timproved\tunchanged\tworse\tunresolved\n");
        for (String family : List.of("all", "EOS R", "G5 X Mark II")) {
            var group = rows.stream().filter(r -> family.equals("all") || r.family().equals(family)).toList();
            summary.append(String.format(Locale.ROOT, "%s\t%d\t%.1f\t%.1f\t%d", family, group.size(),
                    median(group.stream().map(r -> r.baseline.matched()).toList()),
                    median(group.stream().map(r -> r.candidate.matched()).toList()),
                    group.stream().filter(Row::guardAtBaselineMismatch).count()));
            for (String status : List.of("IMPROVED", "UNCHANGED", "WORSE", "UNRESOLVED")) {
                summary.append('\t').append(group.stream().filter(r -> r.comparison().equals(status)).count());
            }
            summary.append('\n');
        }
        Files.writeString(output.resolve("summary.tsv"), summary);
        System.out.print(summary);
    }

    private static double median(List<Integer> values) {
        var sorted = values.stream().sorted().toList();
        return (sorted.get((sorted.size() - 1) / 2) + sorted.get(sorted.size() / 2)) / 2.0;
    }
}
