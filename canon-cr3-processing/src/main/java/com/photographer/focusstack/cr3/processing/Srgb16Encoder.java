package com.photographer.focusstack.cr3.processing;

/** Final sRGB quantization without a power evaluation for every channel. */
final class Srgb16Encoder {
    // Fifteen leading mantissa bits in each exponent range from 2^-9 through 1.
    // The remaining input bits still participate in the exact boundary comparison.
    private static final int SEED_COUNT = 9 * 32768;
    private static final int BUCKET_SHIFT = 37;
    private static final long BASE_BITS = 0x3f60000000000000L; // 2^-9.
    private static final long BUCKET_BITS = 1L << BUCKET_SHIFT;
    private static final int MAX_CODE = 65535;
    // A constant expression lets memory planning reserve first-use tables without initializing them.
    static final long TABLE_BYTES = (long) SEED_COUNT * Short.BYTES + (MAX_CODE + 2L) * Long.BYTES;
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

        long bits = Double.doubleToRawLongBits(linear);
        int bucket = (int) ((bits - BASE_BITS) >>> BUCKET_SHIFT);
        int code = Tables.SEEDS[bucket] & 0xffff;
        if (code == 0) {
            return (short) referenceCode(linear);
        }
        long distance = Tables.BOUNDARIES[code + 1] - bits;
        // Both raw encodings are nonnegative, so their difference cannot overflow
        // or equal Long.MIN_VALUE. The absolute-value guard is therefore exact.
        if (Math.abs(distance) <= BOUNDARY_GUARD_ULPS) return (short) referenceCode(linear);
        return (short) (code + (int) (distance >>> 63));
    }

    private static int referenceCode(double linear) {
        return (int) Math.round(MAX_CODE * SrgbConverter.linearToSrgb(linear));
    }

    /** Built only for sRGB output; linear development allocates neither table. */
    private static final class Tables {
        private static final long[] BOUNDARIES = new long[MAX_CODE + 2];
        private static final short[] SEEDS = new short[SEED_COUNT];

        static {
            for (int code = 1; code <= MAX_CODE; code++) {
                BOUNDARIES[code] = boundary(code);
            }
            BOUNDARIES[MAX_CODE + 1] = Double.doubleToRawLongBits(Double.POSITIVE_INFINITY);
            int code = 0;
            for (int bucket = 0; bucket < SEED_COUNT; bucket++) {
                long first = BASE_BITS + ((long) bucket << BUCKET_SHIFT);
                long end = first + BUCKET_BITS; // Exclusive: its predecessor belongs to this bucket.
                while (code < MAX_CODE && BOUNDARIES[code + 1] <= first) code++;
                // Certify the entire bucket against the original exact transitions.
                // Its first input is more than 32 representable steps above code's
                // transition. Because end is exclusive, its last input is at least
                // 33 steps below code+2's transition. Only code or code+1 is possible,
                // including the permitted variation between pow implementations.
                // Zero marks an uncertified bucket; nonlinear inputs have positive codes.
                // The max-code case uses the positive-infinity upper sentinel.
                if (code > 0 && first > BOUNDARIES[code] + BOUNDARY_GUARD_ULPS
                        && end <= BOUNDARIES[Math.min(code + 2, MAX_CODE + 1)] - BOUNDARY_GUARD_ULPS) {
                    SEEDS[bucket] = (short) code;
                }
            }
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
