package com.photographer.focusstack.cr3.research;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class KAdaptationStateTracerPrefixTest {
    @Test
    void prefixStopsAtDeclaredSymbolLimitWithoutExhaustion() {
        byte[] payload = {(byte) 0b0010_1100, (byte) 0b1010_0000};

        KAdaptationStateTracer.Result result =
                KAdaptationStateTracer.decodePrefix(
                        payload, 4, 1, 8,
                        KAdaptationStateTracer.Rule.HYBRID, 2);

        assertThat(result.symbolsDecoded()).isEqualTo(2);
        assertThat(result.traces()).hasSize(2);
        assertThat(result.traceTruncated()).isTrue();
        assertThat(result.exhausted()).isFalse();
    }

    @Test
    void prefixMatchesBeginningOfFullTrace() {
        byte[] payload = {(byte) 0b0010_1100, (byte) 0b1010_0000};
        KAdaptationStateTracer.Result prefix =
                KAdaptationStateTracer.decodePrefix(
                        payload, 4, 1, 8,
                        KAdaptationStateTracer.Rule.D8_ZERO_CONTRACT, 2);
        KAdaptationStateTracer.Result full =
                KAdaptationStateTracer.decode(
                        payload, 4, 1, 8,
                        KAdaptationStateTracer.Rule.D8_ZERO_CONTRACT, 32);

        assertThat(prefix.traces()).containsExactlyElementsOf(
                full.traces().subList(0, 2));
        assertThat(prefix.pixels()[0]).isEqualTo(full.pixels()[0]);
        assertThat(prefix.pixels()[1]).isEqualTo(full.pixels()[1]);
    }

    @Test
    void prefixRequiresPositiveLimit() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> KAdaptationStateTracer.decodePrefix(
                        new byte[]{0}, 1, 1, 8,
                        KAdaptationStateTracer.Rule.HYBRID, 0));
    }
}
