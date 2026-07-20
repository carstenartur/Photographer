package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.raw.codec.SubbandExtractor;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** D13 observation-only topology inventory from issue #238. */
class CrxSubbandTopologyIT {
    private static final Path SAMPLES = Path.of(System.getProperty(
            "cr3.samples.dir", "src/test/resources/samples"));
    private static final Path OUTPUT = Path.of(System.getProperty(
            "cr3.research.output.dir", "target"))
            .resolve("crx-subband-topology");

    @Test
    void inventoryFixedPanelTopologyWithoutSelectingByImageQuality() throws Exception {
        List<Path> fixtures = KAdaptationFixtureSet.require(SAMPLES);
        assertThat(fixtures).hasSize(KAdaptationFixtureSet.PANEL.size());
        Files.createDirectories(OUTPUT);

        List<CrxSubbandTopologyReport.Row> rows = new ArrayList<>();
        for (Path fixture : fixtures) {
            rows.addAll(inventory(fixture));
        }
        rows.sort(Comparator.comparing(CrxSubbandTopologyReport.Row::fixture)
                .thenComparingInt(CrxSubbandTopologyReport.Row::recordOrder));

        Files.writeString(OUTPUT.resolve("results.md"),
                CrxSubbandTopologyReport.markdown(rows));
        Files.writeString(OUTPUT.resolve("results.tsv"),
                CrxSubbandTopologyReport.tsv(rows));
        Files.writeString(OUTPUT.resolve("results.json"),
                CrxSubbandTopologyReport.json(rows));

        assertThat(rows).isNotEmpty();
        assertThat(rows.stream().map(CrxSubbandTopologyReport.Row::fixture).distinct())
                .hasSize(KAdaptationFixtureSet.PANEL.size());
        assertThat(OUTPUT.resolve("results.md")).exists();
        assertThat(OUTPUT.resolve("results.tsv")).exists();
        assertThat(OUTPUT.resolve("results.json")).exists();
    }

    private static List<CrxSubbandTopologyReport.Row> inventory(Path fixture)
            throws Exception {
        List<SubbandExtractor.SubbandRef> source =
                SubbandExtractor.list(fixture.toFile());
        assertThat(source).as("subbands for %s", fixture).isNotEmpty();
        Map<SubbandExtractor.SubbandRef, Integer> recordOrder = new HashMap<>();
        for (int index = 0; index < source.size(); index++) {
            recordOrder.put(source.get(index), index);
        }

        List<SubbandExtractor.SubbandRef> deterministic = source.stream()
                .sorted(Comparator.comparingInt(SubbandExtractor.SubbandRef::tileSequence)
                        .thenComparingInt(SubbandExtractor.SubbandRef::planeIndex)
                        .thenComparingInt(SubbandExtractor.SubbandRef::waveletLevels)
                        .thenComparingInt(SubbandExtractor.SubbandRef::subbandIndex)
                        .thenComparingInt(recordOrder::get))
                .toList();
        List<CrxSubbandTopologyReport.Row> rows = new ArrayList<>();
        String fixtureName = KAdaptationFixtureSet.name(SAMPLES, fixture);
        for (SubbandExtractor.SubbandRef ref : deterministic) {
            List<SubbandExtractor.SubbandRef> candidates = deterministic.stream()
                    .filter(other -> other != ref)
                    .filter(other -> other.tileSequence() == ref.tileSequence())
                    .filter(other -> other.planeIndex() == ref.planeIndex())
                    .filter(other -> dimensionCompatible(ref, other))
                    .toList();
            String dimensionCandidates = candidateIds(candidates, recordOrder);
            String causalCandidates = candidateIds(candidates.stream()
                    .filter(other -> recordOrder.get(other) < recordOrder.get(ref))
                    .toList(), recordOrder);
            int payloadBytes = SubbandExtractor.read(fixture.toFile(), ref).length;
            rows.add(new CrxSubbandTopologyReport.Row(
                    fixtureName, family(fixtureName), recordOrder.get(ref),
                    ref.tileSequence(), ref.planeIndex(), ref.waveletLevels(),
                    ref.subbandIndex(), ref.planeWidth(), ref.planeHeight(),
                    ref.bitDepth(), payloadBytes,
                    dimensionCandidates, causalCandidates,
                    boundaryFlags(ref, source)));
        }
        return rows;
    }

    private static boolean dimensionCompatible(
            SubbandExtractor.SubbandRef current,
            SubbandExtractor.SubbandRef candidate) {
        int widthDifference = Math.abs(current.planeWidth() - candidate.planeWidth() * 2);
        int heightDifference = Math.abs(current.planeHeight() - candidate.planeHeight() * 2);
        boolean candidateIsDeeper = candidate.waveletLevels()
                == current.waveletLevels() + 1;
        return candidateIsDeeper && widthDifference <= 1 && heightDifference <= 1;
    }

    private static String candidateIds(
            List<SubbandExtractor.SubbandRef> candidates,
            Map<SubbandExtractor.SubbandRef, Integer> recordOrder) {
        return candidates.stream()
                .sorted(Comparator.comparingInt(recordOrder::get))
                .map(ref -> "r" + recordOrder.get(ref)
                        + ":t" + ref.tileSequence()
                        + ":p" + ref.planeIndex()
                        + ":l" + ref.waveletLevels()
                        + ":s" + ref.subbandIndex())
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static String boundaryFlags(
            SubbandExtractor.SubbandRef ref,
            List<SubbandExtractor.SubbandRef> source) {
        List<String> flags = new ArrayList<>();
        if (ref.tileSequence() == source.stream()
                .mapToInt(SubbandExtractor.SubbandRef::tileSequence).min().orElse(0)) {
            flags.add("FIRST_TILE");
        }
        if (ref.tileSequence() == source.stream()
                .mapToInt(SubbandExtractor.SubbandRef::tileSequence).max().orElse(0)) {
            flags.add("LAST_TILE");
        }
        if (ref.planeIndex() == 0) {
            flags.add("FIRST_PLANE");
        }
        if (ref.subbandIndex() == 0) {
            flags.add("FIRST_SUBBAND");
        }
        if (ref.waveletLevels() == 0) {
            flags.add("LEVEL_ZERO");
        }
        return String.join(",", flags);
    }

    private static String family(String fixture) {
        if (fixture.startsWith("eosr-")) {
            return "EOS_R";
        }
        if (fixture.startsWith("research/g5xm2-")) {
            return "G5_X_MARK_II_RESEARCH";
        }
        return "SAMPLE_SERIES";
    }
}
