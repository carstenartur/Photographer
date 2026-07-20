package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.reference.ReferenceImage;
import com.photographer.focusstack.cr3.reference.ReferenceImageLoader;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Fixed-panel D8 discriminator from issue #226. */
class ZeroContractedKDiscriminatorIT {
    private static final Path SAMPLES = Path.of(System.getProperty(
            "cr3.samples.dir", "src/test/resources/samples"));
    private static final Path OUTPUT = Path.of(System.getProperty(
            "cr3.research.output.dir", "target"))
            .resolve("zero-contracted-k-discriminator");

    @Test
    void compareZeroContractedRuleAgainstHybridOnFixedPanel() throws Exception {
        List<Path> fixtures = KAdaptationFixtureSet.require(SAMPLES);
        assertThat(fixtures).hasSize(KAdaptationFixtureSet.PANEL.size());
        Files.createDirectories(OUTPUT);

        List<ZeroContractedKReport.Fixture> results = new ArrayList<>();
        for (Path fixture : fixtures) {
            results.add(analyse(fixture));
        }
        ZeroContractedKReport.Verdict verdict = verdict(results);

        Files.writeString(OUTPUT.resolve("results.md"),
                ZeroContractedKReport.markdown(results, verdict));
        Files.writeString(OUTPUT.resolve("results.tsv"),
                ZeroContractedKReport.tsv(results));
        Files.writeString(OUTPUT.resolve("results.json"),
                ZeroContractedKReport.json(results, verdict));

        assertThat(results).hasSize(KAdaptationFixtureSet.PANEL.size());
        assertThat(OUTPUT.resolve("results.md")).exists();
        assertThat(OUTPUT.resolve("results.tsv")).exists();
        assertThat(OUTPUT.resolve("results.json")).exists();
    }

    private static ZeroContractedKReport.Fixture analyse(Path fixture) throws Exception {
        ReferenceImage reference = ReferenceImageLoader.load(
                KAdaptationFixtureSet.referenceTiff(fixture));
        KAdaptationFixtureSet.Structure structure =
                KAdaptationFixtureSet.structure(fixture);
        assertThat(structure.cmp1().isLossless()).isTrue();
        assertThat(reference.width()).isEqualTo(structure.width());
        assertThat(reference.height()).isEqualTo(structure.height());

        KAdaptationPanelDecode.ImageResult hybrid = KAdaptationPanelDecode.decode(
                fixture, structure.width(), structure.height(),
                ZeroContractedKDiscriminatorIT::decodeHybridPlane);
        KAdaptationPanelDecode.ImageResult d8 = KAdaptationPanelDecode.decode(
                fixture, structure.width(), structure.height(),
                ZeroContractedKDiscriminatorIT::decodeD8Plane);

        return new ZeroContractedKReport.Fixture(
                KAdaptationFixtureSet.name(SAMPLES, fixture),
                KAdaptationPanelMetrics.evaluate(
                        reference, hybrid, structure.cmp1(), structure.iad1()),
                KAdaptationPanelMetrics.evaluate(
                        reference, d8, structure.cmp1(), structure.iad1()));
    }

    private static KAdaptationPanelDecode.PlaneResult decodeHybridPlane(
            byte[] payload, int width, int height, int bitDepth) {
        CrxNormativeLosslessCandidate.Options options =
                new CrxNormativeLosslessCandidate.Options();
        options.kInit = 1;
        options.resetKPerRow = true;
        options.unaryOnesUntilZero = true;
        options.signBitMapping = true;
        options.escapeAtUnary15 = false;
        options.firstPixelSeed = 0;
        options.escapeBitWidth = bitDepth;
        options.kAdaptation =
                CrxNormativeLosslessCandidate.KAdaptation.HYBRID_ACCUMULATOR;
        CrxNormativeLosslessCandidate.Result result =
                CrxNormativeLosslessCandidate.decodePlane(
                        payload, width, height, bitDepth, options);
        return new KAdaptationPanelDecode.PlaneResult(
                result.pixels, result.symbolsDecoded, result.streamExhausted);
    }

    private static KAdaptationPanelDecode.PlaneResult decodeD8Plane(
            byte[] payload, int width, int height, int bitDepth) {
        ZeroContractedBracketDecoder.Result result =
                ZeroContractedBracketDecoder.decode(
                        payload, width, height, bitDepth);
        return new KAdaptationPanelDecode.PlaneResult(
                result.pixels(), result.symbolsDecoded(), result.exhausted());
    }

    private static ZeroContractedKReport.Verdict verdict(
            List<ZeroContractedKReport.Fixture> results) {
        int successes = 0;
        boolean completionOkay = true;
        for (ZeroContractedKReport.Fixture result : results) {
            KAdaptationPanelMetrics.Result hybrid = result.hybrid();
            KAdaptationPanelMetrics.Result d8 = result.d8();
            if (KAdaptationFixtureSet.DISCRIMINATORS.contains(result.name())
                    && d8.global().mae() < hybrid.global().mae()
                    && finiteImprovement(
                    d8.opticalBlackMae(), hybrid.opticalBlackMae())) {
                successes++;
            }
            if (d8.completion() + 1e-12 < hybrid.completion()
                    || (d8.exhausted() && !hybrid.exhausted())) {
                completionOkay = false;
            }
        }

        double hybridMae = median(results.stream()
                .map(result -> result.hybrid().global().mae()).toList());
        double d8Mae = median(results.stream()
                .map(result -> result.d8().global().mae()).toList());
        double hybridBoundary = medianFinite(results.stream()
                .map(result -> result.hybrid().boundaryMae()).toList());
        double d8Boundary = medianFinite(results.stream()
                .map(result -> result.d8().boundaryMae()).toList());
        double hybridSaturation = median(results.stream()
                .map(result -> result.hybrid().saturation()).toList());
        double d8Saturation = median(results.stream()
                .map(result -> result.d8().saturation()).toList());

        boolean corpusGate = d8Mae <= hybridMae
                && (Double.isNaN(hybridBoundary)
                || d8Boundary <= hybridBoundary)
                && d8Saturation <= hybridSaturation;
        String state = successes >= 2 && corpusGate && completionOkay
                ? "SUPPORTED"
                : successes >= 1 || d8Mae < hybridMae
                ? "PARTIAL"
                : "REJECTED";

        return new ZeroContractedKReport.Verdict(
                state, successes, hybridMae, d8Mae,
                hybridBoundary, d8Boundary,
                hybridSaturation, d8Saturation,
                completionOkay);
    }

    private static boolean finiteImprovement(double candidate, double baseline) {
        return Double.isFinite(candidate)
                && Double.isFinite(baseline)
                && candidate < baseline;
    }

    private static double median(List<Double> values) {
        double[] sorted = values.stream()
                .mapToDouble(Double::doubleValue)
                .sorted()
                .toArray();
        if (sorted.length == 0) {
            return Double.NaN;
        }
        int middle = sorted.length / 2;
        return sorted.length % 2 == 0
                ? (sorted[middle - 1] + sorted[middle]) / 2.0
                : sorted[middle];
    }

    private static double medianFinite(List<Double> values) {
        return median(values.stream().filter(Double::isFinite).toList());
    }
}
