package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.raw.codec.SubbandExtractor;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/** D10 fixed-panel transition/outcome matrix from issue #232. */
class KTransitionMatrixIT {
    private static final Path SAMPLES = Path.of(System.getProperty(
            "cr3.samples.dir", "src/test/resources/samples"));
    private static final Path OUTPUT = Path.of(System.getProperty(
            "cr3.research.output.dir", "target"))
            .resolve("k-transition-outcome-matrix");
    private static final int SYMBOL_LIMIT = 16_384;

    @Test
    void correlateTransitionDisagreementsWithDurableD8Outcomes() throws Exception {
        List<Path> fixturePaths = KAdaptationFixtureSet.require(SAMPLES);
        Map<String, D8OutcomeTable.Outcome> outcomes =
                D8OutcomeTable.load(D8OutcomeTable.DEFAULT_PATH);
        assertThat(outcomes.keySet())
                .containsExactlyInAnyOrderElementsOf(KAdaptationFixtureSet.PANEL);

        List<KTransitionMatrixReport.Fixture> fixtures = new ArrayList<>();
        for (Path fixture : fixturePaths) {
            String name = KAdaptationFixtureSet.name(SAMPLES, fixture);
            fixtures.add(analyse(name, fixture, outcomes.get(name)));
        }
        List<KTransitionMatrixReport.Association> associations =
                KTransitionMatrixReport.associations(fixtures);

        Files.createDirectories(OUTPUT);
        Files.writeString(OUTPUT.resolve("results.md"),
                KTransitionMatrixReport.markdown(fixtures, associations));
        Files.writeString(OUTPUT.resolve("results.tsv"),
                KTransitionMatrixReport.tsv(fixtures));
        Files.writeString(OUTPUT.resolve("results.json"),
                KTransitionMatrixReport.json(fixtures, associations));

        assertThat(fixtures).hasSize(KAdaptationFixtureSet.PANEL.size());
        assertThat(OUTPUT.resolve("results.md")).exists();
        assertThat(OUTPUT.resolve("results.tsv")).exists();
        assertThat(OUTPUT.resolve("results.json")).exists();
    }

    private static KTransitionMatrixReport.Fixture analyse(
            String name, Path fixture, D8OutcomeTable.Outcome outcome) throws Exception {
        assertThat(outcome).as("D8 outcome for %s", name).isNotNull();
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

        Map<CellKey, Long> cellCounts = new TreeMap<>();
        Map<Integer, Long> kBins = new TreeMap<>();
        Map<String, Long> symbolBins = emptySymbolBins();
        List<KTransitionMatrixReport.Split> splits = new ArrayList<>();
        long alignedSymbols = 0;

        for (SubbandExtractor.SubbandRef ref : refs) {
            byte[] payload = SubbandExtractor.read(fixture.toFile(), ref);
            KAdaptationStateTracer.Result hybrid =
                    KAdaptationStateTracer.decodePrefix(
                            payload, ref.planeWidth(), ref.planeHeight(),
                            ref.bitDepth(), KAdaptationStateTracer.Rule.HYBRID,
                            SYMBOL_LIMIT);
            KAdaptationStateTracer.Result d8 =
                    KAdaptationStateTracer.decodePrefix(
                            payload, ref.planeWidth(), ref.planeHeight(),
                            ref.bitDepth(),
                            KAdaptationStateTracer.Rule.D8_ZERO_CONTRACT,
                            SYMBOL_LIMIT);

            int common = Math.min(hybrid.traces().size(), d8.traces().size());
            for (int index = 0; index < common; index++) {
                KAdaptationStateTracer.Trace left = hybrid.traces().get(index);
                KAdaptationStateTracer.Trace right = d8.traces().get(index);
                if (!sameParsedState(left, right)) {
                    splits.add(new KTransitionMatrixReport.Split(
                            ref.tileSequence(), ref.planeIndex(), index,
                            left.bitStart(), right.bitStart(), splitReason(left, right)));
                    break;
                }

                KTransitionMatrixReport.TransitionClass transition =
                        transition(left.decision(), right.decision());
                CellKey key = new CellKey(
                        ref.tileSequence(), ref.planeIndex(), transition,
                        left.unsignedSymbol() == 0);
                cellCounts.merge(key, 1L, Long::sum);
                kBins.merge(left.kBefore(), 1L, Long::sum);
                symbolBins.merge(symbolBin(left.unsignedSymbol()), 1L, Long::sum);
                alignedSymbols++;
            }
            if (hybrid.traces().size() != d8.traces().size()
                    && common < SYMBOL_LIMIT) {
                splits.add(new KTransitionMatrixReport.Split(
                        ref.tileSequence(), ref.planeIndex(), common,
                        common < hybrid.traces().size()
                                ? hybrid.traces().get(common).bitStart() : -1,
                        common < d8.traces().size()
                                ? d8.traces().get(common).bitStart() : -1,
                        "TRACE_LENGTH_OR_EXHAUSTION"));
            }
        }

        List<KTransitionMatrixReport.Cell> cells = cellCounts.entrySet().stream()
                .map(entry -> new KTransitionMatrixReport.Cell(
                        entry.getKey().tile(), entry.getKey().plane(),
                        entry.getKey().transition(), entry.getKey().zeroSymbol(),
                        entry.getValue()))
                .toList();
        return new KTransitionMatrixReport.Fixture(
                name, outcome, alignedSymbols, cells,
                Map.copyOf(kBins), Map.copyOf(symbolBins), List.copyOf(splits));
    }

    private static boolean sameParsedState(
            KAdaptationStateTracer.Trace left,
            KAdaptationStateTracer.Trace right) {
        return left.ordinal() == right.ordinal()
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
    }

    private static String splitReason(
            KAdaptationStateTracer.Trace left,
            KAdaptationStateTracer.Trace right) {
        if (left.bitStart() != right.bitStart()
                || left.bitEnd() != right.bitEnd()) {
            return "BIT_POSITION";
        }
        if (left.kBefore() != right.kBefore()) {
            return "K_BEFORE";
        }
        if (left.quotient() != right.quotient()
                || left.remainder() != right.remainder()
                || left.unsignedSymbol() != right.unsignedSymbol()) {
            return "PARSED_SYMBOL";
        }
        if (left.x() != right.x() || left.y() != right.y()) {
            return "PIXEL_POSITION";
        }
        return "RECONSTRUCTED_STATE";
    }

    static KTransitionMatrixReport.TransitionClass transition(
            KAdaptationStateTracer.Decision hybrid,
            KAdaptationStateTracer.Decision d8) {
        int left = direction(hybrid);
        int right = direction(d8);
        if (left == 0 && right == 0) {
            return KTransitionMatrixReport.TransitionClass.BOTH_HOLD;
        }
        if (left > 0 && right > 0) {
            return KTransitionMatrixReport.TransitionClass.BOTH_INCREASE;
        }
        if (left < 0 && right < 0) {
            return KTransitionMatrixReport.TransitionClass.BOTH_DECREASE;
        }
        if (left < 0 && right == 0) {
            return KTransitionMatrixReport.TransitionClass.HYBRID_DECREASE_D8_HOLD;
        }
        if (left < 0 && right > 0) {
            return KTransitionMatrixReport.TransitionClass.HYBRID_DECREASE_D8_INCREASE;
        }
        if (left == 0 && right > 0) {
            return KTransitionMatrixReport.TransitionClass.HYBRID_HOLD_D8_INCREASE;
        }
        if (left > 0 && right == 0) {
            return KTransitionMatrixReport.TransitionClass.HYBRID_INCREASE_D8_HOLD;
        }
        if (left > 0 && right < 0) {
            return KTransitionMatrixReport.TransitionClass.HYBRID_INCREASE_D8_DECREASE;
        }
        if (Integer.signum(left) != Integer.signum(right)) {
            return KTransitionMatrixReport.TransitionClass.OTHER_OPPOSITE_DIRECTION;
        }
        return KTransitionMatrixReport.TransitionClass.OTHER_SAME_DIRECTION;
    }

    private static int direction(KAdaptationStateTracer.Decision decision) {
        return switch (decision) {
            case HYBRID_INCREASE, BRACKET_INCREASE -> 1;
            case HYBRID_DECREASE, BRACKET_DECREASE, ZERO_CONTRACT -> -1;
            case HOLD -> 0;
        };
    }

    private static Map<String, Long> emptySymbolBins() {
        Map<String, Long> bins = new LinkedHashMap<>();
        for (String label : List.of(
                "0", "1", "2-3", "4-7", "8-15", "16-31",
                "32-63", "64-255", "256+")) {
            bins.put(label, 0L);
        }
        return bins;
    }

    private static String symbolBin(int symbol) {
        if (symbol == 0) return "0";
        if (symbol == 1) return "1";
        if (symbol <= 3) return "2-3";
        if (symbol <= 7) return "4-7";
        if (symbol <= 15) return "8-15";
        if (symbol <= 31) return "16-31";
        if (symbol <= 63) return "32-63";
        if (symbol <= 255) return "64-255";
        return "256+";
    }

    private record CellKey(int tile, int plane,
                           KTransitionMatrixReport.TransitionClass transition,
                           boolean zeroSymbol) implements Comparable<CellKey> {
        @Override
        public int compareTo(CellKey other) {
            int value = Integer.compare(tile, other.tile);
            if (value != 0) return value;
            value = Integer.compare(plane, other.plane);
            if (value != 0) return value;
            value = transition.compareTo(other.transition);
            if (value != 0) return value;
            return Boolean.compare(zeroSymbol, other.zeroSymbol);
        }
    }
}
