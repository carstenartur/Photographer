package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.raw.codec.SubbandExtractor;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.photographer.focusstack.cr3.research.KAdaptationTraceReport.Classification;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class KAdaptationFirstDivergenceIT {
    private static final Path SAMPLES = Path.of(System.getProperty(
            "cr3.samples.dir", "src/test/resources/samples"));
    private static final Path OUTPUT = Path.of(System.getProperty(
            "cr3.research.output.dir", "target")).resolve("k-adaptation-first-divergence");
    private static final int LIMIT = 8192;
    private static final List<String> FIXTURES = List.of(
            "eosr-iso-mid.cr3",
            "research/g5xm2-asis-iso200-ss130-ff2p5-01.cr3");

    @Test
    void locateFirstDivergence() throws Exception {
        Files.createDirectories(OUTPUT);
        List<KAdaptationTraceReport.Result> results = new ArrayList<>();
        for (String name : FIXTURES) {
            Path fixture = SAMPLES.resolve(name);
            assumeTrue(Files.isRegularFile(fixture), () -> "Missing fixture " + fixture);
            assumeTrue(Files.isRegularFile(referenceTiff(fixture)),
                    () -> "Missing reference for " + fixture);
            results.add(trace(name, fixture));
        }
        assertThat(results).allSatisfy(r -> {
            assertThat(r.found()).as(r.fixture()).isTrue();
            assertThat(r.context()).isNotEmpty();
        });
        Files.writeString(OUTPUT.resolve("results.md"), KAdaptationTraceReport.markdown(results));
        Files.writeString(OUTPUT.resolve("results.json"), KAdaptationTraceReport.json(results));
    }

    private static KAdaptationTraceReport.Result trace(String name, Path fixture) throws Exception {
        List<SubbandExtractor.SubbandRef> refs = SubbandExtractor.list(fixture.toFile()).stream()
                .filter(r -> r.waveletLevels() == 0 && r.subbandIndex() == 0)
                .sorted(Comparator.comparingInt(SubbandExtractor.SubbandRef::tileSequence)
                        .thenComparingInt(SubbandExtractor.SubbandRef::planeIndex)).toList();
        for (SubbandExtractor.SubbandRef ref : refs) {
            byte[] payload = SubbandExtractor.read(fixture.toFile(), ref);
            Collector hybrid = decode(payload, ref,
                    CrxNormativeLosslessCandidate.KAdaptation.HYBRID_ACCUMULATOR);
            Collector bracket = decode(payload, ref,
                    CrxNormativeLosslessCandidate.KAdaptation.SYMBOL_BRACKET);
            int index = firstDifference(hybrid.values, bracket.values);
            if (index >= 0) {
                int common = Math.min(hybrid.values.size(), bracket.values.size());
                int from = Math.max(0, index - 2);
                int to = Math.min(common, index + 17);
                List<KAdaptationTraceReport.Pair> context = new ArrayList<>();
                for (int i = from; i < to; i++) {
                    context.add(new KAdaptationTraceReport.Pair(
                            i, hybrid.values.get(i), bracket.values.get(i)));
                }
                Classification type = index < common
                        ? classify(hybrid.values.get(index), bracket.values.get(index))
                        : Classification.BIT_OR_STRUCTURE_POSITION;
                return new KAdaptationTraceReport.Result(name, true, type,
                        ref.tileSequence(), ref.planeIndex(), index, context,
                        hybrid.truncated, bracket.truncated);
            }
        }
        return new KAdaptationTraceReport.Result(name, false,
                Classification.NO_DIVERGENCE_WITHIN_BOUND,
                -1, -1, -1, List.of(), false, false);
    }

    private static Collector decode(byte[] payload, SubbandExtractor.SubbandRef ref,
                                    CrxNormativeLosslessCandidate.KAdaptation adaptation) {
        Collector collector = new Collector();
        CrxNormativeLosslessCandidate.Options o = new CrxNormativeLosslessCandidate.Options();
        o.kInit = 1;
        o.resetKPerRow = true;
        o.unaryOnesUntilZero = true;
        o.signBitMapping = true;
        o.escapeAtUnary15 = false;
        o.firstPixelSeed = 0;
        o.escapeBitWidth = ref.bitDepth();
        o.kAdaptation = adaptation;
        o.traceSink = collector;
        CrxNormativeLosslessCandidate.decodePlane(
                payload, ref.planeWidth(), ref.planeHeight(), ref.bitDepth(), o);
        return collector;
    }

    private static int firstDifference(List<?> a, List<?> b) {
        int common = Math.min(a.size(), b.size());
        for (int i = 0; i < common; i++) if (!a.get(i).equals(b.get(i))) return i;
        return a.size() == b.size() ? -1 : common;
    }

    private static Classification classify(CrxNormativeLosslessCandidate.SymbolTrace a,
                                           CrxNormativeLosslessCandidate.SymbolTrace b) {
        boolean same = a.ordinal() == b.ordinal() && a.x() == b.x() && a.y() == b.y()
                && a.bitStart() == b.bitStart() && a.bitEnd() == b.bitEnd()
                && a.kBefore() == b.kBefore() && a.quotient() == b.quotient()
                && a.remainder() == b.remainder() && a.unsignedSymbol() == b.unsignedSymbol()
                && a.signedResidual() == b.signedResidual() && a.predictor() == b.predictor()
                && a.reconstructed() == b.reconstructed();
        if (same && a.kAfter() != b.kAfter()) return Classification.K_STATE_BEFORE_DIFFERENT_BITS;
        if (a.x() == 0 && b.x() == 0 && a.kBefore() != b.kBefore()) return Classification.RESET_STATE;
        if (a.kBefore() == b.kBefore() && a.bitStart() == b.bitStart()
                && (a.quotient() != b.quotient() || a.remainder() != b.remainder()
                || a.unsignedSymbol() != b.unsignedSymbol())) return Classification.SYMBOL_INTERPRETATION;
        if (a.bitStart() != b.bitStart() || a.x() != b.x() || a.y() != b.y())
            return Classification.BIT_OR_STRUCTURE_POSITION;
        return Classification.OTHER_STATE;
    }

    private static Path referenceTiff(Path fixture) {
        return fixture.resolveSibling(fixture.getFileName().toString()
                .replaceAll("(?i)\\.cr3$", ".reference.tiff"));
    }

    private static final class Collector implements CrxNormativeLosslessCandidate.TraceSink {
        private final List<CrxNormativeLosslessCandidate.SymbolTrace> values = new ArrayList<>();
        private boolean truncated;
        @Override
        public boolean onSymbol(CrxNormativeLosslessCandidate.SymbolTrace trace) {
            if (values.size() >= LIMIT) { truncated = true; return false; }
            values.add(trace);
            truncated = values.size() == LIMIT;
            return !truncated;
        }
    }
}
