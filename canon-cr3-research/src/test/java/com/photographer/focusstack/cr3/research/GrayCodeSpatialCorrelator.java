package com.photographer.focusstack.cr3.research;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Correlates an observed bit-plane signature with predeclared Gray-code cells. */
final class GrayCodeSpatialCorrelator {

    record Candidate(int coordinate, boolean inverted, int hammingDistance) {
        Candidate {
            if (coordinate < 0 || hammingDistance < 0) {
                throw new IllegalArgumentException("Candidate values must not be negative");
            }
        }
    }

    record Result(
            int cells,
            int observedBits,
            int requiredBits,
            int minimumDistance,
            List<Candidate> bestCandidates) {
        Result {
            bestCandidates = List.copyOf(bestCandidates);
        }

        boolean exactUnique() {
            return minimumDistance == 0 && bestCandidates.size() == 1;
        }

        Candidate uniqueCandidate() {
            if (!exactUnique()) {
                throw new IllegalStateException("Gray-code correlation is not exact and unique");
            }
            return bestCandidates.getFirst();
        }
    }

    private GrayCodeSpatialCorrelator() {
    }

    static Result correlate(boolean[] observed, int cells) {
        Objects.requireNonNull(observed, "observed");
        if (cells < 2) {
            throw new IllegalArgumentException("cells must be at least 2");
        }
        int requiredBits = Integer.SIZE - Integer.numberOfLeadingZeros(cells - 1);
        if (observed.length < 1 || observed.length > requiredBits) {
            throw new IllegalArgumentException(
                    "observed bit count must be between 1 and " + requiredBits);
        }

        List<Candidate> candidates = new ArrayList<>(cells * 2);
        int minimumDistance = Integer.MAX_VALUE;
        for (int coordinate = 0; coordinate < cells; coordinate++) {
            int grayCode = coordinate ^ (coordinate >>> 1);
            int directDistance = distance(observed, grayCode, false);
            int invertedDistance = distance(observed, grayCode, true);
            minimumDistance = Math.min(minimumDistance, Math.min(directDistance, invertedDistance));
            candidates.add(new Candidate(coordinate, false, directDistance));
            candidates.add(new Candidate(coordinate, true, invertedDistance));
        }

        int bestDistance = minimumDistance;
        List<Candidate> best = candidates.stream()
                .filter(candidate -> candidate.hammingDistance() == bestDistance)
                .sorted(Comparator.comparingInt(Candidate::coordinate)
                        .thenComparing(Candidate::inverted))
                .toList();
        return new Result(cells, observed.length, requiredBits, bestDistance, best);
    }

    static boolean[] codeWord(int coordinate, int cells) {
        if (coordinate < 0 || coordinate >= cells) {
            throw new IllegalArgumentException("coordinate is outside the declared cell range");
        }
        int bits = Integer.SIZE - Integer.numberOfLeadingZeros(cells - 1);
        int grayCode = coordinate ^ (coordinate >>> 1);
        boolean[] result = new boolean[bits];
        for (int bit = 0; bit < bits; bit++) {
            result[bit] = ((grayCode >>> bit) & 1) != 0;
        }
        return result;
    }

    private static int distance(boolean[] observed, int grayCode, boolean inverted) {
        int distance = 0;
        for (int bit = 0; bit < observed.length; bit++) {
            boolean expected = ((grayCode >>> bit) & 1) != 0;
            if (inverted) {
                expected = !expected;
            }
            if (observed[bit] != expected) {
                distance++;
            }
        }
        return distance;
    }
}
