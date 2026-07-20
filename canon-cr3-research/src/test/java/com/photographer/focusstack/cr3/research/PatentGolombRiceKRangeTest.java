package com.photographer.focusstack.cr3.research;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PatentGolombRiceKRangeTest {

    private static final int PATENT_MODEL_MAX_K = 15;

    @Test
    void reproducesPatentExamples() {
        assertThat(PatentGolombRiceKRange.optimalRange(2, PATENT_MODEL_MAX_K))
                .isEqualTo(new PatentGolombRiceKRange.Range(0, 2, 3));
        assertThat(PatentGolombRiceKRange.optimalRange(4, PATENT_MODEL_MAX_K))
                .isEqualTo(new PatentGolombRiceKRange.Range(1, 3, 4));
        assertThat(PatentGolombRiceKRange.optimalRange(6, PATENT_MODEL_MAX_K))
                .isEqualTo(new PatentGolombRiceKRange.Range(2, 3, 4));
    }

    @Test
    void pinsSmallSymbolRangesThatDifferFromTheD6Approximation() {
        assertRange(0, 0, 0);
        assertRange(1, 0, 1);
        assertRange(2, 0, 2);
        assertRange(3, 1, 2);
        assertRange(4, 1, 3);
        assertRange(6, 2, 3);
        assertRange(7, 2, 3);
        assertRange(8, 2, 4);
    }

    @Test
    void movesAtMostOneStepTowardTheExactRange() {
        assertThat(PatentGolombRiceKRange.nextK(0, 6, PATENT_MODEL_MAX_K))
                .isEqualTo(1);
        assertThat(PatentGolombRiceKRange.nextK(2, 6, PATENT_MODEL_MAX_K))
                .isEqualTo(2);
        assertThat(PatentGolombRiceKRange.nextK(3, 6, PATENT_MODEL_MAX_K))
                .isEqualTo(3);
        assertThat(PatentGolombRiceKRange.nextK(5, 6, PATENT_MODEL_MAX_K))
                .isEqualTo(4);
    }

    @Test
    void respectsThePredeclaredFiniteKDomain() {
        PatentGolombRiceKRange.Range range =
                PatentGolombRiceKRange.optimalRange(1_000_000, 5);

        assertThat(range.minK()).isEqualTo(5);
        assertThat(range.maxK()).isEqualTo(5);
        assertThat(PatentGolombRiceKRange.nextK(4, 1_000_000, 5))
                .isEqualTo(5);
    }

    @Test
    void supportsTheCompleteNonNegativeLongKDomain() {
        assertThat(PatentGolombRiceKRange.codeLength(0, 63)).isEqualTo(64);
        assertThat(PatentGolombRiceKRange.optimalRange(Long.MAX_VALUE, 63))
                .isEqualTo(new PatentGolombRiceKRange.Range(62, 63, 64));
    }

    @Test
    void calculatesCodeLengthFromUnaryPrefixStopBitAndSuffix() {
        assertThat(PatentGolombRiceKRange.codeLength(6, 0)).isEqualTo(7);
        assertThat(PatentGolombRiceKRange.codeLength(6, 1)).isEqualTo(5);
        assertThat(PatentGolombRiceKRange.codeLength(6, 2)).isEqualTo(4);
        assertThat(PatentGolombRiceKRange.codeLength(6, 3)).isEqualTo(4);
        assertThat(PatentGolombRiceKRange.codeLength(6, 4)).isEqualTo(5);
    }

    @Test
    void rejectsInvalidDomainsAndSymbols() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PatentGolombRiceKRange.optimalRange(-1, 15));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PatentGolombRiceKRange.optimalRange(0, 64));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PatentGolombRiceKRange.nextK(16, 0, 15));
    }

    private static void assertRange(long symbol, int minK, int maxK) {
        PatentGolombRiceKRange.Range range =
                PatentGolombRiceKRange.optimalRange(symbol, PATENT_MODEL_MAX_K);
        assertThat(range.minK()).as("Kmin(%s)", symbol).isEqualTo(minK);
        assertThat(range.maxK()).as("Kmax(%s)", symbol).isEqualTo(maxK);
    }
}
