package com.photographer.focusstack.cr3.processing;

/** Final sRGB quantization without a power evaluation for every channel. */
final class Srgb16Encoder {
    private static final int INTERVALS = 1 << 14;
    private static final int MAX_CODE = 65535;
    // A constant expression lets memory planning reserve first-use tables without initializing them.
    static final long TABLE_BYTES = (INTERVALS + 1L) * Double.BYTES + (MAX_CODE + 2L) * Long.BYTES;
    // For p = x^(1.0/2.4), pow's one-ulp error and the three rounded operations
    // bound each forward error by 2.51 * 2^-52 * 1.055 * p * MAX_CODE.
    // Two allowed evaluations can move a boundary by less than 27 input ulps,
    // including the first representable input (inverse condition number < 2.4).
    // Use 32 and the original
    // expression there, including any interpreter/JIT implementation variation.
    private static final int BOUNDARY_GUARD_ULPS = 32;

    private Srgb16Encoder() {}

    static short encode(double linear) {
        if (!(linear > 0.0)) { // Also preserves Math.round(NaN) == 0.
            return 0;
        }
        if (linear >= 1.0) {
            return (short) MAX_CODE;
        }
        if (linear <= 0.0031308) {
            return (short) referenceCode(linear);
        }

        double position = linear * INTERVALS;
        int bucket = (int) position;
        double lower = Tables.CURVE[bucket];
        int code = (int) (lower + (Tables.CURVE[bucket + 1] - lower) * (position - bucket) + 0.5);

        // Interpolation only proposes a code. Compare the original, continuous
        // input with the actual output-rounding boundaries; never quantize it first.
        long bits = Double.doubleToRawLongBits(linear);
        if (bits - Tables.BOUNDARIES[code] > BOUNDARY_GUARD_ULPS
                && Tables.BOUNDARIES[code + 1] - bits > BOUNDARY_GUARD_ULPS) {
            return (short) code;
        }
        while (bits < Tables.BOUNDARIES[code]) {
            code--;
        }
        while (bits >= Tables.BOUNDARIES[code + 1]) {
            code++;
        }
        if (bits - Tables.BOUNDARIES[code] <= BOUNDARY_GUARD_ULPS
                || Tables.BOUNDARIES[code + 1] - bits <= BOUNDARY_GUARD_ULPS) {
            return (short) referenceCode(linear);
        }
        return (short) code;
    }

    private static int referenceCode(double linear) {
        return (int) Math.round(MAX_CODE * SrgbConverter.linearToSrgb(linear));
    }

    /** Built only for sRGB output; linear development allocates neither table. */
    private static final class Tables {
        private static final double[] CURVE = new double[INTERVALS + 1];
        private static final long[] BOUNDARIES = new long[MAX_CODE + 2];

        static {
            for (int i = 0; i <= INTERVALS; i++) {
                CURVE[i] = MAX_CODE * SrgbConverter.linearToSrgb((double) i / INTERVALS);
            }
            for (int code = 1; code <= MAX_CODE; code++) {
                BOUNDARIES[code] = boundary(code);
            }
            BOUNDARIES[MAX_CODE + 1] = Double.doubleToRawLongBits(Double.POSITIVE_INFINITY);
        }

        private static long boundary(int code) {
            double encoded = (code - 0.5) / MAX_CODE;
            double linear = encoded <= 0.04045 ? encoded / 12.92
                    : Math.pow((encoded + 0.055) / 1.055, 2.4);
            // Find the first representable input assigned to this code by the
            // original expression, rather than trusting an inverse's rounding.
            for (int step = 0; step < 64; step++) {
                if (referenceCode(linear) < code) {
                    linear = Math.nextUp(linear);
                } else if (referenceCode(Math.nextDown(linear)) >= code) {
                    linear = Math.nextDown(linear);
                } else {
                    return Double.doubleToRawLongBits(linear);
                }
            }
            // A different inverse implementation can only affect startup cost.
            // Bound that cost with a lower-bound search over positive doubles.
            long below = 0;
            long above = Double.doubleToRawLongBits(1.0);
            while (above - below > 1) {
                long middle = below + (above - below) / 2;
                if (referenceCode(Double.longBitsToDouble(middle)) < code) {
                    below = middle;
                } else {
                    above = middle;
                }
            }
            return above;
        }
    }
}
