package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.raw.codec.BitReader;

import java.io.IOException;

/**
 * Test-scope candidate decoder that implements the <i>normative</i> CRX
 * lossless entropy + MED-predictor pipeline as described in the format
 * specification supplied to the project on 2026-05-14.
 *
 * <p>This class is intentionally <b>not</b> wired into the production decoder
 * ({@code CrxLosslessRawDecoder}). It exists so that
 * {@link CrxNormativeLosslessCandidateIT} and focused discriminator tests can
 * A/B one research variable against the existing candidate on the complete
 * reference-backed fixture panel. Only after an integration-test win do we
 * patch production.</p>
 *
 * <h3>Differences vs. {@code CrxLosslessRawDecoder} (what can be tested)</h3>
 * <ul>
 *   <li><b>Unary prefix direction</b> - production reads <i>zeros until a
 *       one</i>; this candidate can read <i>ones until a zero</i>.</li>
 *   <li><b>Initial Rice K</b> - production uses {@code K_INIT = 0}; this
 *       candidate can use {@code K_INIT = 1} for lossless planes.</li>
 *   <li><b>K reset per row</b> - production carries the K accumulator across
 *       the entire plane; this candidate can reset adaptive state at the
 *       left-most pixel of every row.</li>
 *   <li><b>Sign mapping</b> - production uses zigzag; this candidate can use
 *       pure sign-bit separation.</li>
 *   <li><b>Escape at unary 15</b> - production has no escape; this candidate
 *       can fall back to a fixed-width literal when the unary prefix reaches
 *       15.</li>
 *   <li><b>K clamp</b> - production clamps to {@code bitDepth}; this candidate
 *       clamps to 12.</li>
 *   <li><b>K adaptation</b> - the historical candidate uses a hybrid
 *       per-symbol/accumulator rule. D6 can instead move K one step toward a
 *       bracket derived from the current unsigned Rice symbol.</li>
 * </ul>
 *
 * <p>Predictor and seeding are unchanged: MED with {@code W} on the first row,
 * {@code N} on the first column, and a configurable top-left seed.</p>
 *
 * <p>No production code is touched. No proprietary source code was consulted.</p>
 */
final class CrxNormativeLosslessCandidate {

    /** Hard cap on unary length before we declare the bitstream corrupt. */
    private static final int MAX_UNARY = 32;
    /** Unary length that triggers the fixed-width escape. */
    private static final int ESCAPE_UNARY_TRIGGER = 15;
    /** K clamp used by the candidate. */
    static final int K_MAX = 12;

    /** Selectable K-update semantics for isolated research experiments. */
    enum KAdaptation {
        /** Existing candidate rule: magnitude increase plus running-average decrease. */
        HYBRID_ACCUMULATOR,
        /** D6 rule: move one step toward the bracket around floor(log2(S + 1)). */
        SYMBOL_BRACKET
    }

    /** Tunable knobs for the integration tests to sweep. */
    static final class Options {
        /** Initial K for every row. */
        int kInit = 1;
        /** Reset K plus adaptation state at the left edge of every row. */
        boolean resetKPerRow = true;
        /** Use ones-until-zero (true) vs. zeros-until-one (false) for unary. */
        boolean unaryOnesUntilZero = true;
        /** Use pure sign-bit mapping (true) vs. zigzag (false). */
        boolean signBitMapping = true;
        /** Enable the unary-15 escape. */
        boolean escapeAtUnary15 = true;
        /** Top-left seed pixel. */
        int firstPixelSeed = 0;
        /** Bit width used by the fixed-width escape literal. */
        int escapeBitWidth = 14;
        /** K-update rule; defaults to the historical candidate behavior. */
        KAdaptation kAdaptation = KAdaptation.HYBRID_ACCUMULATOR;

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
                    + " kAdapt=" + kAdaptation;
        }
    }

    /** Result of one decode pass. */
    static final class Result {
        final int[] pixels;
        final int symbolsDecoded;
        final int escapeHits;
        final int unaryOverflows;
        final boolean streamExhausted;
        /** Bytes consumed by the BitReader when the plane finished or exhausted. */
        final int consumedBytes;
        /** X coordinate of the first exhaustion pixel, or -1 if the stream finished cleanly. */
        final int firstExhaustX;
        /** Y coordinate of the first exhaustion pixel, or -1 if the stream finished cleanly. */
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

    private CrxNormativeLosslessCandidate() {
    }

    /**
     * Decodes a single lossless plane payload into a {@code width x height}
     * int matrix using the candidate pipeline configured by {@code opt}.
     */
    static Result decodePlane(byte[] payload, int width, int height, int bitDepth, Options opt) {
        int[] out = new int[width * height];
        int maxVal = (1 << bitDepth) - 1;
        BitReader r = new BitReader(payload);

        int k = opt.kInit;
        long accum = 0;
        long cnt = 1;
        int symbols = 0;
        int escapes = 0;
        int unaryOverflows = 0;
        boolean exhausted = false;
        int firstExhaustX = -1;
        int firstExhaustY = -1;

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
                    int w = out[y * width + (x - 1)];
                    int n = out[(y - 1) * width + x];
                    int nw = out[(y - 1) * width + (x - 1)];
                    predicted = medPredict(w, n, nw);
                }

                int residual;
                try {
                    residual = readSymbol(r, k, opt);
                } catch (EscapeHit e) {
                    residual = e.value;
                    escapes++;
                } catch (UnaryOverflow e) {
                    unaryOverflows++;
                    if (firstExhaustX < 0) {
                        firstExhaustX = x;
                        firstExhaustY = y;
                    }
                    exhausted = true;
                    residual = 0;
                } catch (IOException e) {
                    if (firstExhaustX < 0) {
                        firstExhaustX = x;
                        firstExhaustY = y;
                    }
                    exhausted = true;
                    residual = 0;
                }
                if (exhausted && residual == 0) {
                    out[y * width + x] = clamp(predicted, maxVal);
                    if (!r.hasMore()) {
                        for (int xx = x + 1; xx < width; xx++) {
                            out[y * width + xx] = clamp(predicted, maxVal);
                        }
                        for (int yy = y + 1; yy < height; yy++) {
                            for (int xx = 0; xx < width; xx++) {
                                out[yy * width + xx] = clamp(predicted, maxVal);
                            }
                        }
                        break outer;
                    }
                    continue;
                }

                int signed = mapSigned(residual, opt);
                int pixel = clamp(predicted + signed, maxVal);
                out[y * width + x] = pixel;
                symbols++;

                if (opt.kAdaptation == KAdaptation.SYMBOL_BRACKET) {
                    k = symbolBracketNextK(k, residual);
                } else {
                    int abs = signed < 0 ? -signed : signed;
                    accum += abs;
                    cnt++;
                    if (abs > (1 << (k + 1)) && k < K_MAX) {
                        k++;
                    } else if (k > 0 && accum <= (cnt << (k - 1))) {
                        k--;
                    }
                    if (cnt >= 1024) {
                        accum >>= 1;
                        cnt >>= 1;
                        if (cnt == 0) {
                            cnt = 1;
                        }
                    }
                }
            }
        }

        return new Result(out, symbols, escapes, unaryOverflows, exhausted,
                r.bytePosition(), firstExhaustX, firstExhaustY);
    }

    /**
     * Moves K by at most one step toward the bracket around
     * {@code floor(log2(unsignedSymbol + 1))}.
     */
    static int symbolBracketNextK(int currentK, int unsignedSymbol) {
        if (currentK < 0 || currentK > K_MAX) {
            throw new IllegalArgumentException("currentK must be in 0.." + K_MAX);
        }
        if (unsignedSymbol < 0) {
            throw new IllegalArgumentException("unsignedSymbol must be non-negative");
        }

        int kStar = unsignedSymbol == 0
                ? 0
                : 31 - Integer.numberOfLeadingZeros(unsignedSymbol + 1);
        int kMin = Math.max(0, kStar - 1);
        int kMax = Math.min(K_MAX, kStar + 1);

        if (currentK < kMin) {
            return Math.min(K_MAX, currentK + 1);
        }
        if (currentK > kMax) {
            return Math.max(0, currentK - 1);
        }
        return currentK;
    }

    /** MED predictor (JPEG-LS Annex A.3). Same as production. */
    private static int medPredict(int w, int n, int nw) {
        if (nw >= Math.max(w, n)) {
            return Math.min(w, n);
        }
        if (nw <= Math.min(w, n)) {
            return Math.max(w, n);
        }
        return w + n - nw;
    }

    private static int clamp(int value, int max) {
        return value < 0 ? 0 : (value > max ? max : value);
    }

    /** One Rice/Golomb symbol read with optional unary-15 escape. */
    private static int readSymbol(BitReader reader, int k, Options opt)
            throws IOException, EscapeHit, UnaryOverflow {
        int q = 0;
        if (opt.unaryOnesUntilZero) {
            while (reader.readBit() == 1) {
                q++;
                if (opt.escapeAtUnary15 && q == ESCAPE_UNARY_TRIGGER) {
                    int literal = reader.readBits(opt.escapeBitWidth);
                    throw new EscapeHit(literal);
                }
                if (q > MAX_UNARY) {
                    throw new UnaryOverflow();
                }
            }
        } else {
            while (reader.readBit() == 0) {
                q++;
                if (opt.escapeAtUnary15 && q == ESCAPE_UNARY_TRIGGER) {
                    int literal = reader.readBits(opt.escapeBitWidth);
                    throw new EscapeHit(literal);
                }
                if (q > MAX_UNARY) {
                    throw new UnaryOverflow();
                }
            }
        }
        int remainder = k > 0 ? reader.readBits(k) : 0;
        return (q << k) | remainder;
    }

    /** Map an unsigned decoded value back to a signed residual. */
    private static int mapSigned(int value, Options opt) {
        if (opt.signBitMapping) {
            int magnitude = value >>> 1;
            return (value & 1) == 0 ? magnitude : -magnitude;
        }
        return (value & 1) == 0 ? value >>> 1 : -((value >>> 1) + 1);
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
        UnaryOverflow() {
            super(null, null, false, false);
        }
    }
}
