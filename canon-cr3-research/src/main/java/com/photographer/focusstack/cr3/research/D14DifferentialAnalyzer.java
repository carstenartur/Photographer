package com.photographer.focusstack.cr3.research;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic, quality-independent primitives for the D14 controlled-pattern
 * experiment. This class does not decode CRX and does not select hypotheses by
 * rendered image quality. It only measures stable payload differences and
 * correlates pre-registered Gray-code observation vectors.
 */
public final class D14DifferentialAnalyzer {

    private D14DifferentialAnalyzer() {
    }

    /** A half-open changed-bit interval in most-significant-bit-first byte order. */
    public record BitInterval(long startInclusive, long endExclusive) {
        public BitInterval {
            if (startInclusive < 0 || endExclusive <= startInclusive) {
                throw new IllegalArgumentException("Invalid changed-bit interval");
            }
        }

        public long length() {
            return endExclusive - startInclusive;
        }
    }

    /** Per-byte noise mask derived only from same-pattern duplicate captures. */
    public record DuplicateNoiseBaseline(
            int comparedPairs,
            int payloadLength,
            int[] changedPairCounts,
            double maximumChangedPairFraction) {

        public DuplicateNoiseBaseline {
            if (comparedPairs < 1 || payloadLength < 0) {
                throw new IllegalArgumentException("Invalid duplicate baseline dimensions");
            }
            Objects.requireNonNull(changedPairCounts, "changedPairCounts");
            if (changedPairCounts.length != payloadLength) {
                throw new IllegalArgumentException("Noise count length does not match payload");
            }
            if (maximumChangedPairFraction < 0.0 || maximumChangedPairFraction > 1.0) {
                throw new IllegalArgumentException("Noise fraction must be within [0, 1]");
            }
            changedPairCounts = changedPairCounts.clone();
        }

        @Override
        public int[] changedPairCounts() {
            return changedPairCounts.clone();
        }

        public boolean isNoisyByte(int byteIndex) {
            if (byteIndex < 0 || byteIndex >= payloadLength) {
                throw new IndexOutOfBoundsException(byteIndex);
            }
            return changedPairCounts[byteIndex]
                    > maximumChangedPairFraction * comparedPairs;
        }
    }

    /** One stable Gray-code mapping candidate for a payload observation vector. */
    public record GrayCodeMatch(
            int cellIndex,
            int hammingDistance,
            int observedBits,
            boolean uniqueBest) {
        public GrayCodeMatch {
            if (cellIndex < 0 || hammingDistance < 0 || observedBits < 1) {
                throw new IllegalArgumentException("Invalid Gray-code match");
            }
        }
    }

    /**
     * Returns contiguous changed-bit intervals. Bytes are compared in their
     * original order and bit zero is the most significant bit of byte zero.
     */
    public static List<BitInterval> changedBitIntervals(byte[] left, byte[] right) {
        requireSameLength(left, right);
        List<BitInterval> intervals = new ArrayList<>();
        long runStart = -1;
        long totalBits = Math.multiplyExact((long) left.length, 8L);
        for (long bit = 0; bit < totalBits; bit++) {
            boolean changed = bit(left, bit) != bit(right, bit);
            if (changed && runStart < 0) {
                runStart = bit;
            } else if (!changed && runStart >= 0) {
                intervals.add(new BitInterval(runStart, bit));
                runStart = -1;
            }
        }
        if (runStart >= 0) {
            intervals.add(new BitInterval(runStart, totalBits));
        }
        return List.copyOf(intervals);
    }

    /** Builds a duplicate-only noise baseline without consulting pattern identity. */
    public static DuplicateNoiseBaseline duplicateNoiseBaseline(
            List<PayloadPair> duplicatePairs,
            double maximumChangedPairFraction) {
        Objects.requireNonNull(duplicatePairs, "duplicatePairs");
        if (duplicatePairs.isEmpty()) {
            throw new IllegalArgumentException("At least one duplicate pair is required");
        }
        int payloadLength = duplicatePairs.getFirst().left().length;
        int[] changedCounts = new int[payloadLength];
        for (PayloadPair pair : duplicatePairs) {
            requireSameLength(pair.left(), pair.right());
            if (pair.left().length != payloadLength) {
                throw new IllegalArgumentException("Duplicate payload lengths differ");
            }
            for (int index = 0; index < payloadLength; index++) {
                if (pair.left()[index] != pair.right()[index]) {
                    changedCounts[index]++;
                }
            }
        }
        return new DuplicateNoiseBaseline(
                duplicatePairs.size(), payloadLength, changedCounts,
                maximumChangedPairFraction);
    }

    /**
     * Measures changed bits after excluding every byte classified as duplicate
     * noise. The original bit offsets remain unchanged for later record mapping.
     */
    public static List<BitInterval> stableChangedBitIntervals(
            byte[] left,
            byte[] right,
            DuplicateNoiseBaseline baseline) {
        requireSameLength(left, right);
        Objects.requireNonNull(baseline, "baseline");
        if (left.length != baseline.payloadLength()) {
            throw new IllegalArgumentException("Payload length does not match baseline");
        }
        byte[] maskedLeft = left.clone();
        byte[] maskedRight = right.clone();
        for (int index = 0; index < left.length; index++) {
            if (baseline.isNoisyByte(index)) {
                maskedRight[index] = maskedLeft[index];
            }
        }
        return changedBitIntervals(maskedLeft, maskedRight);
    }

    /** Binary-reflected Gray-code observation vector, least-significant plane first. */
    public static BitSet grayCodeVector(int cellIndex, int bitCount) {
        if (cellIndex < 0 || bitCount < 1 || bitCount > Integer.SIZE - 1) {
            throw new IllegalArgumentException("Invalid Gray-code dimensions");
        }
        int maximumCells = 1 << bitCount;
        if (cellIndex >= maximumCells) {
            throw new IllegalArgumentException("Cell index exceeds Gray-code domain");
        }
        int gray = cellIndex ^ (cellIndex >>> 1);
        BitSet vector = new BitSet(bitCount);
        for (int bit = 0; bit < bitCount; bit++) {
            vector.set(bit, ((gray >>> bit) & 1) != 0);
        }
        return vector;
    }

    /**
     * Finds the bounded best Gray-code match. The result is marked unique only
     * when no other cell has the same Hamming distance.
     */
    public static GrayCodeMatch matchGrayCode(
            BitSet observed,
            int observedBits,
            int cellCount) {
        Objects.requireNonNull(observed, "observed");
        if (observedBits < 1 || observedBits > Integer.SIZE - 1) {
            throw new IllegalArgumentException("Invalid observed bit count");
        }
        if (cellCount < 1 || cellCount > (1 << observedBits)) {
            throw new IllegalArgumentException("Cell count exceeds Gray-code domain");
        }

        List<GrayCodeMatch> candidates = new ArrayList<>();
        for (int cell = 0; cell < cellCount; cell++) {
            BitSet difference = (BitSet) observed.clone();
            difference.xor(grayCodeVector(cell, observedBits));
            difference.clear(observedBits, Math.max(observedBits, difference.length()));
            candidates.add(new GrayCodeMatch(
                    cell, difference.cardinality(), observedBits, false));
        }
        candidates.sort(Comparator.comparingInt(GrayCodeMatch::hammingDistance)
                .thenComparingInt(GrayCodeMatch::cellIndex));
        GrayCodeMatch best = candidates.getFirst();
        boolean unique = candidates.size() == 1
                || candidates.get(1).hammingDistance() > best.hammingDistance();
        return new GrayCodeMatch(
                best.cellIndex(), best.hammingDistance(), best.observedBits(), unique);
    }

    /**
     * Correlates one observation vector per payload bit position. Positions are
     * sorted in the returned map to keep reports byte-for-byte deterministic.
     */
    public static Map<Long, GrayCodeMatch> correlateGrayCodeVectors(
            Map<Long, BitSet> observations,
            int observedBits,
            int cellCount,
            int maximumHammingDistance) {
        Objects.requireNonNull(observations, "observations");
        if (maximumHammingDistance < 0) {
            throw new IllegalArgumentException("Maximum Hamming distance must not be negative");
        }
        Map<Long, GrayCodeMatch> matches = new LinkedHashMap<>();
        observations.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    if (entry.getKey() < 0) {
                        throw new IllegalArgumentException("Payload bit positions must be non-negative");
                    }
                    GrayCodeMatch match = matchGrayCode(
                            entry.getValue(), observedBits, cellCount);
                    if (match.uniqueBest()
                            && match.hammingDistance() <= maximumHammingDistance) {
                        matches.put(entry.getKey(), match);
                    }
                });
        return Map.copyOf(matches);
    }

    public record PayloadPair(byte[] left, byte[] right) {
        public PayloadPair {
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
            left = left.clone();
            right = right.clone();
        }

        @Override
        public byte[] left() {
            return left.clone();
        }

        @Override
        public byte[] right() {
            return right.clone();
        }
    }

    private static boolean bit(byte[] data, long bitIndex) {
        int byteIndex = Math.toIntExact(bitIndex >>> 3);
        int shift = 7 - (int) (bitIndex & 7);
        return ((data[byteIndex] >>> shift) & 1) != 0;
    }

    private static void requireSameLength(byte[] left, byte[] right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        if (left.length != right.length) {
            throw new IllegalArgumentException("Payload lengths differ");
        }
    }
}
