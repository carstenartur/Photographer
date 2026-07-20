package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.raw.codec.SubbandExtractor;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.photographer.focusstack.cr3.research.KAdaptationNonZeroTraceReport.Classification;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** D9 observation-only first non-zero divergence trace from issue #230. */
class KAdaptationNonZeroDivergenceIT {
    private static final Path SAMPLES = Path.of(System.getProperty(
            "cr3.samples.dir", "src/test/resources/samples"));
    private static final Path OUTPUT = Path.of(System.getProperty(
            "cr3.research.output.dir", "target"))
            .resolve("k-adaptation-nonzero-divergence");
    private static final int TRACE_LIMIT = 65_536;
    private static final int BEFORE = 2;
    private static final int AFTER = 16;
    private static final List<String> FIXTURES = List.of(
            "eosr-iso-low.cr3",
            "research/g5xm2-asis-iso200-ss130-ff2p5-01.cr3",
            "sample-iso-high.cr3");

    @Test
    void locateFirstNonZeroDivergence() throws Exception {
        Files.createDirectories(OUTPUT);
        List<KAdaptationNonZeroTraceReport.Result> results = new ArrayList<>();
        for (String name : FIXTURES) {
            Path fixture = SAMPLES.resolve(name);
            assumeTrue(Files.isRegularFile(fixture),
                    () -> "Missing fixture " + fixture);
            assumeTrue(Files.isRegularFile(KAdaptationFixtureSet.referenceTiff(fixture)),
                    () -> "Missing external reference for " + fixture);
            results.add(trace(name, fixture));
        }

        assertThat(results).hasSize(FIXTURES.size());
        assertThat(results).allSatisfy(result ->
                assertThat(result.classification()).isNotNull());
        Files.writeString(OUTPUT.resolve("results.md"),
                KAdaptationNonZeroTraceReport.markdown(results));
        Files.writeString(OUTPUT.resolve("results.json"),
                KAdaptationNonZeroTraceReport.json(results));
        assertThat(OUTPUT.resolve("results.md")).exists();
        assertThat(OUTPUT.resolve("results.json")).exists();
    }

    private static KAdaptationNonZeroTraceReport.Result trace(
            String name, Path fixture) throws Exception {
        List<SubbandExtractor.SubbandRef> refs =
                SubbandExtractor.list(fixture.toFile()).stream()
                        .filter(ref -> ref.waveletLevels() == 0
                                && ref.subbandIndex() == 0)
                        .sorted(Comparator.comparingInt(
                                        SubbandExtractor.SubbandRef::tileSequence)
                                .thenComparingInt(
                                        SubbandExtractor.SubbandRef::planeIndex))
                        .toList();
        assertThat(refs).as(name).isNotEmpty();

        boolean anyHybridExhausted = false;
        boolean anyD8Exhausted = false;
        boolean anyTruncated = false;

        for (SubbandExtractor.SubbandRef ref : refs) {
            byte[] payload = SubbandExtractor.read(fixture.toFile(), ref);
            KAdaptationStateTracer.Result hybrid =
                    KAdaptationStateTracer.decode(
                            payload, ref.planeWidth(), ref.planeHeight(),
                            ref.bitDepth(), KAdaptationStateTracer.Rule.HYBRID,
                            TRACE_LIMIT);
            KAdaptationStateTracer.Result d8 =
                    KAdaptationStateTracer.decode(
                            payload, ref.planeWidth(), ref.planeHeight(),
                            ref.bitDepth(),
                            KAdaptationStateTracer.Rule.D8_ZERO_CONTRACT,
                            TRACE_LIMIT);

            assertMatchesExistingDecoders(payload, ref, hybrid, d8);
            anyHybridExhausted |= hybrid.exhausted();
            anyD8Exhausted |= d8.exhausted();
            anyTruncated |= hybrid.traceTruncated() || d8.traceTruncated();

            int index = firstNonZeroDivergence(
                    hybrid.traces(), d8.traces());
            if (index >= 0) {
                int common = Math.min(
                        hybrid.traces().size(), d8.traces().size());
                int from = Math.max(0, index - BEFORE);
                int to = Math.min(common, index + AFTER + 1);
                List<KAdaptationNonZeroTraceReport.Pair> context =
                        new ArrayList<>();
                for (int i = from; i < to; i++) {
                    context.add(new KAdaptationNonZeroTraceReport.Pair(
                            i, hybrid.traces().get(i), d8.traces().get(i)));
                }
                Classification classification = index < common
                        ? classify(hybrid.traces().get(index),
                        d8.traces().get(index))
                        : Classification.EXHAUSTION_BEFORE_NON_ZERO_DIVERGENCE;
                return new KAdaptationNonZeroTraceReport.Result(
                        name, true, classification,
                        ref.tileSequence(), ref.planeIndex(), index,
                        List.copyOf(context),
                        hybrid.traceTruncated(), d8.traceTruncated(),
                        hybrid.exhausted(), d8.exhausted());
            }
        }

        Classification classification =
                anyHybridExhausted != anyD8Exhausted
                        ? Classification.EXHAUSTION_BEFORE_NON_ZERO_DIVERGENCE
                        : Classification.NO_DIVERGENCE_WITHIN_BOUND;
        return new KAdaptationNonZeroTraceReport.Result(
                name, false, classification,
                -1, -1, -1, List.of(),
                anyTruncated, anyTruncated,
                anyHybridExhausted, anyD8Exhausted);
    }

    private static void assertMatchesExistingDecoders(
            byte[] payload,
            SubbandExtractor.SubbandRef ref,
            KAdaptationStateTracer.Result hybrid,
            KAdaptationStateTracer.Result d8) {
        CrxNormativeLosslessCandidate.Options options =
                new CrxNormativeLosslessCandidate.Options();
        options.kInit = 1;
        options.resetKPerRow = true;
        options.unaryOnesUntilZero = true;
        options.signBitMapping = true;
        options.escapeAtUnary15 = false;
        options.firstPixelSeed = 0;
        options.escapeBitWidth = ref.bitDepth();
        options.kAdaptation =
                CrxNormativeLosslessCandidate.KAdaptation.HYBRID_ACCUMULATOR;
        CrxNormativeLosslessCandidate.Result expectedHybrid =
                CrxNormativeLosslessCandidate.decodePlane(
                        payload, ref.planeWidth(), ref.planeHeight(),
                        ref.bitDepth(), options);
        ZeroContractedBracketDecoder.Result expectedD8 =
                ZeroContractedBracketDecoder.decode(
                        payload, ref.planeWidth(), ref.planeHeight(),
                        ref.bitDepth());

        assertThat(hybrid.pixels()).containsExactly(expectedHybrid.pixels);
        assertThat(hybrid.symbolsDecoded())
                .isEqualTo(expectedHybrid.symbolsDecoded);
        assertThat(hybrid.exhausted())
                .isEqualTo(expectedHybrid.streamExhausted);
        assertThat(d8.pixels()).containsExactly(expectedD8.pixels());
        assertThat(d8.symbolsDecoded())
                .isEqualTo(expectedD8.symbolsDecoded());
        assertThat(d8.exhausted()).isEqualTo(expectedD8.exhausted());
    }

    private static int firstNonZeroDivergence(
            List<KAdaptationStateTracer.Trace> hybrid,
            List<KAdaptationStateTracer.Trace> d8) {
        int common = Math.min(hybrid.size(), d8.size());
        for (int i = 0; i < common; i++) {
            KAdaptationStateTracer.Trace left = hybrid.get(i);
            KAdaptationStateTracer.Trace right = d8.get(i);
            boolean sameParsedState = left.ordinal() == right.ordinal()
                    && left.x() == right.x()
                    && left.y() == right.y()
                    && left.bitStart() == right.bitStart()
                    && left.bitEnd() == right.bitEnd()
                    && left.kBefore() == right.kBefore()
                    && left.quotient() == right.quotient()
                    && left.remainder() == right.remainder()
                    && left.unsignedSymbol() == right.unsignedSymbol()
                    && left.signedResidual() == right.signedResidual()
                    && left.predictor() == right.predictor()
                    && left.reconstructed() == right.reconstructed();

            // D9 deliberately observes the first non-zero divergence. Identically
            // parsed zero symbols are context even when their K-after decisions differ.
            if (left.unsignedSymbol() == 0
                    && right.unsignedSymbol() == 0
                    && sameParsedState) {
                continue;
            }

            boolean nonZero = left.unsignedSymbol() != 0
                    || right.unsignedSymbol() != 0;
            if (nonZero && (!sameParsedState
                    || left.kAfter() != right.kAfter())) {
                return i;
            }

            if (!sameParsedState || left.kAfter() != right.kAfter()) {
                return i;
            }
        }
        return hybrid.size() == d8.size() ? -1 : common;
    }

    private static Classification classify(
            KAdaptationStateTracer.Trace hybrid,
            KAdaptationStateTracer.Trace d8) {
        if (hybrid.x() != d8.x()
                || hybrid.y() != d8.y()
                || hybrid.kBefore() != d8.kBefore()) {
            return Classification.RESET_OR_STRUCTURE_SCOPE;
        }
        if (hybrid.bitStart() != d8.bitStart()
                || hybrid.bitEnd() != d8.bitEnd()
                || hybrid.quotient() != d8.quotient()
                || hybrid.remainder() != d8.remainder()
                || hybrid.unsignedSymbol() != d8.unsignedSymbol()) {
            return Classification.SAME_K_DIFFERENT_SYMBOL_OR_BITS;
        }
        if (hybrid.decision()
                == KAdaptationStateTracer.Decision.HYBRID_DECREASE
                && d8.decision() == KAdaptationStateTracer.Decision.HOLD) {
            return Classification.HYBRID_ACCUMULATOR_DECREASE_VS_D8_HOLD;
        }
        if (hybrid.decision() == KAdaptationStateTracer.Decision.HOLD
                && d8.decision()
                == KAdaptationStateTracer.Decision.BRACKET_INCREASE) {
            return Classification.HYBRID_HOLD_VS_D8_INCREASE;
        }
        if (hybrid.decision()
                == KAdaptationStateTracer.Decision.HYBRID_INCREASE
                && d8.kAfter() <= d8.kBefore()) {
            return Classification.HYBRID_INCREASE_VS_D8_HOLD_OR_DECREASE;
        }
        return Classification.OTHER_K_DECISION;
    }
}
