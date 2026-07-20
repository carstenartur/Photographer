package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.reference.ReferenceImage;
import com.photographer.focusstack.cr3.reference.ReferenceImageLoader;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Fixed-panel D12 discriminator from issue #236. */
class ExactBracketKDiscriminatorIT {
    private static final Path SAMPLES = Path.of(System.getProperty(
            "cr3.samples.dir", "src/test/resources/samples"));
    private static final Path OUTPUT = Path.of(System.getProperty(
            "cr3.research.output.dir", "target"))
            .resolve("exact-code-length-k-discriminator");

    @Test
    void compareExactBracketAgainstD8OnFixedPanel() throws Exception {
        List<Path> fixtures = KAdaptationFixtureSet.require(SAMPLES);
        assertThat(fixtures).hasSize(KAdaptationFixtureSet.PANEL.size());
        Files.createDirectories(OUTPUT);

        List<ExactBracketKReport.Fixture> results = new ArrayList<>();
        for (Path fixture : fixtures) {
            results.add(analyse(fixture));
        }
        ExactBracketKReport.Verdict verdict = ExactBracketKReport.verdict(results);

        Files.writeString(OUTPUT.resolve("results.md"),
                ExactBracketKReport.markdown(results, verdict));
        Files.writeString(OUTPUT.resolve("results.tsv"),
                ExactBracketKReport.tsv(results));
        Files.writeString(OUTPUT.resolve("results.json"),
                ExactBracketKReport.json(results, verdict));

        assertThat(results).hasSize(KAdaptationFixtureSet.PANEL.size());
        assertThat(results.stream()
                .mapToLong(ExactBracketKReport.Fixture::bracketDifferences)
                .sum()).isPositive();
        assertThat(OUTPUT.resolve("results.md")).exists();
        assertThat(OUTPUT.resolve("results.tsv")).exists();
        assertThat(OUTPUT.resolve("results.json")).exists();
    }

    private static ExactBracketKReport.Fixture analyse(Path fixture)
            throws Exception {
        ReferenceImage reference = ReferenceImageLoader.load(
                KAdaptationFixtureSet.referenceTiff(fixture));
        KAdaptationFixtureSet.Structure structure =
                KAdaptationFixtureSet.structure(fixture);
        assertThat(structure.cmp1().isLossless()).isTrue();
        assertThat(reference.width()).isEqualTo(structure.width());
        assertThat(reference.height()).isEqualTo(structure.height());

        KAdaptationPanelDecode.ImageResult d8 = KAdaptationPanelDecode.decode(
                fixture, structure.width(), structure.height(),
                ExactBracketKDiscriminatorIT::decodeD8Plane);
        long[] differences = {0};
        KAdaptationPanelDecode.ImageResult exact = KAdaptationPanelDecode.decode(
                fixture, structure.width(), structure.height(),
                (payload, width, height, bitDepth) -> {
                    ExactCodeLengthBracketDecoder.Result result =
                            ExactCodeLengthBracketDecoder.decode(
                                    payload, width, height, bitDepth);
                    differences[0] += result.bracketDifferences();
                    return new KAdaptationPanelDecode.PlaneResult(
                            result.pixels(), result.symbolsDecoded(),
                            result.exhausted());
                });
        StreamComparison streamComparison = compareStreams(d8, exact);

        return new ExactBracketKReport.Fixture(
                KAdaptationFixtureSet.name(SAMPLES, fixture),
                KAdaptationPanelMetrics.evaluate(
                        reference, d8, structure.cmp1(), structure.iad1()),
                KAdaptationPanelMetrics.evaluate(
                        reference, exact, structure.cmp1(), structure.iad1()),
                differences[0],
                streamComparison.regressed(),
                streamComparison.newlyExhausted(),
                streamComparison.recovered());
    }

    private static StreamComparison compareStreams(
            KAdaptationPanelDecode.ImageResult d8,
            KAdaptationPanelDecode.ImageResult exact) {
        assertThat(exact.streams()).hasSameSizeAs(d8.streams());
        int regressed = 0;
        int newlyExhausted = 0;
        int recovered = 0;
        for (int index = 0; index < d8.streams().size(); index++) {
            KAdaptationPanelDecode.StreamResult baseline = d8.streams().get(index);
            KAdaptationPanelDecode.StreamResult candidate = exact.streams().get(index);
            assertThat(candidate.tileSequence()).isEqualTo(baseline.tileSequence());
            assertThat(candidate.planeIndex()).isEqualTo(baseline.planeIndex());
            assertThat(candidate.capacity()).isEqualTo(baseline.capacity());

            if (candidate.completion() + 1e-12 < baseline.completion()) {
                regressed++;
            }
            if (candidate.exhausted() && !baseline.exhausted()) {
                newlyExhausted++;
            }
            if (candidate.completion() > baseline.completion() + 1e-12
                    || (!candidate.exhausted() && baseline.exhausted())) {
                recovered++;
            }
        }
        return new StreamComparison(regressed, newlyExhausted, recovered);
    }

    private static KAdaptationPanelDecode.PlaneResult decodeD8Plane(
            byte[] payload, int width, int height, int bitDepth) {
        ZeroContractedBracketDecoder.Result result =
                ZeroContractedBracketDecoder.decode(
                        payload, width, height, bitDepth);
        return new KAdaptationPanelDecode.PlaneResult(
                result.pixels(), result.symbolsDecoded(), result.exhausted());
    }

    private record StreamComparison(int regressed,
                                    int newlyExhausted,
                                    int recovered) {
    }
}
