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

    private static final int MAX_LONG_K = Long.SIZE - 1;

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

    static long codeLength(long symbol, int k) {
        requireSymbol(symbol);
        requireK(k, "k");
        long quotient = symbol >>> k;
        long suffixAndStop = 1L + k;
        if (quotient > Long.MAX_VALUE - suffixAndStop) {
            return Long.MAX_VALUE;
        }
        return quotient + suffixAndStop;
    }

    static Range optimalRange(long symbol, int maxK) {
        requireSymbol(symbol);
        requireK(maxK, "maxK");

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

    static int nextK(int currentK, long tentativeNextSymbol, int maxK) {
        requireK(maxK, "maxK");
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

    private static void requireK(int k, String name) {
        if (k < 0 || k > MAX_LONG_K) {
            throw new IllegalArgumentException(name + " must be in 0.." + MAX_LONG_K);
        }
    }

    private static void requireSymbol(long symbol) {
        if (symbol < 0) {
            throw new IllegalArgumentException("symbol must be non-negative");
        }
    }
}
