package com.photographer.focusstack.cr3.research;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ExactCodeLengthBracketDecoderTest {

    @Test
    void keepsD8ZeroContractionUnchanged() {
        assertThat(ExactCodeLengthBracketDecoder.nextK(3, 0)).isEqualTo(2);
        assertThat(ExactCodeLengthBracketDecoder.nextK(0, 0)).isZero();
        assertThat(ExactCodeLengthBracketDecoder.nextK(3, 0))
                .isEqualTo(ZeroContractedBracketDecoder.nextK(3, 0));
    }

    @Test
    void decreasesWhenD8ApproximationHasAnExtraUpperValue() {
        assertThat(ZeroContractedBracketDecoder.nextK(2, 1)).isEqualTo(2);
        assertThat(ExactCodeLengthBracketDecoder.nextK(2, 1)).isEqualTo(1);

        assertThat(ZeroContractedBracketDecoder.nextK(3, 3)).isEqualTo(3);
        assertThat(ExactCodeLengthBracketDecoder.nextK(3, 3)).isEqualTo(2);
    }

    @Test
    void increasesWhenD8ApproximationHasAnExtraLowerValue() {
        assertThat(ZeroContractedBracketDecoder.nextK(1, 6)).isEqualTo(1);
        assertThat(ExactCodeLengthBracketDecoder.nextK(1, 6)).isEqualTo(2);
    }

    @Test
    void agreesWhereTheTwoRangesAreIdentical() {
        for (int currentK = 0; currentK <= 5; currentK++) {
            assertThat(ExactCodeLengthBracketDecoder.nextK(currentK, 4))
                    .isEqualTo(ZeroContractedBracketDecoder.nextK(currentK, 4));
            assertThat(ExactCodeLengthBracketDecoder.nextK(currentK, 8))
                    .isEqualTo(ZeroContractedBracketDecoder.nextK(currentK, 8));
        }
    }

    @Test
    void changesAtMostOneStepAndStaysWithinCandidateClamp() {
        for (int currentK = 0;
             currentK <= CrxNormativeLosslessCandidate.K_MAX;
             currentK++) {
            for (int symbol = 0; symbol <= 512; symbol++) {
                int next = ExactCodeLengthBracketDecoder.nextK(currentK, symbol);
                assertThat(Math.abs(next - currentK)).isLessThanOrEqualTo(1);
                assertThat(next).isBetween(0, CrxNormativeLosslessCandidate.K_MAX);
            }
        }
    }

    @Test
    void rejectsInvalidState() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ExactCodeLengthBracketDecoder.nextK(-1, 1));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ExactCodeLengthBracketDecoder.nextK(0, -1));
    }
}
