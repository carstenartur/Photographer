package com.photographer.focusstack.cr3.research;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StablePayloadDiffTest {

    @Test
    void excludesRepeatNoiseWhileRetainingStableStimulusBits() {
        byte[] a1 = {(byte) 0b0000_0000, (byte) 0b0000_0000};
        byte[] a2 = {(byte) 0b0000_0001, (byte) 0b0000_0000};
        byte[] b1 = {(byte) 0b0000_0011, (byte) 0b1111_0000};
        byte[] b2 = {(byte) 0b0000_0010, (byte) 0b1111_0000};

        StablePayloadDiff.Result result = StablePayloadDiff.compare(a1, a2, b1, b2);

        assertThat(result.lengthMismatch()).isFalse();
        assertThat(result.comparedBytes()).isEqualTo(2);
        assertThat(result.unstableBytes()).isEqualTo(1);
        assertThat(result.unstableBits()).isEqualTo(1);
        assertThat(result.stableChangedBytes()).isEqualTo(2);
        assertThat(result.stableChangedBits()).isEqualTo(5);
        assertThat(result.firstStableChangedByte()).isZero();
        assertThat(result.stableRuns())
                .containsExactly(new StablePayloadDiff.ByteRun(0, 2, 2, 5));
    }

    @Test
    void createsSeparateRunsAroundStableEqualBytes() {
        byte[] a = {0, 0, 0, 0, 0};
        byte[] b = {1, 1, 0, 4, 0};

        StablePayloadDiff.Result result = StablePayloadDiff.compare(a, a, b, b);

        assertThat(result.stableRuns()).containsExactly(
                new StablePayloadDiff.ByteRun(0, 2, 2, 2),
                new StablePayloadDiff.ByteRun(3, 4, 1, 1));
        assertThat(result.stableChangedBytes()).isEqualTo(3);
        assertThat(result.stableChangedBits()).isEqualTo(3);
    }

    @Test
    void reportsLengthMismatchWithoutTreatingUnalignedTailAsStableMappingEvidence() {
        StablePayloadDiff.Result result = StablePayloadDiff.compare(
                new byte[]{0, 0, 0},
                new byte[]{0, 0, 0},
                new byte[]{0, 1, 0, 7},
                new byte[]{0, 1, 0, 7});

        assertThat(result.lengthMismatch()).isTrue();
        assertThat(result.minimumInputLength()).isEqualTo(3);
        assertThat(result.maximumInputLength()).isEqualTo(4);
        assertThat(result.stableChangedBytes()).isEqualTo(1);
        assertThat(result.firstStableChangedByte()).isEqualTo(1);
        assertThat(result.stableRuns())
                .containsExactly(new StablePayloadDiff.ByteRun(1, 2, 1, 1));
    }

    @Test
    void identicalRepeatPairsProduceNoStableChange() {
        byte[] payload = {1, 2, 3};

        StablePayloadDiff.Result result = StablePayloadDiff.compare(
                payload, payload, payload, payload);

        assertThat(result.hasStableChange()).isFalse();
        assertThat(result.firstStableChangedByte()).isEqualTo(-1);
        assertThat(result.stableRuns()).isEmpty();
    }
}
