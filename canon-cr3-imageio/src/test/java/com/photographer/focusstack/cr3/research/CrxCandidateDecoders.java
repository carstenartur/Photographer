package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.raw.codec.BitReader;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Concrete candidate CRX subband decoders for the cleanroom hypothesis sweep.
 *
 * <p>Each candidate corresponds to a specific hypothesis from
 * {@code SubbandFixtures.md}. They are intentionally small and focused so the
 * sweep result table identifies which ingredients (predictor, k policy, sign
 * convention) move the needle.</p>
 *
 * <p>All candidates share the same outer loop: emit one int per pixel of an
 * (assumed) {@code planeWidth × planeHeight} grid by repeatedly decoding one
 * residual via Rice-Golomb, applying a predictor against already-decoded
 * neighbours, and clamping into [0, 2^bitDepth − 1]. They differ only in the
 * specific decisions documented on each builder method.</p>
 *
 * <p>None of these candidates touches production code. They exist to discriminate
 * hypotheses against ground truth supplied by {@code PreviewReferenceExtractor}.</p>
 */
public final class CrxCandidateDecoders {

    /**
     * Defensive cap on the unary prefix length when probing candidate decoders.
     * 64 is comfortably above any sane Rice-Golomb prefix for 14-bit residuals
     * and exists only to prevent runaway loops on garbage input — accuracy
     * scoring lives in the harness, not here.
     */
    private static final int MAX_UNARY_PROBE = 64;

    private CrxCandidateDecoders() {
        // utility class
    }

    /**
     * Returns the standard library of candidates plus their human-readable names,
     * for use in {@link SubbandHypothesisHarness} sweeps.
     *
     * @param planeWidth   width of the plane grid the candidates emit
     * @param planeHeight  height of the plane grid
     * @param bitDepth     bit depth (typically 14 for CR3)
     * @param quantValue   the q-param read from the subband header
     * @return a name → candidate map preserving insertion order
     */
    public static Map<String, Function<byte[], int[]>> standardLibrary(
            int planeWidth, int planeHeight, int bitDepth, int quantValue) {
        var map = new LinkedHashMap<String, Function<byte[], int[]>>();

        // Controls
        map.put("C0_zero",
                bytes -> new int[planeWidth * planeHeight]);
        map.put("C1_identity_byte",
                bytes -> SubbandHypothesisHarness.identityByteDecoder().apply(bytes));

        // C2: current production-style decoder (MED predictor + adaptive Rice, k starts at 0,
        // zigzag sign, JPEG-LS-style k adaptation). This should match what users see today.
        map.put("C2_med_zigzag_k0_adaptive",
                bytes -> safeDecode(bytes, planeWidth, planeHeight, bitDepth,
                        new Config(Predictor.MED, SignConv.ZIGZAG, 0, true)));

        // C3: H1 — initial k = quantValue (the falsified-last-session hypothesis,
        // re-checked here against ground truth, with quantValue typically=4).
        map.put("C3_med_zigzag_kQuant_adaptive",
                bytes -> safeDecode(bytes, planeWidth, planeHeight, bitDepth,
                        new Config(Predictor.MED, SignConv.ZIGZAG, Math.max(0, quantValue),
                                true)));

        // C4: alternative sign convention — treat residual as direct two's-complement
        // signed value of width (k+1), i.e. no zigzag.
        map.put("C4_med_signedmag_k0_adaptive",
                bytes -> safeDecode(bytes, planeWidth, planeHeight, bitDepth,
                        new Config(Predictor.MED, SignConv.SIGNED_MAGNITUDE, 0, true)));

        // C5: H2 — 4-way gradient predictor in place of MED.
        map.put("C5_grad4_zigzag_k0_adaptive",
                bytes -> safeDecode(bytes, planeWidth, planeHeight, bitDepth,
                        new Config(Predictor.GRADIENT4, SignConv.ZIGZAG, 0, true)));

        // C6: simple LEFT predictor (degenerate but useful sanity check — should
        // produce visible structure if entropy decode is even partially correct).
        map.put("C6_left_zigzag_k0_adaptive",
                bytes -> safeDecode(bytes, planeWidth, planeHeight, bitDepth,
                        new Config(Predictor.LEFT, SignConv.ZIGZAG, 0, true)));

        // C7: paeth-style predictor (PNG paeth: choose neighbour with smallest gradient).
        map.put("C7_paeth_zigzag_k0_adaptive",
                bytes -> safeDecode(bytes, planeWidth, planeHeight, bitDepth,
                        new Config(Predictor.PAETH, SignConv.ZIGZAG, 0, true, false, false)));

        // ---- Sign-flip variants. The first sweep showed all "natural" candidates
        // produce strongly *anti*-correlated output (Pearson r ≈ −0.3 to −0.4).
        // That is consistent with either (a) the residual being subtracted rather
        // than added or (b) the final pixels being read out inverted.
        // C8 / C9 isolate which.

        // C8: MED predictor, subtract residual instead of add.
        map.put("C8_med_zigzag_k0_subtract",
                bytes -> safeDecode(bytes, planeWidth, planeHeight, bitDepth,
                        new Config(Predictor.MED, SignConv.ZIGZAG, 0, true, true, false)));

        // C9: MED predictor, add residual then invert the final pixel value
        // (pixel = maxVal - pixel). Discriminates "wrong sign at update" from
        // "wrong polarity of decoded planes".
        map.put("C9_med_zigzag_k0_invertOutput",
                bytes -> safeDecode(bytes, planeWidth, planeHeight, bitDepth,
                        new Config(Predictor.MED, SignConv.ZIGZAG, 0, true, false, true)));

        // C10: Paeth + subtract (combines C7's strongest |r| with the C8 variation).
        map.put("C10_paeth_zigzag_k0_subtract",
                bytes -> safeDecode(bytes, planeWidth, planeHeight, bitDepth,
                        new Config(Predictor.PAETH, SignConv.ZIGZAG, 0, true, true, false)));

        // C11: LEFT + subtract — C6 had the lowest mean abs error; if subtract
        // flips its sign positive, that's the strongest single piece of evidence.
        map.put("C11_left_zigzag_k0_subtract",
                bytes -> safeDecode(bytes, planeWidth, planeHeight, bitDepth,
                        new Config(Predictor.LEFT, SignConv.ZIGZAG, 0, true, true, false)));

        // ---- Empirical-k variants. The CrxBitsPerPixelIT diagnostic showed that
        // for real Canon CR3 files the bitstream is ≈ 9.0–9.4 bits/pixel and a
        // pure fixed-k=7 Rice decoder consumes ≈97-100% of the bytes — i.e.
        // initial_k for adaptive Rice is approximately 7, NOT 0 as the older
        // candidates assumed. C12-C15 check fixed-k decoders without adaptation
        // around that empirical value to discriminate "k init wrong" (the new
        // hypothesis) from "predictor wrong" (the old hypothesis space).
        for (int kInit : new int[]{5, 6, 7, 8}) {
            final int kFinal = kInit;
            map.put(String.format("C_med_zigzag_k%d_fixed", kFinal),
                    bytes -> safeDecode(bytes, planeWidth, planeHeight, bitDepth,
                            new Config(Predictor.MED, SignConv.ZIGZAG, kFinal, false)));
            map.put(String.format("C_paeth_zigzag_k%d_fixed", kFinal),
                    bytes -> safeDecode(bytes, planeWidth, planeHeight, bitDepth,
                            new Config(Predictor.PAETH, SignConv.ZIGZAG, kFinal, false)));
            map.put(String.format("C_left_zigzag_k%d_fixed", kFinal),
                    bytes -> safeDecode(bytes, planeWidth, planeHeight, bitDepth,
                            new Config(Predictor.LEFT, SignConv.ZIGZAG, kFinal, false)));
        }
        // And: adaptive starting from k=7 (the empirical mean) — same predictor
        // choices, but the k-counter is allowed to track per-pixel variance from
        // a sane starting point instead of climbing from 0.
        map.put("C_med_zigzag_k7_adaptive",
                bytes -> safeDecode(bytes, planeWidth, planeHeight, bitDepth,
                        new Config(Predictor.MED, SignConv.ZIGZAG, 7, true)));
        map.put("C_paeth_zigzag_k7_adaptive",
                bytes -> safeDecode(bytes, planeWidth, planeHeight, bitDepth,
                        new Config(Predictor.PAETH, SignConv.ZIGZAG, 7, true)));

        return map;
    }

    private enum Predictor { MED, LEFT, GRADIENT4, PAETH }
    private enum SignConv { ZIGZAG, SIGNED_MAGNITUDE }

    private record Config(Predictor predictor, SignConv signConv, int initialK, boolean adaptive,
                          boolean subtract, boolean invertOutput) {
        Config(Predictor predictor, SignConv signConv, int initialK, boolean adaptive) {
            this(predictor, signConv, initialK, adaptive, false, false);
        }
    }

    private static int[] safeDecode(byte[] bytes, int w, int h, int bitDepth, Config cfg) {
        try {
            return decode(bytes, w, h, bitDepth, cfg);
        } catch (RuntimeException | IOException e) {
            // Fall through to whatever we have so far — the harness will see truncated output.
            return new int[0];
        }
    }

    private static int[] decode(byte[] bytes, int w, int h, int bitDepth, Config cfg)
            throws IOException {
        int[] result = new int[w * h];
        int maxVal = (1 << bitDepth) - 1;
        BitReader reader = new BitReader(bytes, 0, bytes.length);

        int k = cfg.initialK();
        long acc = 0;
        long n = 1;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!reader.hasMore()) {
                    return java.util.Arrays.copyOf(result, y * w + x);
                }
                int residual = decodeRice(reader, k);
                int signed = switch (cfg.signConv()) {
                    case ZIGZAG -> ((residual & 1) == 0) ? (residual >> 1) : (-(residual >> 1) - 1);
                    case SIGNED_MAGNITUDE -> {
                        int half = residual >> 1;
                        yield ((residual & 1) == 0) ? half : -half;
                    }
                };

                int predicted = predict(result, w, x, y, bitDepth, cfg.predictor());
                int pixel = cfg.subtract() ? predicted - signed : predicted + signed;
                if (pixel < 0) {
                    pixel = 0;
                } else if (pixel > maxVal) {
                    pixel = maxVal;
                }
                if (cfg.invertOutput()) {
                    pixel = maxVal - pixel;
                }
                result[y * w + x] = pixel;

                if (cfg.adaptive()) {
                    int abs = Math.abs(signed);
                    acc += abs;
                    n++;
                    long thr = n << k;
                    if (acc > thr && k < bitDepth) {
                        k++;
                    } else if (k > 0) {
                        long lower = n << (k - 1);
                        if (acc <= lower) {
                            k--;
                        }
                    }
                    if (n >= 1 << 20) {
                        acc >>= 1;
                        n >>= 1;
                        if (n == 0) {
                            n = 1;
                        }
                    }
                }
            }
        }
        return result;
    }

    private static int predict(int[] result, int w, int x, int y, int bitDepth, Predictor p) {
        if (x == 0 && y == 0) {
            return 1 << (bitDepth - 1);
        }
        if (y == 0) {
            return result[x - 1];
        }
        if (x == 0) {
            return result[(y - 1) * w];
        }
        int a = result[y * w + x - 1];
        int b = result[(y - 1) * w + x];
        int c = result[(y - 1) * w + x - 1];
        return switch (p) {
            case LEFT -> a;
            case MED -> {
                if (c >= Math.max(a, b)) {
                    yield Math.min(a, b);
                } else if (c <= Math.min(a, b)) {
                    yield Math.max(a, b);
                } else {
                    yield a + b - c;
                }
            }
            case GRADIENT4 -> {
                // 4-way region classifier: pick the neighbour whose gradient
                // most closely matches the local trend.
                int dh = Math.abs(b - c); // horizontal gradient (above row)
                int dv = Math.abs(a - c); // vertical gradient (left col)
                if (dh > 2 * dv) {
                    yield a;
                } else if (dv > 2 * dh) {
                    yield b;
                } else {
                    yield (a + b) >> 1;
                }
            }
            case PAETH -> {
                int p0 = a + b - c;
                int da = Math.abs(p0 - a);
                int db = Math.abs(p0 - b);
                int dc = Math.abs(p0 - c);
                if (da <= db && da <= dc) {
                    yield a;
                } else if (db <= dc) {
                    yield b;
                } else {
                    yield c;
                }
            }
        };
    }

    private static int decodeRice(BitReader reader, int k) throws IOException {
        int q = 0;
        while (reader.hasMore()) {
            if (reader.readBit() != 0) {
                break;
            }
            q++;
            if (q > MAX_UNARY_PROBE) {
                // Cap to prevent runaway on garbage; harness will flag accuracy elsewhere.
                break;
            }
        }
        int r = 0;
        for (int i = 0; i < k && reader.hasMore(); i++) {
            r = (r << 1) | reader.readBit();
        }
        return (q << k) | r;
    }
}
