package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.raw.codec.BitReader;

import java.io.IOException;

/**
 * Test‑scope candidate decoder that implements the <i>normative</i> CRX
 * lossless entropy + MED‑predictor pipeline as described in the format
 * specification supplied to the project on 2026‑05‑14.
 *
 * <p>This class is intentionally <b>not</b> wired into the production decoder
 * ({@code CrxLosslessRawDecoder}). It exists so that
 * {@link CrxNormativeLosslessCandidateIT} can A/B it against the production
 * decoder on the dark / flat / as‑is fixtures and report whether the normative
 * rules actually move the decode metrics in the right direction. Only after
 * an IT win do we patch production.</p>
 *
 * <h3>Differences vs. {@code CrxLosslessRawDecoder} (what is being tested)</h3>
 * <ul>
 *   <li><b>Unary prefix direction</b> — production reads <i>zeros until a
 *       one</i>; this candidate reads <i>ones until a zero</i>.</li>
 *   <li><b>Initial Rice K</b> — production uses {@code K_INIT = 0}; this
 *       candidate uses {@code K_INIT = 1} for lossless planes (the spec
 *       reserves {@code K_INIT = 0} for C‑RAW detail subbands, which this
 *       class does not handle).</li>
 *   <li><b>K reset per row</b> — production carries the K accumulator across
 *       the entire plane; this candidate resets the adaptive state at the
 *       left‑most pixel of every row.</li>
 *   <li><b>Sign mapping</b> — production uses zigzag
 *       ({@code odd → -((n-1)/2 + 1)}); this candidate uses pure sign‑bit
 *       separation ({@code A = V >> 1; sign = bit0}).</li>
 *   <li><b>Escape at unary 15</b> — production has no escape; this candidate
 *       falls back to a fixed‑width literal of {@code bitDepth} bits when the
 *       unary prefix reaches 15.</li>
 *   <li><b>K clamp</b> — production clamps to {@code bitDepth}; this candidate
 *       clamps to 12 per spec.</li>
 * </ul>
 *
 * <p>Predictor and seeding are unchanged: MED with {@code W} on the first row,
 * {@code N} on the first column, and a configurable top‑left seed (the spec
 * says "derived from the sensor profile"; in practice we sweep {@code 0} and
 * {@code 1 << (bd-1)} from the IT).</p>
 *
 * <p>No production code is touched. No proprietary code was consulted.</p>
 */
final class CrxNormativeLosslessCandidate {

    /** Hard cap on unary length before we declare the bitstream corrupt. */
    private static final int MAX_UNARY = 32;
    /** Unary length that triggers the fixed‑width escape (per spec §4). */
    private static final int ESCAPE_UNARY_TRIGGER = 15;
    /** K clamp per spec §5. */
    private static final int K_MAX = 12;

    /** Tunable knobs for the IT to sweep. */
    static final class Options {
        /** Initial K for every row. Spec says 1 for lossless. */
        int kInit = 1;
        /** Reset K + adaptation state at the left edge of every row. */
        boolean resetKPerRow = true;
        /** Use ones‑until‑zero (true) vs. zeros‑until‑one (false) for unary. */
        boolean unaryOnesUntilZero = true;
        /** Use pure sign‑bit mapping (true) vs. zigzag (false). */
        boolean signBitMapping = true;
        /** Enable the unary‑15 escape. */
        boolean escapeAtUnary15 = true;
        /** Top‑left seed pixel; the spec says "from sensor profile". */
        int firstPixelSeed = 0;
        /** Bit width used by the fixed‑width escape literal. */
        int escapeBitWidth = 14;

        Options copy() {
            Options o = new Options();
            o.kInit = kInit;
            o.resetKPerRow = resetKPerRow;
            o.unaryOnesUntilZero = unaryOnesUntilZero;
            o.signBitMapping = signBitMapping;
            o.escapeAtUnary15 = escapeAtUnary15;
            o.firstPixelSeed = firstPixelSeed;
            o.escapeBitWidth = escapeBitWidth;
            return o;
        }

        @Override public String toString() {
            return "kInit=" + kInit
                    + " resetRow=" + resetKPerRow
                    + " unary=" + (unaryOnesUntilZero ? "1s→0" : "0s→1")
                    + " sign=" + (signBitMapping ? "BIT0" : "ZIGZAG")
                    + " esc15=" + escapeAtUnary15
                    + " seed=" + firstPixelSeed
                    + " escW=" + escapeBitWidth;
        }
    }

    /** Result of one decode pass. */
    static final class Result {
        final int[] pixels;
        final int symbolsDecoded;
        final int escapeHits;
        final int unaryOverflows;
        final boolean streamExhausted;
        Result(int[] pixels, int symbolsDecoded, int escapeHits,
               int unaryOverflows, boolean streamExhausted) {
            this.pixels = pixels;
            this.symbolsDecoded = symbolsDecoded;
            this.escapeHits = escapeHits;
            this.unaryOverflows = unaryOverflows;
            this.streamExhausted = streamExhausted;
        }
    }

    private CrxNormativeLosslessCandidate() {}

    /**
     * Decodes a single lossless plane payload into a {@code width × height}
     * int matrix using the normative pipeline configured by {@code opt}.
     *
     * @param payload   the entropy‑coded bytes for one plane (one subband for lossless)
     * @param width     plane width in pixels
     * @param height    plane height in pixels
     * @param bitDepth  pixel bit depth (typically 14)
     * @param opt       knob configuration
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
                    predicted = out[x - 1];                    // first row → W
                } else if (x == 0) {
                    predicted = out[(y - 1) * width];          // first col → N
                } else {
                    int W = out[y * width + (x - 1)];
                    int N = out[(y - 1) * width + x];
                    int NW = out[(y - 1) * width + (x - 1)];
                    predicted = medPredict(W, N, NW);
                }

                int residual;
                try {
                    residual = readSymbol(r, k, bitDepth, opt);
                } catch (EscapeHit e) {
                    residual = e.value;
                    escapes++;
                } catch (UnaryOverflow e) {
                    unaryOverflows++;
                    exhausted = true;
                    residual = 0;
                } catch (IOException e) {
                    exhausted = true;
                    residual = 0;
                }
                if (exhausted && residual == 0) {
                    out[y * width + x] = clamp(predicted, maxVal);
                    if (!r.hasMore()) {
                        // Pad the rest with the predictor.
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

                // Per-symbol K bracket per spec §5: increment when the magnitude
                // exceeds 1<<(K+1); decrement when the running accumulator is
                // small relative to the running sample count at K-1.
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
                    if (cnt == 0) cnt = 1;
                }
            }
        }

        return new Result(out, symbols, escapes, unaryOverflows, exhausted);
    }

    /** MED predictor (JPEG-LS Annex A.3). Same as production. */
    private static int medPredict(int W, int N, int NW) {
        if (NW >= Math.max(W, N)) return Math.min(W, N);
        if (NW <= Math.min(W, N)) return Math.max(W, N);
        return W + N - NW;
    }

    private static int clamp(int v, int max) {
        return v < 0 ? 0 : (v > max ? max : v);
    }

    /** One Rice/Golomb symbol read with optional unary-15 escape. */
    private static int readSymbol(BitReader r, int k, int bitDepth, Options opt)
            throws IOException, EscapeHit, UnaryOverflow {
        int q = 0;
        if (opt.unaryOnesUntilZero) {
            while (r.readBit() == 1) {
                q++;
                if (opt.escapeAtUnary15 && q == ESCAPE_UNARY_TRIGGER) {
                    int lit = r.readBits(opt.escapeBitWidth);
                    throw new EscapeHit(lit);
                }
                if (q > MAX_UNARY) throw new UnaryOverflow();
            }
        } else {
            while (r.readBit() == 0) {
                q++;
                if (opt.escapeAtUnary15 && q == ESCAPE_UNARY_TRIGGER) {
                    int lit = r.readBits(opt.escapeBitWidth);
                    throw new EscapeHit(lit);
                }
                if (q > MAX_UNARY) throw new UnaryOverflow();
            }
        }
        int rem = k > 0 ? r.readBits(k) : 0;
        return (q << k) | rem;
    }

    /** Map an unsigned decoded value back to a signed residual. */
    private static int mapSigned(int v, Options opt) {
        if (opt.signBitMapping) {
            int a = v >>> 1;
            return (v & 1) == 0 ? a : -a;
        }
        // Zigzag (production)
        return (v & 1) == 0 ? v >>> 1 : -((v >>> 1) + 1);
    }

    @SuppressWarnings("serial")
    private static final class EscapeHit extends Exception {
        final int value;
        EscapeHit(int v) { super(null, null, false, false); this.value = v; }
    }
    @SuppressWarnings("serial")
    private static final class UnaryOverflow extends Exception {
        UnaryOverflow() { super(null, null, false, false); }
    }
}
