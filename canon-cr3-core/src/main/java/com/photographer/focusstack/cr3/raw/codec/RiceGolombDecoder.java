package com.photographer.focusstack.cr3.raw.codec;

import java.io.IOException;

/**
 * Rice-Golomb entropy decoder for Canon CRX lossless RAW data.
 *
 * <p>Canon's CRX codec uses adaptive Rice-Golomb coding for the prediction
 * residuals. The Rice parameter is adapted based on recent symbol statistics.</p>
 *
 * TODO: Implement full adaptive Rice-Golomb decoding for CRX bitstream
 * TODO: Handle different CRX sub-band configurations
 */
public final class RiceGolombDecoder {

    private final BitReader reader;
    private int riceParam;

    /**
     * Creates a new Rice-Golomb decoder.
     *
     * @param reader    the bit reader
     * @param riceParam initial Rice parameter (k)
     */
    public RiceGolombDecoder(BitReader reader, int riceParam) {
        this.reader = reader;
        this.riceParam = riceParam;
    }

    /**
     * Decodes a single Rice-Golomb coded value.
     *
     * <p>The value is encoded as: unary(q) + remainder(k bits),
     * where q = value >> k and remainder = value & ((1 << k) - 1).</p>
     *
     * @return the decoded unsigned value
     * @throws IOException if the bit reader runs out of data
     */
    public int decode() throws IOException {
        int q = reader.readUnary();
        int r = (riceParam > 0) ? reader.readBits(riceParam) : 0;
        return (q << riceParam) | r;
    }

    /**
     * Decodes a signed value using Rice-Golomb with interleaved sign.
     *
     * <p>Positive values map to even codes, negative to odd codes:
     * 0 → 0, -1 → 1, 1 → 2, -2 → 3, 2 → 4, ...</p>
     *
     * @return the decoded signed value
     * @throws IOException if the bit reader runs out of data
     */
    public int decodeSigned() throws IOException {
        int unsigned = decode();
        // Interleaved sign mapping
        if ((unsigned & 1) == 0) {
            return unsigned >> 1;
        } else {
            return -(unsigned >> 1) - 1;
        }
    }

    /** Returns the current Rice parameter. */
    public int riceParam() { return riceParam; }

    /** Updates the Rice parameter. */
    public void setRiceParam(int k) { this.riceParam = k; }
}
