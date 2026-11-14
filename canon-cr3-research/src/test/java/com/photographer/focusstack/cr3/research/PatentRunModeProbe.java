package com.photographer.focusstack.cr3.research;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * First-row discriminator for the uncentered, zero-boundary LL interpretation
 * of JP2017192077A figures 10 and 14. Only earlier reconstructed samples
 * select a flag; expected samples are used for scoring and the single RG1 seed.
 */
final class PatentRunModeProbe {
    static final int LIMIT = 64;

    record Flag(int x, int y, long bitOffset, int value) {}

    enum Stop { MISMATCH, RUN_CONTRADICTION, ZERO_RUN_CONTRADICTION, POSITIVE_RUN_UNRESOLVED, EXHAUSTED, PREFIX_LIMIT }

    record Prefix(int matched, Stop stop, long bitStart, long bitEnd, int expected,
                  Integer reconstructed, boolean guardAtStop, List<Flag> flags) {
        String flagOffsets() {
            return flags.stream().filter(f -> f.x() <= matched)
                    .map(f -> f.bitOffset() + "=" + f.value())
                    .collect(Collectors.joining(","));
        }
    }

    private final List<Flag> flags = new ArrayList<>();

    // A single, previously undecomposed band is conditionally treated as LL.
    boolean guard(int[] past, int width, int x, int y) {
        int west = x == 0 ? 0 : past[y * width + x - 1];
        int north = y == 0 ? 0 : past[(y - 1) * width + x];
        int northWest = x == 0 || y == 0 ? 0 : past[(y - 1) * width + x - 1];
        int northEast = x + 1 == width || y == 0 ? 0 : past[(y - 1) * width + x + 1];
        return west == north && west == northWest && west == northEast;
    }

    /** Zero-based, MSB-first payload offset, or -1 when the payload contains no one bit. */
    static long firstOneBit(byte[] payload) {
        for (int i = 0; i < payload.length; i++) {
            int octet = Byte.toUnsignedInt(payload[i]);
            if (octet != 0) {
                int leadingZeros = Integer.numberOfLeadingZeros(octet) - (Integer.SIZE - Byte.SIZE);
                return (long) i * Byte.SIZE + leadingZeros;
            }
        }
        return -1;
    }

    void recordFlag(int x, int y, long bitOffset, int value) {
        flags.add(new Flag(x, y, bitOffset, value));
    }

    static Prefix measure(byte[] payload, int bitDepth, int[] firstRow, boolean candidate) {
        if (firstRow.length == 0 || firstRow.length > LIMIT) {
            throw new IllegalArgumentException("Expected 1.." + LIMIT + " first-row samples");
        }
        var probe = new PatentRunModeProbe();
        var options = LibRawGuidedFirstDivergenceIT.baselineOptions(bitDepth);
        options.firstPixelSeed = firstRow[0];
        var traces = new ArrayList<CrxNormativeLosslessCandidate.SymbolTrace>();
        options.traceSink = trace -> {
            traces.add(trace);
            return trace.reconstructed() == firstRow[trace.x()];
        };
        var decoded = CrxNormativeLosslessCandidate.decodePlane(payload, firstRow.length, 1,
                bitDepth, options, candidate ? probe : null);
        for (var trace : traces) {
            int x = trace.x();
            if (candidate && (x == 0 || firstRow[x - 1] == 0) && firstRow[x] == 0) {
                // With equal (zero) first-row neighbours the patent's zero-run flag
                // means that the current sample differs from W, irrespective of Rice.
                Flag flag = probe.flags.stream().filter(f -> f.x() == x).findFirst().orElseThrow();
                return new Prefix(x, Stop.ZERO_RUN_CONTRADICTION, flag.bitOffset(),
                        flag.bitOffset() + 1, firstRow[x], null, true, List.copyOf(probe.flags));
            }
            if (trace.reconstructed() != firstRow[x]) {
                return new Prefix(x, Stop.MISMATCH, trace.bitStart(), trace.bitEnd(),
                        firstRow[x], trace.reconstructed(), x == 0 || firstRow[x - 1] == 0,
                        List.copyOf(probe.flags));
            }
        }
        // No earlier mismatch. A positive run promises at least one copy of W.
        if (!probe.flags.isEmpty() && probe.flags.getLast().value() == 1) {
            Flag flag = probe.flags.getLast();
            int x = flag.x();
            int west = x == 0 ? 0 : firstRow[x - 1];
            boolean possible = west == firstRow[x];
            return new Prefix(x, possible ? Stop.POSITIVE_RUN_UNRESOLVED : Stop.RUN_CONTRADICTION,
                    flag.bitOffset(), flag.bitOffset() + 1, firstRow[x], west, true,
                    List.copyOf(probe.flags));
        }
        int matched = traces.size();
        if (decoded.streamExhausted || matched != firstRow.length) {
            int x = Math.min(matched, firstRow.length - 1);
            return new Prefix(matched, Stop.EXHAUSTED, -1, -1, firstRow[x], null,
                    x == 0 || firstRow[x - 1] == 0, List.copyOf(probe.flags));
        }
        return new Prefix(matched, Stop.PREFIX_LIMIT, -1, -1, -1, null, false,
                List.copyOf(probe.flags));
    }

    static String compare(Prefix baseline, Prefix candidate) {
        if (candidate.stop() == Stop.POSITIVE_RUN_UNRESOLVED || candidate.stop() == Stop.EXHAUSTED) {
            return "UNRESOLVED";
        }
        int delta = candidate.matched() - baseline.matched();
        if (delta == 0) return "UNCHANGED";
        return delta > 0 ? "IMPROVED" : "WORSE";
    }
}
