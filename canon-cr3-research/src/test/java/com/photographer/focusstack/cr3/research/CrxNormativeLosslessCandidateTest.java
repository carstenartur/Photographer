package com.photographer.focusstack.cr3.research;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class CrxNormativeLosslessCandidateTest {

    @Test
    void symbolBracketMovesUpByOnlyOneStepWhenKIsBelowRange() {
        // S=31 => K*=5 => accepted bracket 4..6.
        assertThat(CrxNormativeLosslessCandidate.symbolBracketNextK(0, 31)).isEqualTo(1);
        assertThat(CrxNormativeLosslessCandidate.symbolBracketNextK(3, 31)).isEqualTo(4);
    }

    @Test
    void symbolBracketMovesDownByOnlyOneStepWhenKIsAboveRange() {
        // S=0 => K*=0 => accepted bracket 0..1.
        assertThat(CrxNormativeLosslessCandidate.symbolBracketNextK(12, 0)).isEqualTo(11);
        assertThat(CrxNormativeLosslessCandidate.symbolBracketNextK(2, 0)).isEqualTo(1);
    }

    @Test
    void symbolBracketKeepsKInsideAcceptedRange() {
        // S=7 => K*=3 => accepted bracket 2..4.
        assertThat(CrxNormativeLosslessCandidate.symbolBracketNextK(2, 7)).isEqualTo(2);
        assertThat(CrxNormativeLosslessCandidate.symbolBracketNextK(3, 7)).isEqualTo(3);
        assertThat(CrxNormativeLosslessCandidate.symbolBracketNextK(4, 7)).isEqualTo(4);
    }

    @Test
    void symbolBracketRespectsTheCandidateKClamp() {
        assertThat(CrxNormativeLosslessCandidate.symbolBracketNextK(11, Integer.MAX_VALUE - 1))
                .isEqualTo(CrxNormativeLosslessCandidate.K_MAX);
        assertThat(CrxNormativeLosslessCandidate.symbolBracketNextK(
                CrxNormativeLosslessCandidate.K_MAX, Integer.MAX_VALUE - 1))
                .isEqualTo(CrxNormativeLosslessCandidate.K_MAX);
    }

    @Test
    void symbolBracketRejectsInvalidInputs() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CrxNormativeLosslessCandidate.symbolBracketNextK(-1, 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CrxNormativeLosslessCandidate.symbolBracketNextK(
                        CrxNormativeLosslessCandidate.K_MAX + 1, 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CrxNormativeLosslessCandidate.symbolBracketNextK(0, -1));
    }

    @Test
    void optionsCopyPreservesSelectedKAdaptation() {
        CrxNormativeLosslessCandidate.Options options =
                new CrxNormativeLosslessCandidate.Options();
        options.kAdaptation = CrxNormativeLosslessCandidate.KAdaptation.SYMBOL_BRACKET;

        assertThat(options.copy().kAdaptation)
                .isEqualTo(CrxNormativeLosslessCandidate.KAdaptation.SYMBOL_BRACKET);
    }
}
