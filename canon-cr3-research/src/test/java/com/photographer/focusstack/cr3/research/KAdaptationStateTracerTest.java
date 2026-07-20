package com.photographer.focusstack.cr3.research;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KAdaptationStateTracerTest {
    @Test
    void hybridTracerMatchesExistingCandidate() {
        byte[] payload = {(byte) 0b0010_1100, (byte) 0b1010_0000};
        CrxNormativeLosslessCandidate.Options options =
                new CrxNormativeLosslessCandidate.Options();
        options.kInit = 1;
        options.resetKPerRow = true;
        options.unaryOnesUntilZero = true;
        options.signBitMapping = true;
        options.escapeAtUnary15 = false;
        options.firstPixelSeed = 0;
        options.escapeBitWidth = 8;
        options.kAdaptation =
                CrxNormativeLosslessCandidate.KAdaptation.HYBRID_ACCUMULATOR;

        CrxNormativeLosslessCandidate.Result expected =
                CrxNormativeLosslessCandidate.decodePlane(
                        payload, 4, 1, 8, options);
        KAdaptationStateTracer.Result actual =
                KAdaptationStateTracer.decode(
                        payload, 4, 1, 8,
                        KAdaptationStateTracer.Rule.HYBRID, 32);

        assertThat(actual.pixels()).containsExactly(expected.pixels);
        assertThat(actual.symbolsDecoded()).isEqualTo(expected.symbolsDecoded);
        assertThat(actual.exhausted()).isEqualTo(expected.streamExhausted);
    }

    @Test
    void d8TracerMatchesExistingZeroContractedDecoder() {
        byte[] payload = {(byte) 0b0010_1100, (byte) 0b1010_0000};
        ZeroContractedBracketDecoder.Result expected =
                ZeroContractedBracketDecoder.decode(payload, 4, 1, 8);
        KAdaptationStateTracer.Result actual =
                KAdaptationStateTracer.decode(
                        payload, 4, 1, 8,
                        KAdaptationStateTracer.Rule.D8_ZERO_CONTRACT, 32);

        assertThat(actual.pixels()).containsExactly(expected.pixels());
        assertThat(actual.symbolsDecoded()).isEqualTo(expected.symbolsDecoded());
        assertThat(actual.exhausted()).isEqualTo(expected.exhausted());
    }

    @Test
    void traceContainsAccumulatorAndBracketEvidence() {
        byte[] payload = {(byte) 0b0010_1100, (byte) 0b1010_0000};
        KAdaptationStateTracer.Result hybrid =
                KAdaptationStateTracer.decode(
                        payload, 4, 1, 8,
                        KAdaptationStateTracer.Rule.HYBRID, 32);
        KAdaptationStateTracer.Result d8 =
                KAdaptationStateTracer.decode(
                        payload, 4, 1, 8,
                        KAdaptationStateTracer.Rule.D8_ZERO_CONTRACT, 32);

        assertThat(hybrid.traces()).isNotEmpty();
        assertThat(d8.traces()).isNotEmpty();
        assertThat(hybrid.traces().getFirst().bitStart()).isZero();
        assertThat(hybrid.traces().getFirst().sampleCountBefore()).isEqualTo(1);
        assertThat(hybrid.traces().getFirst().sampleCountAfter()).isEqualTo(2);
        assertThat(d8.traces().getFirst().kMin()).isGreaterThanOrEqualTo(0);
        assertThat(d8.traces().getFirst().kMax())
                .isLessThanOrEqualTo(CrxNormativeLosslessCandidate.K_MAX);
    }

    @Test
    void traceBoundDoesNotStopDecoding() {
        byte[] payload = {(byte) 0b0010_1100, (byte) 0b1010_0000};
        KAdaptationStateTracer.Result bounded =
                KAdaptationStateTracer.decode(
                        payload, 4, 1, 8,
                        KAdaptationStateTracer.Rule.HYBRID, 1);
        KAdaptationStateTracer.Result complete =
                KAdaptationStateTracer.decode(
                        payload, 4, 1, 8,
                        KAdaptationStateTracer.Rule.HYBRID, 32);

        assertThat(bounded.traceTruncated()).isTrue();
        assertThat(bounded.traces()).hasSize(1);
        assertThat(bounded.pixels()).containsExactly(complete.pixels());
        assertThat(bounded.symbolsDecoded()).isEqualTo(complete.symbolsDecoded());
    }
}
