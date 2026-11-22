package com.photographer.focusstack.cr3.raw.codec;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;

import static jdk.incubator.vector.VectorOperators.ASHR;

/** Horizontal 5/3 lifting when all intermediate sums are known to fit in int. */
final class CrxWaveletVectors implements CrxWaveletTile.VectorKernel {
    private static final VectorSpecies<Integer> LANES = IntVector.SPECIES_256;
    private static final VectorShuffle<Integer> ZIP_LOW = VectorShuffle.makeZip(LANES, 0);
    private static final VectorShuffle<Integer> ZIP_HIGH = VectorShuffle.makeZip(LANES, 1);
    private static final boolean SUPPORTED = IntVector.SPECIES_PREFERRED.vectorBitSize() >= LANES.vectorBitSize();

    @Override
    public int lift(int[] low, int[] high, int[] output, int lowWidth, int highWidth, int origin,
                    int width, int firstEven) {
        // A vector pair needs the updated even on both sides of its odd sample.
        int pairs = Math.min(width / 2, Math.min(lowWidth - 1, highWidth - origin - 1));
        if (!SUPPORTED || pairs <= LANES.length()) {
            return 0;
        }
        int end = 1 + LANES.loopBound(pairs - 1);

        // The left edge can repeat its first detail value in lieu of a stored neighbour.
        int secondEven = low[1] - ((high[origin] + high[origin + 1] + 2) >> 2);
        output[1] = high[origin] + ((firstEven + secondEven) >> 1);
        for (int pair = 1; pair < end; pair += LANES.length()) {
            int detail = pair + origin;
            IntVector left = IntVector.fromArray(LANES, high, detail - 1);
            IntVector current = IntVector.fromArray(LANES, high, detail);
            IntVector right = IntVector.fromArray(LANES, high, detail + 1);
            IntVector even = update(IntVector.fromArray(LANES, low, pair), left, current);
            IntVector nextEven = update(IntVector.fromArray(LANES, low, pair + 1), current, right);
            IntVector odd = current.add(even.add(nextEven).lanewise(ASHR, 1));
            even.rearrange(ZIP_LOW, odd).intoArray(output, 2 * pair);
            even.rearrange(ZIP_HIGH, odd).intoArray(output, 2 * pair + LANES.length());
        }
        return end;
    }

    private static IntVector update(IntVector low, IntVector left, IntVector right) {
        return low.sub(left.add(right).add(2).lanewise(ASHR, 2));
    }
}
