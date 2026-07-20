package com.photographer.focusstack.cr3.research;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CrxNormativeLosslessTraceTest {
    @Test
    void tracingDoesNotChangeOutputOrTerminalState() {
        byte[] payload = {(byte) 0b1011_0000};
        CrxNormativeLosslessCandidate.Options plain = options();
        CrxNormativeLosslessCandidate.Options traced = plain.copy();
        traced.traceSink = trace -> false;

        CrxNormativeLosslessCandidate.Result a =
                CrxNormativeLosslessCandidate.decodePlane(payload, 2, 1, 8, plain);
        CrxNormativeLosslessCandidate.Result b =
                CrxNormativeLosslessCandidate.decodePlane(payload, 2, 1, 8, traced);

        assertThat(b.pixels).containsExactly(a.pixels);
        assertThat(b.symbolsDecoded).isEqualTo(a.symbolsDecoded);
        assertThat(b.streamExhausted).isEqualTo(a.streamExhausted);
        assertThat(b.consumedBytes).isEqualTo(a.consumedBytes);
    }

    @Test
    void tracePinsExactBitPositions() {
        List<CrxNormativeLosslessCandidate.SymbolTrace> traces = new ArrayList<>();
        CrxNormativeLosslessCandidate.Options options = options();
        options.traceSink = trace -> { traces.add(trace); return true; };

        CrxNormativeLosslessCandidate.decodePlane(
                new byte[]{(byte) 0b1011_0000}, 2, 1, 8, options);

        assertThat(traces).hasSize(2);
        assertThat(traces.get(0).bitStart()).isZero();
        assertThat(traces.get(0).bitEnd()).isEqualTo(3);
        assertThat(traces.get(0).quotient()).isEqualTo(1);
        assertThat(traces.get(0).remainder()).isEqualTo(1);
        assertThat(traces.get(1).bitStart()).isEqualTo(3);
        assertThat(traces.get(1).bitEnd()).isEqualTo(6);
    }

    private static CrxNormativeLosslessCandidate.Options options() {
        CrxNormativeLosslessCandidate.Options o = new CrxNormativeLosslessCandidate.Options();
        o.kInit = 1;
        o.resetKPerRow = false;
        o.unaryOnesUntilZero = true;
        o.signBitMapping = true;
        o.escapeAtUnary15 = false;
        o.escapeBitWidth = 8;
        o.kAdaptation = CrxNormativeLosslessCandidate.KAdaptation.SYMBOL_BRACKET;
        return o;
    }
}
