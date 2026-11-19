package com.photographer.focusstack.cr3.research;

/**
 * Test‑scope catalogue of CRX tiling and wavelet dimensions for specific
 * Canon DIGIC&nbsp;8 bodies.
 *
 * <p>This class has two layers:</p>
 * <ol>
 *   <li>{@code OBSERVED_*} constants — the values our cleanroom parser
 *       <i>actually</i> reads from the committed CR3 fixtures
 *       ({@code samples/} and {@code samples/research/}). These drive the
 *       JUnit assertions in
 *       {@code KnownCameraTilingFixtureCrossCheckIT}.</li>
 *   <li>{@code CLAIMED_*} constants — what an external 2026‑05‑14
 *       information drop <i>claimed</i> the values were. Kept for
 *       traceability and so the falsification is visible at code level.</li>
 * </ol>
 *
 * <h3>Falsification log (2026‑05‑17 fixture cross‑check)</h3>
 * <p>The drop was falsified on several counts; see
 * {@code target/known-tiling-hypothesis/report.txt} for the raw evidence:</p>
 * <ul>
 *   <li><b>G5X II matrix</b> drop: 5632×3648 — <b>observed: 5632×3710</b>.</li>
 *   <li><b>G5X II slicing</b> drop: 4 vertical slices × 1408 — <b>observed: 2 horizontal tiles × 2816</b> (CMP1.tileWidth=2816, CRX header reports 2 tiles).</li>
 *   <li><b>G5X II lossless plane count</b> drop: 2 planes/slice with greens line‑interleaved — <b>observed: 4 planes/tile (full RGGB), 1 subband/plane</b>.</li>
 *   <li><b>G5X II C‑RAW waveletLevels</b> drop: 2 — <b>observed: 3</b> (iq3 fixture {@code g5xm2-detail-…-iq3-…}).</li>
 *   <li><b>EOS R matrix</b> drop: 6880×4544 — <b>observed: 6888×4546</b>.</li>
 *   <li><b>EOS R slicing</b> drop: 5 vertical slices × 1376 — <b>observed: 2 horizontal tiles × 3444</b>.</li>
 *   <li><b>EOS R waveletLevels = 3</b> — <i>not testable</i>, the
 *       repository ships only lossless EOS R fixtures.</li>
 * </ul>
 *
 * <p>What survived from the drop is the high‑level <i>shape</i> of the
 * dequantisation formula
 * ({@code Ĉ = sign(V)·(|V|·q + ⌊q/2⌋)·2^roundedBits·α_band}) and the
 * Le&nbsp;Gall‑5/3 normalisation α‑constants — neither of which can be
 * verified from fixture geometry alone, so they are kept as a hypothesis
 * the {@code CrxNormativeLosslessCandidate*} bake‑off can A/B against.</p>
 *
 * <p>No production code is touched by this class.</p>
 */
final class KnownCameraTilingHypothesis {

    private KnownCameraTilingHypothesis() {}

    /**
     * Hypothesised CMP1‑level geometry for one Canon body.
     *
     * @param model              human‑readable model name
     * @param sensorWidth        full RAW matrix width (incl. OB pixels)
     * @param sensorHeight       full RAW matrix height (incl. OB pixels)
     * @param activeWidth        usable image area width
     * @param activeHeight       usable image area height
     * @param sliceCount         number of vertical slices the tile is split into
     * @param sliceWidth         per‑slice width in sensor pixels (uniform across slices for these bodies)
     * @param tileHeight         tile height in sensor pixels (uniform across slices)
     * @param cfa                CFA layout name (e.g. "RGGB")
     * @param waveletLevels      number of wavelet decomposition levels for C‑RAW
     * @param bayerPlaneWidth    per‑Bayer‑plane width inside one slice (slice / 2)
     * @param bayerPlaneHeight   per‑Bayer‑plane height inside one slice (tile / 2)
     */
    record CameraTiling(
            String model,
            int sensorWidth, int sensorHeight,
            int activeWidth, int activeHeight,
            int sliceCount, int sliceWidth, int tileHeight,
            String cfa,
            int waveletLevels,
            int bayerPlaneWidth, int bayerPlaneHeight
    ) {}

    /**
     * Hypothesised wavelet subband vector dimensions for one decomposition
     * level. {@code LL} and the three detail bands {@code LH/HL/HH} share
     * the same vector size at each level.
     */
    record SubbandLevel(int level, int width, int height) {}

    // ---------------------------------------------------------------------
    // Canon PowerShot G5 X Mark II — DIGIC 8
    // ---------------------------------------------------------------------

    /** Drop’s claim — falsified, kept for traceability. */
    static final CameraTiling G5X_MARK_II_CLAIMED = new CameraTiling(
            "Canon PowerShot G5 X Mark II (CLAIMED, falsified)",
            5632, 3648,
            5496, 3648,
            4, 1408, 3648,
            "RGGB",
            2,
            704, 1824
    );

    /**
     * Observed values from the committed G5X II fixtures
     * ({@code samples/research/g5xm2-*.cr3}). CRX header reports two
     * horizontal tiles of 2816 pixels each, so {@code sliceCount=2} and
     * {@code sliceWidth=2816}.
     */
    static final CameraTiling G5X_MARK_II = new CameraTiling(
            "Canon PowerShot G5 X Mark II",
            5632, 3710,
            5496, 3668,                   // inclusive IAD1 active = [120,28..5615,3695]
            2, 2816, 3710,
            "RGGB",
            3,                            // observed in C‑RAW iq3 fixtures
            1408, 1855                    // bayer plane = tile / 2
    );

    /**
     * Drop’s 2‑level claim — falsified; the C‑RAW iq3 fixtures decompose
     * to 3 levels.
     */
    static final SubbandLevel[] G5X_MARK_II_CLAIMED_SUBBANDS = {
            new SubbandLevel(1, 352, 912),
            new SubbandLevel(2, 176, 456)
    };

    /**
     * Observed subband ladder for the C‑RAW {@code iq3} fixtures: starts
     * from the bayer plane (1408×1855), halves three times. Rounding is
     * ceiling so the last level still contains the boundary samples.
     */
    static final SubbandLevel[] G5X_MARK_II_SUBBANDS = {
            new SubbandLevel(1, 704, 928),
            new SubbandLevel(2, 352, 464),
            new SubbandLevel(3, 176, 232)
    };

    // ---------------------------------------------------------------------
    // Canon EOS R — DIGIC 8
    // ---------------------------------------------------------------------

    /** Drop’s claim — falsified, kept for traceability. */
    static final CameraTiling EOS_R_CLAIMED = new CameraTiling(
            "Canon EOS R (CLAIMED, falsified)",
            6880, 4544,
            6744, 4502,
            5, 1376, 4544,
            "RGGB",
            3,
            688, 2272
    );

    /**
     * Observed values from {@code samples/eosr-iso-*.cr3} (all lossless,
     * RAW_LOSSLESS, waveletLevels=0). The 3‑level wavelet hypothesis is
     * not testable here and remains as a candidate to be checked when an
     * EOS R C‑RAW fixture is added.
     */
    static final CameraTiling EOS_R = new CameraTiling(
            "Canon EOS R",
            6888, 4546,
            6744, 4500,                   // inclusive IAD1 active = [144,46..6887,4545]
            2, 3444, 4546,
            "RGGB",
            3,                            // hypothesis, not yet verified
            1722, 2273
    );

    /**
     * Same caveat as above: the EOS R C‑RAW subband ladder is a hypothesis
     * we cannot fixture‑check today. Kept for the {@code
     * CrxNormativeLosslessCandidate*} harness.
     */
    static final SubbandLevel[] EOS_R_SUBBANDS = {
            new SubbandLevel(1, 861, 1137),
            new SubbandLevel(2, 431,  569),
            new SubbandLevel(3, 216,  285)
    };

    // ---------------------------------------------------------------------
    // G5X II lossless slice layout — falsified
    // ---------------------------------------------------------------------

    /**
     * Layout description for a lossless CRX bitstream.
     *
     * @param model                         human‑readable model name
     * @param sampleCount                   number of ISOBMFF RAW‑track
     *                                      samples (one per independent
     *                                      bitstream)
     * @param planesPerSlice                Bayer planes per CRX tile
     * @param greensInterleavedAsLinePairs  whether G1/G2 are folded into
     *                                      a single plane bitstream
     * @param zeroBitByteAligned            whether each bitstream pads
     *                                      with zero bits to the next byte
     *                                      boundary at its end
     */
    record LosslessSliceLayout(
            String model,
            int sampleCount,
            int planesPerSlice,
            boolean greensInterleavedAsLinePairs,
            boolean zeroBitByteAligned
    ) {}

    /** Drop’s claim, falsified by every G5X II lossless fixture. */
    static final LosslessSliceLayout G5X_MARK_II_LOSSLESS_LAYOUT_CLAIMED =
            new LosslessSliceLayout(
                    "Canon PowerShot G5 X Mark II (CLAIMED, falsified)",
                    /* sampleCount               */ 4,
                    /* planesPerSlice            */ 2,
                    /* greensInterleavedAsLinePairs */ true,
                    /* zeroBitByteAligned        */ true
            );

    /**
     * Observed layout. RAW track is a single ISOBMFF sample; CRX header
     * exposes two horizontal tiles; each tile carries four Bayer planes
     * (full RGGB), one subband per plane (lossless = no wavelet).
     */
    static final LosslessSliceLayout G5X_MARK_II_LOSSLESS_LAYOUT =
            new LosslessSliceLayout(
                    "Canon PowerShot G5 X Mark II",
                    /* sampleCount               */ 1,
                    /* planesPerSlice            */ 4,
                    /* greensInterleavedAsLinePairs */ false,
                    /* zeroBitByteAligned        */ true
            );

    // ---------------------------------------------------------------------
    // EOS R C‑RAW detail‑subband dequantisation hypothesis (2026‑05‑14)
    // ---------------------------------------------------------------------

    /**
     * Anisotropic scaling factor &alpha;<sub>band</sub> hypothesised for
     * the separable 2D Le&nbsp;Gall&nbsp;5/3 transform when reconstructed
     * in <i>normalised biorthogonal</i> form: the two cross detail bands
     * receive {@code √2}, the diagonal band receives {@code 2.0}, and the
     * approximation band is unscaled.
     *
     * <p><b>Caveat:</b> this is the right family of constants <i>iff</i>
     * the inverse transform is the normalised variant. For a strict
     * integer‑lifting 5/3 implementation these factors are already baked
     * into the lifting coefficients and applying them again would
     * double‑scale the detail bands. The bring‑up harness in
     * {@code CrxNormativeLosslessCandidate*} is the right place to A/B
     * this — never apply unconditionally in the production IDWT.</p>
     */
    enum SubbandKind {
        LL(1.0),
        LH(Math.sqrt(2.0)),
        HL(Math.sqrt(2.0)),
        HH(2.0);

        final double alpha;
        SubbandKind(double alpha) { this.alpha = alpha; }
    }

    /**
     * Pure, side‑effect‑free reference implementation of the hypothesised
     * EOS R C‑RAW detail‑subband dequantisation step:
     * <pre>
     *   Ĉ = sign(V) · ( |V|·q + ⌊q/2⌋ ) · 2^roundedBits · α<sub>band</sub>
     * </pre>
     *
     * @param signedCoeff  the signed quantised coefficient {@code V}
     *                     decoded by the Rice/Golomb stage
     * @param quantValue   {@code q} from {@code CrxHeader.Subband.quantValue()}
     * @param roundedBits  {@code roundedBits} from
     *                     {@code CrxHeader.Plane.roundedBits()}
     * @param band         which of LL/LH/HL/HH this coefficient belongs to
     * @return the dequantised coefficient as a {@code double}; the caller
     *         is responsible for any final rounding to the storage type
     */
    static double dequantise(int signedCoeff, int quantValue, int roundedBits,
                             SubbandKind band) {
        if (quantValue == 0) {
            // Lossless / unquantised band — no mid‑tread offset, no scaling.
            return (double) signedCoeff * (1L << roundedBits) * band.alpha;
        }
        int sign = Integer.signum(signedCoeff);
        long magnitude = Math.abs((long) signedCoeff);
        long midTread = magnitude * quantValue + (quantValue >> 1);
        return sign * midTread * (double) (1L << roundedBits) * band.alpha;
    }

    /**
     * Sanity check that the recorded subband dimensions are internally
     * consistent with the bayer plane size: each successive level halves
     * width and height (rounded up). This is a pure compile/test‑time
     * arithmetic check and does not touch the file format.
     */
    static boolean isInternallyConsistent(CameraTiling t, SubbandLevel[] levels) {
        int w = t.bayerPlaneWidth();
        int h = t.bayerPlaneHeight();
        for (SubbandLevel lv : levels) {
            w = (w + 1) >> 1;
            h = (h + 1) >> 1;
            if (lv.width() != w || lv.height() != h) {
                return false;
            }
        }
        return levels.length == t.waveletLevels();
    }
}
