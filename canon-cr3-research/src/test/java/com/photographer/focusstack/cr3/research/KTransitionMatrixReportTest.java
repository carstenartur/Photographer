package com.photographer.focusstack.cr3.research;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KTransitionMatrixReportTest {
    private static final KTransitionMatrixReport.TransitionClass CANDIDATE =
            KTransitionMatrixReport.TransitionClass.HYBRID_HOLD_D8_INCREASE;

    @Test
    void selectsRepeatedNonZeroFailureTransitionWithoutImprovementContradiction() {
        List<KTransitionMatrixReport.Fixture> fixtures = List.of(
                fixture("failure-a.cr3",
                        D8OutcomeTable.OutcomeClass.COMPLETION_REGRESSION,
                        false, CANDIDATE),
                fixture("failure-b.cr3",
                        D8OutcomeTable.OutcomeClass.COMPLETION_REGRESSION,
                        false, CANDIDATE),
                fixture("improved.cr3",
                        D8OutcomeTable.OutcomeClass.COMPLETE_IMPROVEMENT,
                        false,
                        KTransitionMatrixReport.TransitionClass.BOTH_HOLD));

        List<KTransitionMatrixReport.Association> associations =
                KTransitionMatrixReport.associations(fixtures);

        assertThat(KTransitionMatrixReport.selectedNextCause(associations))
                .isEqualTo("HYBRID_HOLD_D8_INCREASE->COMPLETION_REGRESSION");
    }

    @Test
    void improvementContradictionRejectsOtherwiseRepeatedTransition() {
        List<KTransitionMatrixReport.Fixture> fixtures = List.of(
                fixture("failure-a.cr3",
                        D8OutcomeTable.OutcomeClass.COMPLETION_REGRESSION,
                        false, CANDIDATE),
                fixture("failure-b.cr3",
                        D8OutcomeTable.OutcomeClass.COMPLETION_REGRESSION,
                        false, CANDIDATE),
                fixture("improved.cr3",
                        D8OutcomeTable.OutcomeClass.COMPLETE_IMPROVEMENT,
                        false, CANDIDATE));

        assertThat(KTransitionMatrixReport.selectedNextCause(
                KTransitionMatrixReport.associations(fixtures)))
                .isEqualTo("NO_PREDICTIVE_TRANSITION_CLASS");
    }

    @Test
    void zeroOnlyTransitionCannotSelectSemanticExperiment() {
        List<KTransitionMatrixReport.Fixture> fixtures = List.of(
                fixture("failure-a.cr3",
                        D8OutcomeTable.OutcomeClass.COMPLETE_GLOBAL_REGRESSION,
                        true, CANDIDATE),
                fixture("failure-b.cr3",
                        D8OutcomeTable.OutcomeClass.COMPLETE_GLOBAL_REGRESSION,
                        true, CANDIDATE));

        assertThat(KTransitionMatrixReport.selectedNextCause(
                KTransitionMatrixReport.associations(fixtures)))
                .isEqualTo("NO_PREDICTIVE_TRANSITION_CLASS");
    }

    @Test
    void oneFixtureSupportIsInsufficient() {
        List<KTransitionMatrixReport.Fixture> fixtures = List.of(
                fixture("failure.cr3",
                        D8OutcomeTable.OutcomeClass.COMPLETION_REGRESSION,
                        false, CANDIDATE),
                fixture("mixed.cr3",
                        D8OutcomeTable.OutcomeClass.COMPLETE_MIXED,
                        false,
                        KTransitionMatrixReport.TransitionClass.BOTH_HOLD));

        assertThat(KTransitionMatrixReport.selectedNextCause(
                KTransitionMatrixReport.associations(fixtures)))
                .isEqualTo("NO_PREDICTIVE_TRANSITION_CLASS");
    }

    @Test
    void mixedOutcomeIsReportedButCannotSelectSemanticExperiment() {
        List<KTransitionMatrixReport.Fixture> fixtures = List.of(
                fixture("mixed-a.cr3",
                        D8OutcomeTable.OutcomeClass.COMPLETE_MIXED,
                        false, CANDIDATE),
                fixture("mixed-b.cr3",
                        D8OutcomeTable.OutcomeClass.COMPLETE_MIXED,
                        false, CANDIDATE));

        List<KTransitionMatrixReport.Association> associations =
                KTransitionMatrixReport.associations(fixtures);

        assertThat(associations)
                .anySatisfy(association -> {
                    assertThat(association.transition()).isEqualTo(CANDIDATE);
                    assertThat(association.outcomeClass())
                            .isEqualTo(D8OutcomeTable.OutcomeClass.COMPLETE_MIXED);
                    assertThat(association.support()).isEqualTo(2);
                    assertThat(association.predictive()).isFalse();
                });
        assertThat(KTransitionMatrixReport.selectedNextCause(associations))
                .isEqualTo("NO_PREDICTIVE_TRANSITION_CLASS");
    }

    @Test
    void aggregatesCorpusCountsInStableSymbolAndTransitionOrder() {
        List<KTransitionMatrixReport.Fixture> fixtures = List.of(
                fixture("zero.cr3",
                        D8OutcomeTable.OutcomeClass.COMPLETE_MIXED,
                        true, CANDIDATE),
                fixture("nonzero.cr3",
                        D8OutcomeTable.OutcomeClass.COMPLETION_REGRESSION,
                        false, CANDIDATE));

        assertThat(KTransitionMatrixReport.aggregateCells(fixtures))
                .containsExactly(
                        new KTransitionMatrixReport.AggregateCell(true, CANDIDATE, 10),
                        new KTransitionMatrixReport.AggregateCell(false, CANDIDATE, 10));

        String markdown = KTransitionMatrixReport.markdown(
                fixtures, KTransitionMatrixReport.associations(fixtures));
        assertThat(markdown)
                .contains("## Corpus transition totals")
                .contains("Total aligned symbols: `20`")
                .contains("| ZERO | HYBRID_HOLD_D8_INCREASE | 10 | 0.500000 |")
                .contains("| NON_ZERO | HYBRID_HOLD_D8_INCREASE | 10 | 0.500000 |");
    }

    private static KTransitionMatrixReport.Fixture fixture(
            String name,
            D8OutcomeTable.OutcomeClass outcomeClass,
            boolean zeroSymbol,
            KTransitionMatrixReport.TransitionClass transition) {
        D8OutcomeTable.Outcome outcome = new D8OutcomeTable.Outcome(
                name, outcomeClass,
                1, 1, 0,
                1, 1, 0,
                1, 1, 0,
                1, 1, 0,
                false, false);
        KTransitionMatrixReport.Cell cell = new KTransitionMatrixReport.Cell(
                0, 0, transition, zeroSymbol, 10);
        return new KTransitionMatrixReport.Fixture(
                name, outcome, 10, List.of(cell),
                Map.of(0, 10L), Map.of("1", 10L), List.of());
    }
}
