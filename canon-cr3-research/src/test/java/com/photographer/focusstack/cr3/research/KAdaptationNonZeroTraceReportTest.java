package com.photographer.focusstack.cr3.research;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.photographer.focusstack.cr3.research.KAdaptationNonZeroTraceReport.Classification;
import static org.assertj.core.api.Assertions.assertThat;

class KAdaptationNonZeroTraceReportTest {
    @Test
    void repeatedConcreteCauseSelectsNextExperiment() {
        List<KAdaptationNonZeroTraceReport.Result> results = List.of(
                result("a.cr3", Classification.HYBRID_HOLD_VS_D8_INCREASE),
                result("b.cr3", Classification.HYBRID_HOLD_VS_D8_INCREASE),
                result("c.cr3", Classification.OTHER_K_DECISION));

        assertThat(KAdaptationNonZeroTraceReport.selectedNextStep(results))
                .isEqualTo("HYBRID_HOLD_VS_D8_INCREASE");
    }

    @Test
    void repeatedOtherDecisionNeverSelectsExperiment() {
        List<KAdaptationNonZeroTraceReport.Result> results = List.of(
                result("a.cr3", Classification.OTHER_K_DECISION),
                result("b.cr3", Classification.OTHER_K_DECISION),
                result("c.cr3", Classification.NO_DIVERGENCE_WITHIN_BOUND));

        assertThat(KAdaptationNonZeroTraceReport.selectedNextStep(results))
                .isEqualTo("NO_COMMON_CAUSE");
    }

    @Test
    void exhaustionAndNoDivergenceNeverSelectExperiment() {
        List<KAdaptationNonZeroTraceReport.Result> results = List.of(
                result("a.cr3", Classification.EXHAUSTION_BEFORE_NON_ZERO_DIVERGENCE),
                result("b.cr3", Classification.EXHAUSTION_BEFORE_NON_ZERO_DIVERGENCE),
                result("c.cr3", Classification.NO_DIVERGENCE_WITHIN_BOUND));

        assertThat(KAdaptationNonZeroTraceReport.selectedNextStep(results))
                .isEqualTo("NO_COMMON_CAUSE");
    }

    @Test
    void singleConcreteCauseIsInsufficient() {
        List<KAdaptationNonZeroTraceReport.Result> results = List.of(
                result("a.cr3", Classification.HYBRID_ACCUMULATOR_DECREASE_VS_D8_HOLD),
                result("b.cr3", Classification.HYBRID_HOLD_VS_D8_INCREASE),
                result("c.cr3", Classification.RESET_OR_STRUCTURE_SCOPE));

        assertThat(KAdaptationNonZeroTraceReport.selectedNextStep(results))
                .isEqualTo("NO_COMMON_CAUSE");
    }

    private static KAdaptationNonZeroTraceReport.Result result(
            String fixture, Classification classification) {
        return new KAdaptationNonZeroTraceReport.Result(
                fixture, true, classification,
                0, 0, 0, List.of(),
                false, false, false, false);
    }
}
