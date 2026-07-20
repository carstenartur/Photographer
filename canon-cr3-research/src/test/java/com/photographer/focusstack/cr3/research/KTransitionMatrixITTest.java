package com.photographer.focusstack.cr3.research;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KTransitionMatrixITTest {
    @Test
    void classifiesSharedDirections() {
        assertThat(KTransitionMatrixIT.transition(
                KAdaptationStateTracer.Decision.HOLD,
                KAdaptationStateTracer.Decision.HOLD))
                .isEqualTo(KTransitionMatrixReport.TransitionClass.BOTH_HOLD);
        assertThat(KTransitionMatrixIT.transition(
                KAdaptationStateTracer.Decision.HYBRID_INCREASE,
                KAdaptationStateTracer.Decision.BRACKET_INCREASE))
                .isEqualTo(KTransitionMatrixReport.TransitionClass.BOTH_INCREASE);
        assertThat(KTransitionMatrixIT.transition(
                KAdaptationStateTracer.Decision.HYBRID_DECREASE,
                KAdaptationStateTracer.Decision.ZERO_CONTRACT))
                .isEqualTo(KTransitionMatrixReport.TransitionClass.BOTH_DECREASE);
    }

    @Test
    void classifiesD9ObservedDisagreements() {
        assertThat(KTransitionMatrixIT.transition(
                KAdaptationStateTracer.Decision.HYBRID_DECREASE,
                KAdaptationStateTracer.Decision.HOLD))
                .isEqualTo(KTransitionMatrixReport.TransitionClass
                        .HYBRID_DECREASE_D8_HOLD);
        assertThat(KTransitionMatrixIT.transition(
                KAdaptationStateTracer.Decision.HYBRID_DECREASE,
                KAdaptationStateTracer.Decision.BRACKET_INCREASE))
                .isEqualTo(KTransitionMatrixReport.TransitionClass
                        .HYBRID_DECREASE_D8_INCREASE);
        assertThat(KTransitionMatrixIT.transition(
                KAdaptationStateTracer.Decision.HOLD,
                KAdaptationStateTracer.Decision.BRACKET_INCREASE))
                .isEqualTo(KTransitionMatrixReport.TransitionClass
                        .HYBRID_HOLD_D8_INCREASE);
    }

    @Test
    void classifiesRemainingOppositeDirections() {
        assertThat(KTransitionMatrixIT.transition(
                KAdaptationStateTracer.Decision.HYBRID_INCREASE,
                KAdaptationStateTracer.Decision.HOLD))
                .isEqualTo(KTransitionMatrixReport.TransitionClass
                        .HYBRID_INCREASE_D8_HOLD);
        assertThat(KTransitionMatrixIT.transition(
                KAdaptationStateTracer.Decision.HYBRID_INCREASE,
                KAdaptationStateTracer.Decision.BRACKET_DECREASE))
                .isEqualTo(KTransitionMatrixReport.TransitionClass
                        .HYBRID_INCREASE_D8_DECREASE);
    }
}
