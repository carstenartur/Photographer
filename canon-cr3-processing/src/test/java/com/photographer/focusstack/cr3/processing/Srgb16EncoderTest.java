package com.photographer.focusstack.cr3.processing;

import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Preserve the original continuous transfer and final rounding, especially at half codes. */
class Srgb16EncoderTest {
    @Test
    void preservesClippingSpecialValuesAndTheTransferJoin() {
        for (double value : new double[]{Double.NEGATIVE_INFINITY, -1, -0.0, 0.0,
                Double.MIN_VALUE, Math.nextDown(0.0031308), 0.0031308, Math.nextUp(0.0031308),
                Math.nextDown(1.0), 1, 2, Double.POSITIVE_INFINITY, Double.NaN}) {
            assertSameCode(value);
        }
    }

    @Test
    void preservesEveryNativeFourteenAndSixteenBitInputAndContinuousValues() {
        for (int code = 0; code <= 65535; code++) {
            assertSameCode(code / 65535.0);
            if (code <= 16383) {
                assertSameCode(code / 16383.0);
            }
        }
        var random = new SplittableRandom(0x6b5db976af42L);
        for (int i = 0; i < 1_000_000; i++) {
            double value = random.nextDouble();
            assertSameCode(value);
            // Exposure values near black exercise the high-slope part of the transfer.
            assertSameCode(value * 0.01);
        }
    }

    @Test
    void preservesAdjacentDoublesAroundEveryOutputRoundingBoundary() {
        for (int code = 1; code <= 65535; code++) {
            double encoded = (code - 0.5) / 65535.0;
            double inverse = encoded <= 0.04045 ? encoded / 12.92
                    : Math.pow((encoded + 0.055) / 1.055, 2.4);
            assertSameCode(inverse);
            double below = inverse;
            double above = inverse;
            // The mathematical inverse is a guide; the reference includes Java's actual rounding.
            for (int step = 0; step < 96; step++) {
                below = Math.nextDown(below);
                above = Math.nextUp(above);
                assertSameCode(below);
                assertSameCode(above);
            }
        }
    }

    private static void assertSameCode(double linear) {
        int expected = (int) Math.round(65535.0 * SrgbConverter.linearToSrgb(Math.clamp(linear, 0.0, 1.0)));
        int actual = Srgb16Encoder.encode(linear) & 0xffff;
        if (expected != actual) {
            assertEquals(expected, actual, "linear=" + Double.toHexString(linear));
        }
    }
}
