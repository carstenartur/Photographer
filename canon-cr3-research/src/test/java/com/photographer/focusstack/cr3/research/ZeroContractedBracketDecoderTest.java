package com.photographer.focusstack.cr3.research;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ZeroContractedBracketDecoderTest {
    @Test
    void zeroSymbolContractsKByOne() {
        assertThat(ZeroContractedBracketDecoder.nextK(4, 0)).isEqualTo(3);
        assertThat(ZeroContractedBracketDecoder.nextK(1, 0)).isZero();
        assertThat(ZeroContractedBracketDecoder.nextK(0, 0)).isZero();
    }

    @Test
    void nonZeroSymbolUsesUnchangedD6Bracket() {
        for (int k = 0; k <= CrxNormativeLosslessCandidate.K_MAX; k++) {
            for (int symbol : new int[]{1, 2, 3, 7, 31, 255}) {
                assertThat(ZeroContractedBracketDecoder.nextK(k, symbol))
                        .isEqualTo(CrxNormativeLosslessCandidate.symbolBracketNextK(k, symbol));
            }
        }
    }

    @Test
    void transitionRemainsBoundedToOneStep() {
        for (int k = 0; k <= CrxNormativeLosslessCandidate.K_MAX; k++) {
            for (int symbol : new int[]{0, 1, 2, 7, 31, 1023}) {
                assertThat(Math.abs(ZeroContractedBracketDecoder.nextK(k, symbol) - k))
                        .isLessThanOrEqualTo(1);
            }
        }
    }

    @Test
    void rejectsInvalidInputs() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ZeroContractedBracketDecoder.nextK(-1, 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ZeroContractedBracketDecoder.nextK(13, 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ZeroContractedBracketDecoder.nextK(0, -1));
    }
}
