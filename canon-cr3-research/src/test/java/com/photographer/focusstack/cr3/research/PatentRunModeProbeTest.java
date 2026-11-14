package com.photographer.focusstack.cr3.research;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PatentRunModeProbeTest {
    @Test
    void originZeroFlagShiftsTheRiceCodeAndTheSeedDoesNotFillMissingNeighbours() {
        byte[] payload = {0x18, 0}; // 0 | 00 | 110 = first flag, Rice(0,K=1), Rice(2,K=0)
        int[] expected = {10, 11};
        var candidate = PatentRunModeProbe.measure(payload, 14, expected, true);
        var baseline = PatentRunModeProbe.measure(payload, 14, expected, false);
        assertThat(candidate.flagOffsets()).isEqualTo("0=0");
        assertThat(baseline.matched()).isEqualTo(1);
        assertThat(candidate.matched()).isEqualTo(2);
        assertThat(PatentRunModeProbe.compare(baseline, candidate)).isEqualTo("IMPROVED");
    }

    @Test
    void positiveRunAtOriginMustCopyTheBoundaryEvenWhenTheSeedIsNonzero() {
        var decisive = PatentRunModeProbe.measure(new byte[]{(byte) 0x80}, 14,
                new int[]{42, 42}, true);
        assertThat(decisive.stop()).isEqualTo(PatentRunModeProbe.Stop.RUN_CONTRADICTION);
        assertThat(decisive.matched()).isZero();
        assertThat(decisive.reconstructed()).isZero();

        var forbiddenZeroRun = PatentRunModeProbe.measure(new byte[]{0}, 14, new int[]{0}, true);
        assertThat(forbiddenZeroRun.stop()).isEqualTo(PatentRunModeProbe.Stop.ZERO_RUN_CONTRADICTION);

        var compatible = PatentRunModeProbe.measure(new byte[]{(byte) 0x80}, 14,
                new int[]{0, 0}, true);
        assertThat(compatible.stop()).isEqualTo(PatentRunModeProbe.Stop.POSITIVE_RUN_UNRESOLVED);
        assertThat(PatentRunModeProbe.compare(decisive, compatible)).isEqualTo("UNRESOLVED");
    }

    @Test
    void findsFirstOneInThePayloadWithoutReadingPastAnAllZeroPrefix() {
        assertThat(PatentRunModeProbe.firstOneBit(new byte[]{0, 0, 0x20})).isEqualTo(18);
        assertThat(PatentRunModeProbe.firstOneBit(new byte[]{(byte) 0x80})).isZero();
        assertThat(PatentRunModeProbe.firstOneBit(new byte[]{0, 0})).isEqualTo(-1);
        assertThat(PatentRunModeProbe.firstOneBit(new byte[0])).isEqualTo(-1);
    }

    @Test
    void unequalNorthEastBlocksRunSelection() {
        var probe = new PatentRunModeProbe();
        int[] raster = {4, 4, 9, 4, 0, 0};
        assertThat(probe.guard(raster, 3, 1, 1)).isFalse();
        raster[2] = 4;
        assertThat(probe.guard(raster, 3, 1, 1)).isTrue();
    }
}
