package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.raw.codec.SubbandExtractor;
import com.photographer.focusstack.cr3.reference.DeterministicResultWriter;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
        List<Path> fixtures = requireFixedPanel(SAMPLES);
        assertThat(fixtures).hasSize(KAdaptationFixtureSet.PANEL.size());

        List<CrxSubbandTopologyReport.Row> rows = new ArrayList<>();
        for (Path fixture : fixtures) {
            rows.addAll(inventory(fixture));
        }
        rows.sort(Comparator.comparing(CrxSubbandTopologyReport.Row::fixture)
                .thenComparingInt(CrxSubbandTopologyReport.Row::recordOrder));

        DeterministicResultWriter.ResultBundle bundle =
                DeterministicResultWriter.writeBundle(
                        OUTPUT,
                        CrxSubbandTopologyReport.markdown(rows),
                        CrxSubbandTopologyReport.tsv(rows),
                        CrxSubbandTopologyReport.json(rows));

        assertThat(rows).isNotEmpty();
        assertThat(rows.stream().map(CrxSubbandTopologyReport.Row::fixture).distinct())
                .hasSize(KAdaptationFixtureSet.PANEL.size());
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.declaredWaveletLevels()).isZero();
            assertThat(row.subband()).isZero();
            assertThat(row.payloadOffset()).isGreaterThanOrEqualTo(0L);
            assertThat(row.payloadSize()).isPositive();
            assertThat(row.payloadEndExclusive())
                    .isEqualTo(row.payloadOffset() + row.payloadSize());
            assertThat(row.dimensionParentCandidates()).isEmpty();
            assertThat(row.causalAssessment())
                    .isEqualTo("NOT_APPLICABLE_WAVELET_LEVELS_0");
        });
        assertThat(bundle.markdown()).exists();
        assertThat(bundle.tsv()).exists();
        assertThat(bundle.json()).exists();
    }

    private static List<Path> requireFixedPanel(Path samples) {
        List<Path> fixtures = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String name : KAdaptationFixtureSet.PANEL) {
            Path fixture = samples.resolve(name);
            if (Files.isRegularFile(fixture)) {
                fixtures.add(fixture);
            } else {
                missing.add(name);
            }
        }
        assertThat(missing)
                .as("D13 requires the complete fixed CR3 panel under %s", samples)
                .isEmpty();
        return List.copyOf(fixtures);
    }

    private static List<CrxSubbandTopologyReport.Row> inventory(Path fixture)
            throws Exception {
        List<SubbandExtractor.SubbandRef> source =
                SubbandExtractor.list(fixture.toFile());
        assertThat(source).as("subbands for %s", fixture).isNotEmpty();

        List<CrxSubbandTopologyReport.Row> rows = new ArrayList<>();
        String fixtureName = KAdaptationFixtureSet.name(SAMPLES, fixture);
        for (int recordOrder = 0; recordOrder < source.size(); recordOrder++) {
            SubbandExtractor.SubbandRef ref = source.get(recordOrder);
            rows.add(new CrxSubbandTopologyReport.Row(
                    fixtureName,
                    family(fixtureName),
                    recordOrder,
                    ref.tileIndex(),
                    ref.tileSequence(),
                    ref.planeIndex(),
                    ref.waveletLevels(),
                    ref.subbandIndex(),
                    ref.planeWidth(),
                    ref.planeHeight(),
                    ref.bitDepth(),
                    ref.dataOffsetInFile(),
                    ref.dataSize(),
                    Math.addExact(ref.dataOffsetInFile(), ref.dataSize()),
                    dimensionParentCandidates(ref, source),
                    causalAssessment(ref),
                    continuity(recordOrder, ref, source),
                    boundaryFlags(ref, source)));
        }
        return rows;
    }

    /**
     * The current extractor exposes the undecomposed plane grid for lossy data.
     * It therefore cannot derive a same-position wavelet parent from dimensions.
     * The fixed D13 panel is lossless, so no deeper-level relation exists there.
     */
    private static String dimensionParentCandidates(
            SubbandExtractor.SubbandRef ref,
            List<SubbandExtractor.SubbandRef> source) {
        if (ref.waveletLevels() == 0) {
            return "";
        }
        boolean hasSamePlanePeer = source.stream()
                .anyMatch(other -> other != ref
                        && other.tileSequence() == ref.tileSequence()
                        && other.planeIndex() == ref.planeIndex());
        return hasSamePlanePeer
                ? "UNRESOLVED_UNDECOMPOSED_PLANE_DIMENSIONS"
                : "";
    }

    private static String causalAssessment(SubbandExtractor.SubbandRef ref) {
        return ref.waveletLevels() == 0
                ? "NOT_APPLICABLE_WAVELET_LEVELS_0"
                : "UNRESOLVED_NO_PER_SUBBAND_GEOMETRY_OR_ROLE";
    }

    private static String continuity(
            int recordOrder,
            SubbandExtractor.SubbandRef current,
            List<SubbandExtractor.SubbandRef> source) {
        if (recordOrder == 0) {
            return "FIRST_RECORD";
        }
        SubbandExtractor.SubbandRef previous = source.get(recordOrder - 1);
        long previousEnd = Math.addExact(
                previous.dataOffsetInFile(), previous.dataSize());
        long delta = current.dataOffsetInFile() - previousEnd;
        if (delta == 0) {
            return "CONTIGUOUS_WITH_PREVIOUS";
        }
        return delta > 0
                ? "GAP_FROM_PREVIOUS_" + delta
                : "OVERLAP_WITH_PREVIOUS_" + -delta;
    }

    private static String boundaryFlags(
            SubbandExtractor.SubbandRef ref,
            List<SubbandExtractor.SubbandRef> source) {
        List<String> flags = new ArrayList<>();
        int minTile = source.stream()
                .mapToInt(SubbandExtractor.SubbandRef::tileSequence).min().orElse(0);
        int maxTile = source.stream()
                .mapToInt(SubbandExtractor.SubbandRef::tileSequence).max().orElse(0);
        int maxPlane = source.stream()
                .filter(other -> other.tileSequence() == ref.tileSequence())
                .mapToInt(SubbandExtractor.SubbandRef::planeIndex).max().orElse(0);
        int maxSubband = source.stream()
                .filter(other -> other.tileSequence() == ref.tileSequence())
                .filter(other -> other.planeIndex() == ref.planeIndex())
                .mapToInt(SubbandExtractor.SubbandRef::subbandIndex).max().orElse(0);

        if (ref.tileSequence() == minTile) flags.add("FIRST_TILE");
        if (ref.tileSequence() == maxTile) flags.add("LAST_TILE");
        if (ref.planeIndex() == 0) flags.add("FIRST_PLANE");
        if (ref.planeIndex() == maxPlane) flags.add("LAST_PLANE");
        if (ref.subbandIndex() == 0) flags.add("FIRST_SUBBAND");
        if (ref.subbandIndex() == maxSubband) flags.add("LAST_SUBBAND");
        if (ref.waveletLevels() == 0) flags.add("LOSSLESS_SINGLE_SUBBAND");
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
