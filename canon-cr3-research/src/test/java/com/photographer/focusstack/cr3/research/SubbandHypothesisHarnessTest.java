package com.photographer.focusstack.cr3.research;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Self-tests for {@link SubbandHypothesisHarness}. Since the harness's job is
 * to discriminate good from bad candidates, the most important property is
 * that an identity decoder reports 100% match on a fixture whose reference
 * values are equal to the payload bytes, and that an obviously-wrong decoder
 * reports a low match fraction.
 */
class SubbandHypothesisHarnessTest {

    @Test
    void identityDecoderProducesPerfectMatch() {
        byte[] payload = new byte[]{0, 1, 2, (byte) 0xFE, (byte) 0xFF};
        int[] reference = new int[]{0, 1, 2, 0xFE, 0xFF};
        SubbandHypothesisHarness.Result result = SubbandHypothesisHarness.evaluate(
                "identity", payload, reference,
                SubbandHypothesisHarness.identityByteDecoder());

        assertThat(result.threwException()).isFalse();
        assertThat(result.exactMatches()).isEqualTo(reference.length);
        assertThat(result.exactMatchFraction()).isEqualTo(1.0);
        assertThat(result.meanAbsError()).isZero();
        assertThat(result.maxAbsError()).isZero();
    }

    @Test
    void wrongDecoderReportsLowAccuracy() {
        byte[] payload = new byte[]{1, 2, 3, 4};
        int[] reference = new int[]{1, 2, 3, 4};
        Function<byte[], int[]> alwaysZero = bytes -> new int[bytes.length];

        SubbandHypothesisHarness.Result result = SubbandHypothesisHarness.evaluate(
                "always-zero", payload, reference, alwaysZero);

        // reference = {1,2,3,4}, decoded = {0,0,0,0} → no exact matches anywhere.
        assertThat(result.exactMatches()).isZero();
        assertThat(result.meanAbsError()).isEqualTo(2.5); // (1+2+3+4)/4
        assertThat(result.maxAbsError()).isEqualTo(4);
    }

    @Test
    void exceptionsAreCapturedNotPropagated() {
        byte[] payload = new byte[]{1};
        int[] reference = new int[]{1};
        Function<byte[], int[]> throwing = bytes -> {
            throw new ArithmeticException("nope");
        };

        SubbandHypothesisHarness.Result result = SubbandHypothesisHarness.evaluate(
                "throwing", payload, reference, throwing);

        assertThat(result.threwException()).isTrue();
        assertThat(result.exceptionType()).isEqualTo(ArithmeticException.class.getName());
        assertThat(result.exactMatchFraction()).isZero();
    }

    @Test
    void shorterDecodedOutputCountsOnlyOverlap() {
        byte[] payload = new byte[]{1, 2};
        int[] reference = new int[]{1, 2, 3, 4};
        Function<byte[], int[]> partial = bytes -> new int[]{1, 2};

        SubbandHypothesisHarness.Result result = SubbandHypothesisHarness.evaluate(
                "partial", payload, reference, partial);

        assertThat(result.exactMatches()).isEqualTo(2);
        // exactMatchFraction is over the OVERLAP, so it's 2/2 = 1.0
        assertThat(result.exactMatchFraction()).isEqualTo(1.0);
        assertThat(result.expectedLength()).isEqualTo(4);
        assertThat(result.decodedLength()).isEqualTo(2);
    }

    @Test
    void nullDecodedOutputIsTreatedAsEmpty() {
        byte[] payload = new byte[]{1};
        int[] reference = new int[]{1};
        SubbandHypothesisHarness.Result result = SubbandHypothesisHarness.evaluate(
                "null-output", payload, reference, bytes -> null);

        assertThat(result.threwException()).isFalse();
        assertThat(result.decodedLength()).isZero();
        assertThat(result.exactMatchFraction()).isZero();
    }

    @Test
    void reportRendersTabularSummary() {
        var ok = SubbandHypothesisHarness.evaluate(
                "ok", new byte[]{1}, new int[]{1},
                SubbandHypothesisHarness.identityByteDecoder());
        var bad = SubbandHypothesisHarness.evaluate(
                "bad", new byte[]{1}, new int[]{2}, b -> new int[]{0});
        var thrown = SubbandHypothesisHarness.evaluate(
                "thrown", new byte[]{1}, new int[]{1},
                b -> { throw new IllegalStateException("x"); });

        String text = SubbandHypothesisHarness.report(List.of(ok, bad, thrown));
        assertThat(text)
                .contains("fixture")
                .contains("ok")
                .contains("bad")
                .contains("thrown")
                .contains("IllegalStateException");
    }

    @Test
    void rejectsNullArguments() {
        Function<byte[], int[]> id = SubbandHypothesisHarness.identityByteDecoder();
        assertThatThrownBy(() -> SubbandHypothesisHarness.evaluate(null, new byte[0], new int[0], id))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SubbandHypothesisHarness.evaluate("x", null, new int[0], id))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SubbandHypothesisHarness.evaluate("x", new byte[0], null, id))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SubbandHypothesisHarness.evaluate("x", new byte[0], new int[0], null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
