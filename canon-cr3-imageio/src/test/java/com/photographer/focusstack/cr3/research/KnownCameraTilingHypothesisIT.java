package com.photographer.focusstack.cr3.research;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the <i>observed</i> CRX tiling / wavelet dimensions for the Canon
 * G5&nbsp;X&nbsp;Mark&nbsp;II and the Canon EOS&nbsp;R that
 * {@link KnownCameraTilingFixtureCrossCheckIT} extracted from the
 * committed fixtures. These are not the values claimed by the 2026‑05‑14
 * information drop — the drop was falsified on several counts on
 * 2026‑05‑17; the {@code *_CLAIMED} constants in
 * {@link KnownCameraTilingHypothesis} record what it claimed for
 * traceability, the unsuffixed constants record what we actually see.
 *
 * <p>This test never opens a CR3 file; it only asserts arithmetic
 * self‑consistency of the recorded constants. The fixture cross‑check
 * lives in {@link KnownCameraTilingFixtureCrossCheckIT}.</p>
 */
final class KnownCameraTilingHypothesisIT {

    @Test
    void g5xMarkIiSliceWidthsCoverFullSensorWidth() {
        var t = KnownCameraTilingHypothesis.G5X_MARK_II;
        assertEquals(t.sensorWidth(), t.sliceCount() * t.sliceWidth(),
                "Sum of slice widths must cover the full sensor matrix width");
        assertEquals(t.sliceWidth() / 2, t.bayerPlaneWidth(),
                "Bayer plane width is half a slice for an RGGB CFA");
        assertEquals(t.tileHeight() / 2, t.bayerPlaneHeight(),
                "Bayer plane height is half a tile for an RGGB CFA");
    }

    @Test
    void eosRSliceWidthsCoverFullSensorWidth() {
        var t = KnownCameraTilingHypothesis.EOS_R;
        assertEquals(t.sensorWidth(), t.sliceCount() * t.sliceWidth(),
                "Sum of slice widths must cover the full sensor matrix width");
        assertEquals(t.sliceWidth() / 2, t.bayerPlaneWidth());
        assertEquals(t.tileHeight() / 2, t.bayerPlaneHeight());
    }

    @Test
    void waveletSubbandLaddersAreInternallyConsistent() {
        assertTrue(KnownCameraTilingHypothesis.isInternallyConsistent(
                        KnownCameraTilingHypothesis.G5X_MARK_II,
                        KnownCameraTilingHypothesis.G5X_MARK_II_SUBBANDS),
                "G5X II subband ladder must halve at each wavelet level");
        assertTrue(KnownCameraTilingHypothesis.isInternallyConsistent(
                        KnownCameraTilingHypothesis.EOS_R,
                        KnownCameraTilingHypothesis.EOS_R_SUBBANDS),
                "EOS R subband ladder must halve at each wavelet level");
    }

    // -----------------------------------------------------------------
    // G5X II lossless slice‑layout hypothesis
    // -----------------------------------------------------------------

    @Test
    void g5xMarkIiLosslessLayoutMatchesObservation() {
        var l = KnownCameraTilingHypothesis.G5X_MARK_II_LOSSLESS_LAYOUT;
        // Observed reality (see falsification log on KnownCameraTilingHypothesis):
        // a single ISOBMFF sample carries the whole RAW track; the CRX
        // header internally splits it into two horizontal tiles, each
        // tile holds four Bayer planes (full RGGB), one subband per plane.
        assertEquals(1, l.sampleCount(),
                "Lossless RAW track is a single ISOBMFF sample for these fixtures");
        assertEquals(4, l.planesPerSlice(),
                "G5X II lossless tiles carry four full Bayer planes (RGGB)");
        assertFalse(l.greensInterleavedAsLinePairs(),
                "Greens are not line‑interleaved in observed fixtures");
        assertTrue(l.zeroBitByteAligned(),
                "Slice end must be zero‑bit padded to a byte boundary");
    }

    @Test
    void g5xMarkIiClaimedLayoutDiffersFromObservation() {
        // Pins the drop’s claim as falsified so a future regression
        // cannot silently re‑introduce the wrong constants.
        var claimed = KnownCameraTilingHypothesis.G5X_MARK_II_LOSSLESS_LAYOUT_CLAIMED;
        var observed = KnownCameraTilingHypothesis.G5X_MARK_II_LOSSLESS_LAYOUT;
        assertNotEquals(claimed.sampleCount(), observed.sampleCount());
        assertNotEquals(claimed.planesPerSlice(), observed.planesPerSlice());
    }

    // -----------------------------------------------------------------
    // EOS R C‑RAW dequantisation reference
    // -----------------------------------------------------------------

    @Test
    void dequantiseIsZeroPreservingAndSignSymmetric() {
        for (var band : KnownCameraTilingHypothesis.SubbandKind.values()) {
            assertEquals(0.0,
                    KnownCameraTilingHypothesis.dequantise(0, 7, 2, band),
                    "Zero coefficient must dequantise to zero on band " + band);
            double pos = KnownCameraTilingHypothesis.dequantise(+5, 7, 2, band);
            double neg = KnownCameraTilingHypothesis.dequantise(-5, 7, 2, band);
            assertEquals(pos, -neg, 1e-12,
                    "Dequantisation must be sign‑symmetric on band " + band);
        }
    }

    @Test
    void dequantiseAppliesMidTreadAndRoundedBitsForLl() {
        // V=3, q=4, roundedBits=2, LL (alpha=1):
        //   midTread = 3*4 + 4/2 = 14
        //   shift   = 1 << 2    = 4
        //   alpha   = 1
        //   result  = 56
        double v = KnownCameraTilingHypothesis.dequantise(
                3, 4, 2, KnownCameraTilingHypothesis.SubbandKind.LL);
        assertEquals(56.0, v, 1e-12);
    }

    @Test
    void dequantiseAppliesAnisotropicScalingPerBand() {
        // Same V/q/roundedBits across all four bands, only alpha differs.
        int v = 1; int q = 1; int rb = 0; // midTread = 1, shift = 1
        double ll = KnownCameraTilingHypothesis.dequantise(v, q, rb, KnownCameraTilingHypothesis.SubbandKind.LL);
        double lh = KnownCameraTilingHypothesis.dequantise(v, q, rb, KnownCameraTilingHypothesis.SubbandKind.LH);
        double hl = KnownCameraTilingHypothesis.dequantise(v, q, rb, KnownCameraTilingHypothesis.SubbandKind.HL);
        double hh = KnownCameraTilingHypothesis.dequantise(v, q, rb, KnownCameraTilingHypothesis.SubbandKind.HH);
        assertEquals(1.0, ll, 1e-12);
        assertEquals(Math.sqrt(2.0), lh, 1e-12);
        assertEquals(Math.sqrt(2.0), hl, 1e-12);
        assertEquals(2.0, hh, 1e-12);
        // Diagonal scaling must equal the product of the two cross scalings —
        // this is the algebraic signature of a separable 2D normalisation.
        assertEquals(lh * hl, hh, 1e-12,
                "HH alpha must equal LH·HL for a separable normalised 2D wavelet");
    }
}
