package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.raw.codec.BitReader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Unified observation-only decoder for HYBRID and D8 K state. */
final class KAdaptationStateTracer {
    enum Rule {
        HYBRID,
        D8_ZERO_CONTRACT
    }

    enum Decision {
        ZERO_CONTRACT,
        HYBRID_INCREASE,
        HYBRID_DECREASE,
        BRACKET_INCREASE,
        BRACKET_DECREASE,
        HOLD
    }

    record Trace(int ordinal, int x, int y,
                 long bitStart, long bitEnd,
                 int kBefore, int kAfter,
                 int quotient, int remainder, int unsignedSymbol,
                 int signedResidual, int predictor, int reconstructed,
                 int zeroRunBefore,
                 long accumulatorBefore, long accumulatorAfter,
                 long sampleCountBefore, long sampleCountAfter,
                 int kStar, int kMin, int kMax,
                 Decision decision) {
    }

    record Result(int[] pixels, int symbolsDecoded, boolean exhausted,
                  List<Trace> traces, boolean traceTruncated) {
    }

    private KAdaptationStateTracer() {
    }

    /**
     * Fully decodes the plane while retaining at most {@code traceLimit} trace entries.
     * Used by unit tests that prove the observer does not alter decoder output.
     */
    static Result decode(byte[] payload, int width, int height, int bitDepth,
                         Rule rule, int traceLimit) {
        return decodeInternal(payload, width, height, bitDepth,
                rule, traceLimit, false);
    }

    /**
     * Decodes only the first {@code symbolLimit} symbols. D9 needs the first
     * divergence, not a complete sensor-plane reconstruction.
     */
    static Result decodePrefix(byte[] payload, int width, int height, int bitDepth,
                               Rule rule, int symbolLimit) {
        if (symbolLimit <= 0) {
            throw new IllegalArgumentException("symbolLimit must be positive");
        }
        return decodeInternal(payload, width, height, bitDepth,
                rule, symbolLimit, true);
    }

    private static Result decodeInternal(byte[] payload, int width, int height,
                                         int bitDepth, Rule rule, int traceLimit,
                                         boolean stopAtTraceLimit) {
        int[] pixels = new int[width * height];
        int max = (1 << bitDepth) - 1;
        CountingBits bits = new CountingBits(payload);
        List<Trace> traces = new ArrayList<>(Math.min(traceLimit, 1024));
        int symbols = 0;
        boolean exhausted = false;
        boolean traceTruncated = false;

        outer:
        for (int y = 0; y < height; y++) {
            int k = 1;
            long accumulator = 0;
            long sampleCount = 1;
            int zeroRun = 0;
            for (int x = 0; x < width; x++) {
                if (stopAtTraceLimit && symbols >= traceLimit) {
                    traceTruncated = true;
                    break outer;
                }

                int predictor = predictor(pixels, width, x, y);
                int kBefore = k;
                long accumulatorBefore = accumulator;
                long countBefore = sampleCount;
                int zeroRunBefore = zeroRun;
                Parsed parsed = new Parsed(bits.position());
                int unsigned;
                try {
                    unsigned = read(bits, kBefore, parsed);
                } catch (IOException | UnaryOverflow failure) {
                    exhausted = true;
                    unsigned = 0;
                }

                if (exhausted && unsigned == 0) {
                    int value = clamp(predictor, max);
                    pixels[y * width + x] = value;
                    if (!bits.hasMore()) {
                        if (!stopAtTraceLimit) {
                            pad(pixels, width, height, x, y, value);
                        }
                        break outer;
                    }
                    continue;
                }

                int magnitude = unsigned >>> 1;
                int signed = (unsigned & 1) == 0 ? magnitude : -magnitude;
                int value = clamp(predictor + signed, max);
                pixels[y * width + x] = value;

                int kStar = unsigned == 0
                        ? 0 : 31 - Integer.numberOfLeadingZeros(unsigned + 1);
                int kMin = Math.max(0, kStar - 1);
                int kMax = Math.min(CrxNormativeLosslessCandidate.K_MAX, kStar + 1);
                Decision decision;

                if (rule == Rule.HYBRID) {
                    int absolute = Math.abs(signed);
                    accumulator += absolute;
                    sampleCount++;
                    k = kBefore;
                    if (absolute > (1 << (kBefore + 1))
                            && kBefore < CrxNormativeLosslessCandidate.K_MAX) {
                        k++;
                        decision = Decision.HYBRID_INCREASE;
                    } else if (kBefore > 0
                            && accumulator <= (sampleCount << (kBefore - 1))) {
                        k--;
                        decision = Decision.HYBRID_DECREASE;
                    } else {
                        decision = Decision.HOLD;
                    }
                    if (sampleCount >= 1024) {
                        accumulator >>= 1;
                        sampleCount >>= 1;
                        if (sampleCount == 0) {
                            sampleCount = 1;
                        }
                    }
                } else if (unsigned == 0 && kBefore > 0) {
                    k = kBefore - 1;
                    decision = Decision.ZERO_CONTRACT;
                } else if (kBefore < kMin) {
                    k = kBefore + 1;
                    decision = Decision.BRACKET_INCREASE;
                } else if (kBefore > kMax) {
                    k = kBefore - 1;
                    decision = Decision.BRACKET_DECREASE;
                } else {
                    k = kBefore;
                    decision = Decision.HOLD;
                }

                if (traces.size() < traceLimit) {
                    traces.add(new Trace(symbols, x, y,
                            parsed.start, parsed.end,
                            kBefore, k,
                            parsed.quotient, parsed.remainder, unsigned,
                            signed, predictor, value,
                            zeroRunBefore,
                            accumulatorBefore, accumulator,
                            countBefore, sampleCount,
                            kStar, kMin, kMax, decision));
                } else {
                    traceTruncated = true;
                }

                zeroRun = unsigned == 0 ? zeroRun + 1 : 0;
                symbols++;
            }
        }

        return new Result(pixels, symbols, exhausted,
                List.copyOf(traces), traceTruncated);
    }

    private static int read(CountingBits bits, int k, Parsed parsed)
            throws IOException, UnaryOverflow {
        int quotient = 0;
        while (bits.readBit() == 1) {
            quotient++;
            if (quotient > 32) {
                throw new UnaryOverflow();
            }
        }
        int remainder = k == 0 ? 0 : bits.readBits(k);
        parsed.complete(quotient, remainder, bits.position());
        return (quotient << k) | remainder;
    }

    private static int predictor(int[] pixels, int width, int x, int y) {
        if (x == 0 && y == 0) {
            return 0;
        }
        if (y == 0) {
            return pixels[x - 1];
        }
        if (x == 0) {
            return pixels[(y - 1) * width];
        }
        int west = pixels[y * width + x - 1];
        int north = pixels[(y - 1) * width + x];
        int northWest = pixels[(y - 1) * width + x - 1];
        if (northWest >= Math.max(west, north)) {
            return Math.min(west, north);
        }
        if (northWest <= Math.min(west, north)) {
            return Math.max(west, north);
        }
        return west + north - northWest;
    }

    private static int clamp(int value, int max) {
        return Math.max(0, Math.min(value, max));
    }

    private static void pad(int[] pixels, int width, int height,
                            int x, int y, int value) {
        for (int xx = x + 1; xx < width; xx++) {
            pixels[y * width + xx] = value;
        }
        for (int yy = y + 1; yy < height; yy++) {
            for (int xx = 0; xx < width; xx++) {
                pixels[yy * width + xx] = value;
            }
        }
    }

    private static final class CountingBits {
        private final BitReader delegate;
        private long position;

        CountingBits(byte[] payload) {
            delegate = new BitReader(payload);
        }

        int readBit() throws IOException {
            int value = delegate.readBit();
            position++;
            return value;
        }

        int readBits(int count) throws IOException {
            int value = 0;
            for (int i = 0; i < count; i++) {
                value = (value << 1) | readBit();
            }
            return value;
        }

        long position() {
            return position;
        }

        boolean hasMore() {
            return delegate.hasMore();
        }
    }

    private static final class Parsed {
        private final long start;
        private long end;
        private int quotient;
        private int remainder;

        Parsed(long start) {
            this.start = start;
            this.end = start;
        }

        void complete(int quotient, int remainder, long end) {
            this.quotient = quotient;
            this.remainder = remainder;
            this.end = end;
        }
    }

    @SuppressWarnings("serial")
    private static final class UnaryOverflow extends Exception {
        UnaryOverflow() {
            super(null, null, false, false);
        }
    }
}
