package com.photographer.focusstack.cr3.research;

import java.io.IOException;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LosslessOpeningSamplesTest {
    private static byte[] bytes(String hex) {
        return HexFormat.of().parseHex(hex);
    }

    @Test
    void firstOnlyNeedsFourBytesAndHandlesBothSignBranches() throws IOException {
        assertThat(LosslessOpeningSamples.first(bytes("00200000"), 14)).isEqualTo(8192);
        assertThat(LosslessOpeningSamples.first(bytes("00200001"), 14)).isEqualTo(8191);
        assertThat(LosslessOpeningSamples.first(bytes("00203ffe"), 14)).isEqualTo(16383);
        assertThat(LosslessOpeningSamples.first(bytes("00203fff"), 14)).isZero();
        assertThat(LosslessOpeningSamples.first(bytes("00203059"), 14)).isEqualTo(2003);
    }

    @Test
    void readsOrdinarySecondResidualInEitherDirection() throws IOException {
        // 2003, q=1, remainder 2, code 6 and delta +3.
        assertThat(LosslessOpeningSamples.decode(bytes("0020305960"), 14))
                .isEqualTo(new LosslessOpeningSamples.Opening(2003, 2006, 1, 6, 36));
        // q=0, remainder 3, code 3 and delta -2.
        assertThat(LosslessOpeningSamples.decode(bytes("00203059e0"), 14))
                .isEqualTo(new LosslessOpeningSamples.Opening(2003, 2001, 0, 3, 35));
    }

    @Test
    void handlesLastOrdinaryQuotientAndEscapeLiteral() throws IOException {
        assertThat(LosslessOpeningSamples.decode(bytes("002000000000000000e0"), 14))
                .isEqualTo(new LosslessOpeningSamples.Opening(8192, 8110, 40, 163, 75));
        assertThat(LosslessOpeningSamples.decode(bytes("00202f35000000000040020a"), 14))
                .isEqualTo(new LosslessOpeningSamples.Opening(2149, 2018, 41, 261, 95));
    }

    @Test
    void rejectsUnobservedFormatsAndTruncationsWithoutZeroPadding() {
        assertThatThrownBy(() -> LosslessOpeningSamples.first(bytes("00203059"), 12))
                .isInstanceOf(IOException.class).hasMessageContaining("depth");
        assertThatThrownBy(() -> LosslessOpeningSamples.first(bytes("00207059"), 14))
                .isInstanceOf(IOException.class).hasMessageContaining("prefix");
        byte[] escape = bytes("00202f35000000000040020a");
        for (int i = 0; i < escape.length; i++) {
            byte[] prefix = Arrays.copyOf(escape, i);
            assertThatThrownBy(() -> LosslessOpeningSamples.decode(prefix, 14))
                    .isInstanceOf(IOException.class);
        }
        assertThatThrownBy(() -> LosslessOpeningSamples.decode(bytes("00203059000000000020"), 14))
                .isInstanceOf(IOException.class).hasMessageContaining("quotient");
        assertThatThrownBy(() -> LosslessOpeningSamples.decode(bytes("00203fffa0"), 14))
                .isInstanceOf(IOException.class).hasMessageContaining("range");
    }
}
