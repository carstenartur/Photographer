package com.photographer.focusstack.cr3.research;

/**
 * Pure model of the base K-range rule disclosed around FIG. 12, steps S1204/S1206,
 * of Canon patent application US 2016/0323602 A1.
 *
 * <p>This class deliberately models only the finite-domain Golomb-Rice code-length
 * optimum and the one-step movement toward that optimum. It does not model CRX
 * container semantics, initial K, subband identity, the LL {@code Sd} estimate,
 * or the optional {@code Kd} correction from S1207.</p>
 */
final class PatentGolombRiceKRange {

    record Range(int minK, int maxK, long minimumCodeLength) {
        Range {
            if (minK < 0 || maxK < minK) {
                throw new IllegalArgumentException("invalid K range");
            }
            if (minimumCodeLength <= 0) {
                throw new IllegalArgumentException("code length must be positive");
            }
        }

        boolean contains(int k) {
            return k >= minK && k <= maxK;
        }
    }

    private PatentGolombRiceKRange() {
    }

    /**
     * Returns {@code (S >> K) + 1 + K}, the code length disclosed by FIG. 10A/10B.
     */
    static long codeLength(long symbol, int k) {
        requireSymbol(symbol);
        if (k < 0 || k > 62) {
            throw new IllegalArgumentException("k must be in 0..62");
        }
        long quotient = symbol >>> k;
        long suffixAndStop = 1L + k;
        if (quotient > Long.MAX_VALUE - suffixAndStop) {
            return Long.MAX_VALUE;
        }
        return quotient + suffixAndStop;
    }

    /**
     * Finds the lowest and highest K attaining the minimum code length in
     * {@code 0..maxK}. This is the exact finite-domain definition of Kmin/Kmax.
     */
    static Range optimalRange(long symbol, int maxK) {
        requireSymbol(symbol);
        if (maxK < 0 || maxK > 62) {
            throw new IllegalArgumentException("maxK must be in 0..62");
        }

        long minimum = Long.MAX_VALUE;
        int minimumK = -1;
        int maximumK = -1;
        for (int k = 0; k <= maxK; k++) {
            long length = codeLength(symbol, k);
            if (length < minimum) {
                minimum = length;
                minimumK = k;
                maximumK = k;
            } else if (length == minimum) {
                maximumK = k;
            }
        }
        return new Range(minimumK, maximumK, minimum);
    }

    /**
     * Applies the disclosed base update: increase or decrease K by one when it
     * lies outside the optimal range, otherwise hold it.
     */
    static int nextK(int currentK, long tentativeNextSymbol, int maxK) {
        if (currentK < 0 || currentK > maxK) {
            throw new IllegalArgumentException("currentK must be in 0..maxK");
        }
        Range range = optimalRange(tentativeNextSymbol, maxK);
        if (currentK < range.minK()) {
            return currentK + 1;
        }
        if (currentK > range.maxK()) {
            return currentK - 1;
        }
        return currentK;
    }

    private static void requireSymbol(long symbol) {
        if (symbol < 0) {
            throw new IllegalArgumentException("symbol must be non-negative");
        }
    }
}
