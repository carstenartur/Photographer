package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.raw.codec.BitReader;

import java.io.IOException;

/**
 * Test-scope candidate decoder for isolated CRX lossless research.
 * This class is not wired into the production decoder.
 */
final class CrxNormativeLosslessCandidate {
    private static final int MAX_UNARY = 32;
    private static final int ESCAPE_UNARY_TRIGGER = 15;
    static final int K_MAX = 12;

    enum KAdaptation {
        HYBRID_ACCUMULATOR,
        SYMBOL_BRACKET
    }

    @FunctionalInterface
    interface TraceSink {
        boolean onSymbol(SymbolTrace trace);
    }

    record SymbolTrace(int ordinal, int x, int y, long bitStart, long bitEnd,
                       int kBefore, int quotient, int remainder, int unsignedSymbol,
                       int signedResidual, int predictor, int reconstructed, int kAfter) {}

    static final class Options {
        int kInit = 1;
        boolean resetKPerRow = true;
        boolean unaryOnesUntilZero = true;
        boolean signBitMapping = true;
        boolean escapeAtUnary15 = true;
        int firstPixelSeed = 0;
        int escapeBitWidth = 14;
        KAdaptation kAdaptation = KAdaptation.HYBRID_ACCUMULATOR;
        TraceSink traceSink;

        Options copy() {
            Options o = new Options();
            o.kInit = kInit;
            o.resetKPerRow = resetKPerRow;
            o.unaryOnesUntilZero = unaryOnesUntilZero;
            o.signBitMapping = signBitMapping;
            o.escapeAtUnary15 = escapeAtUnary15;
            o.firstPixelSeed = firstPixelSeed;
            o.escapeBitWidth = escapeBitWidth;
            o.kAdaptation = kAdaptation;
            o.traceSink = traceSink;
            return o;
        }

        @Override
        public String toString() {
            return "kInit=" + kInit
                    + " resetRow=" + resetKPerRow
                    + " unary=" + (unaryOnesUntilZero ? "1s->0" : "0s->1")
                    + " sign=" + (signBitMapping ? "BIT0" : "ZIGZAG")
                    + " esc15=" + escapeAtUnary15
                    + " seed=" + firstPixelSeed
                    + " escW=" + escapeBitWidth
                    + " kAdapt=" + kAdaptation
                    + " trace=" + (traceSink != null);
        }
    }

    static final class Result {
        final int[] pixels;
        final int symbolsDecoded;
        final int escapeHits;
        final int unaryOverflows;
        final boolean streamExhausted;
        final int consumedBytes;
        final int firstExhaustX;
        final int firstExhaustY;

        Result(int[] pixels, int symbolsDecoded, int escapeHits,
               int unaryOverflows, boolean streamExhausted,
               int consumedBytes, int firstExhaustX, int firstExhaustY) {
            this.pixels = pixels;
            this.symbolsDecoded = symbolsDecoded;
            this.escapeHits = escapeHits;
            this.unaryOverflows = unaryOverflows;
            this.streamExhausted = streamExhausted;
            this.consumedBytes = consumedBytes;
            this.firstExhaustX = firstExhaustX;
            this.firstExhaustY = firstExhaustY;
        }
    }

    private CrxNormativeLosslessCandidate() {}

    static Result decodePlane(byte[] payload, int width, int height, int bitDepth, Options opt) {
        int[] out = new int[width * height];
        int maxVal = (1 << bitDepth) - 1;
        CountingBitReader reader = new CountingBitReader(payload);
        int k = opt.kInit;
        long accum = 0;
        long cnt = 1;
        int symbols = 0;
        int escapes = 0;
        int unaryOverflows = 0;
        boolean exhausted = false;
        int firstExhaustX = -1;
        int firstExhaustY = -1;
        boolean traceActive = opt.traceSink != null;
        MutableSymbol symbolParts = traceActive ? new MutableSymbol() : null;

        outer:
        for (int y = 0; y < height; y++) {
            if (opt.resetKPerRow) {
                k = opt.kInit;
                accum = 0;
                cnt = 1;
            }
            for (int x = 0; x < width; x++) {
                int predicted;
                if (x == 0 && y == 0) {
                    predicted = opt.firstPixelSeed;
                } else if (y == 0) {
                    predicted = out[x - 1];
                } else if (x == 0) {
                    predicted = out[(y - 1) * width];
                } else {
                    int w = out[y * width + x - 1];
                    int n = out[(y - 1) * width + x];
                    int nw = out[(y - 1) * width + x - 1];
                    predicted = medPredict(w, n, nw);
                }

                int kBefore = k;
                MutableSymbol activeParts = traceActive ? symbolParts : null;
                int residual;
                try {
                    residual = readSymbol(reader, kBefore, opt, activeParts);
                } catch (EscapeHit e) {
                    residual = e.value;
                    escapes++;
                } catch (UnaryOverflow | IOException e) {
                    if (e instanceof UnaryOverflow) unaryOverflows++;
                    if (firstExhaustX < 0) {
                        firstExhaustX = x;
                        firstExhaustY = y;
                    }
                    exhausted = true;
                    residual = 0;
                }
                if (exhausted && residual == 0) {
                    out[y * width + x] = clamp(predicted, maxVal);
                    if (!reader.hasMore()) {
                        for (int xx = x + 1; xx < width; xx++) out[y * width + xx] = clamp(predicted, maxVal);
                        for (int yy = y + 1; yy < height; yy++) {
                            for (int xx = 0; xx < width; xx++) out[yy * width + xx] = clamp(predicted, maxVal);
                        }
                        break outer;
                    }
                    continue;
                }

                int signed = mapSigned(residual, opt);
                int pixel = clamp(predicted + signed, maxVal);
                out[y * width + x] = pixel;

                if (opt.kAdaptation == KAdaptation.SYMBOL_BRACKET) {
                    k = symbolBracketNextK(kBefore, residual);
                } else {
                    int abs = signed < 0 ? -signed : signed;
                    accum += abs;
                    cnt++;
                    k = kBefore;
                    if (abs > (1 << (kBefore + 1)) && kBefore < K_MAX) {
                        k++;
                    } else if (kBefore > 0 && accum <= (cnt << (kBefore - 1))) {
                        k--;
                    }
                    if (cnt >= 1024) {
                        accum >>= 1;
                        cnt >>= 1;
                        if (cnt == 0) cnt = 1;
                    }
                }

                if (traceActive) {
                    traceActive = opt.traceSink.onSymbol(new SymbolTrace(
                            symbols, x, y, symbolParts.bitStart, symbolParts.bitEnd,
                            kBefore, symbolParts.quotient, symbolParts.remainder,
                            residual, signed, predicted, pixel, k));
                }
                symbols++;
            }
        }
        return new Result(out, symbols, escapes, unaryOverflows, exhausted,
                reader.bytePosition(), firstExhaustX, firstExhaustY);
    }

    static int symbolBracketNextK(int currentK, int unsignedSymbol) {
        if (currentK < 0 || currentK > K_MAX) {
            throw new IllegalArgumentException("currentK must be in 0.." + K_MAX);
        }
        if (unsignedSymbol < 0) {
            throw new IllegalArgumentException("unsignedSymbol must be non-negative");
        }
        int kStar = unsignedSymbol == 0
                ? 0 : 31 - Integer.numberOfLeadingZeros(unsignedSymbol + 1);
        int kMin = Math.max(0, kStar - 1);
        int kMax = Math.min(K_MAX, kStar + 1);
        if (currentK < kMin) return Math.min(K_MAX, currentK + 1);
        if (currentK > kMax) return Math.max(0, currentK - 1);
        return currentK;
    }

    private static int medPredict(int w, int n, int nw) {
        if (nw >= Math.max(w, n)) return Math.min(w, n);
        if (nw <= Math.min(w, n)) return Math.max(w, n);
        return w + n - nw;
    }

    private static int clamp(int value, int max) {
        return value < 0 ? 0 : Math.min(value, max);
    }

    private static int readSymbol(CountingBitReader reader, int k, Options opt,
                                  MutableSymbol trace)
            throws IOException, EscapeHit, UnaryOverflow {
        if (trace != null) trace.reset(reader.bitPosition());
        int quotient = 0;
        int continuation = opt.unaryOnesUntilZero ? 1 : 0;
        while (reader.readBit() == continuation) {
            quotient++;
            if (opt.escapeAtUnary15 && quotient == ESCAPE_UNARY_TRIGGER) {
                int literal = reader.readBits(opt.escapeBitWidth);
                if (trace != null) trace.complete(quotient, literal, reader.bitPosition());
                throw new EscapeHit(literal);
            }
            if (quotient > MAX_UNARY) throw new UnaryOverflow();
        }
        int remainder = k > 0 ? reader.readBits(k) : 0;
        if (trace != null) trace.complete(quotient, remainder, reader.bitPosition());
        return (quotient << k) | remainder;
    }

    private static int mapSigned(int value, Options opt) {
        if (opt.signBitMapping) {
            int magnitude = value >>> 1;
            return (value & 1) == 0 ? magnitude : -magnitude;
        }
        return (value & 1) == 0 ? value >>> 1 : -((value >>> 1) + 1);
    }

    private static final class CountingBitReader {
        private final BitReader delegate;
        private long bitsRead;

        CountingBitReader(byte[] payload) { delegate = new BitReader(payload); }
        int readBit() throws IOException { bitsRead++; return delegate.readBit(); }
        int readBits(int count) throws IOException {
            int value = 0;
            for (int i = 0; i < count; i++) value = (value << 1) | readBit();
            return value;
        }
        long bitPosition() { return bitsRead; }
        int bytePosition() { return delegate.bytePosition(); }
        boolean hasMore() { return delegate.hasMore(); }
    }

    private static final class MutableSymbol {
        long bitStart;
        long bitEnd;
        int quotient;
        int remainder;
        void reset(long start) {
            bitStart = start;
            bitEnd = start;
            quotient = 0;
            remainder = 0;
        }
        void complete(int q, int r, long end) {
            quotient = q;
            remainder = r;
            bitEnd = end;
        }
    }

    @SuppressWarnings("serial")
    private static final class EscapeHit extends Exception {
        final int value;
        EscapeHit(int value) {
            super(null, null, false, false);
            this.value = value;
        }
    }

    @SuppressWarnings("serial")
    private static final class UnaryOverflow extends Exception {
        UnaryOverflow() { super(null, null, false, false); }
    }
}
