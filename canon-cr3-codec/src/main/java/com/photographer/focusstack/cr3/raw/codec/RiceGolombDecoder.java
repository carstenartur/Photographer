package com.photographer.focusstack.cr3.raw.codec;

import java.io.IOException;

/**
 * Rice-Golomb entropy decoder for Canon CRX lossless RAW data.
 *
 * <p>Canon's CRX codec uses adaptive Rice-Golomb coding for the prediction
 * residuals. The Rice parameter is adapted based on recent symbol statistics.</p>
 *
 * <p>The Golomb-Rice codeword shape implemented here matches the patent
 * description (US 2016/0323602 A1, FIG. 10A/10B): for a non-negative symbol
 * {@code S} and parameter {@code K}, the codeword is {@code (S >> K)} zero bits
 * followed by a single {@code 1} bit, then the {@code K} low-order bits of
 * {@code S}. See {@code docs/crx-cleanroom-spec.md} §7 for the full
 * specification.</p>
 *
 * <p><b>Note on K adaptation:</b> the patent rule for updating {@code K} is a
 * per-symbol lookahead-bracket comparison (see spec §4), not the JPEG-LS-style
 * running accumulator currently used in {@link CrxLosslessRawDecoder}. This
 * decoder class deals only with the entropy step itself; the K-update logic
 * lives in the calling site and is the next planned change.</p>
 */
public final class RiceGolombDecoder {

    private final BitReader reader;
    private int riceParam;

    /**
     * Creates a new Rice-Golomb decoder.
     *
     * @param reader    the bit reader
     * @param riceParam initial Rice parameter ({@code k})
     */
    public RiceGolombDecoder(BitReader reader, int riceParam) {
        this.reader = reader;
        this.riceParam = riceParam;
    }

    /**
     * Decodes a single Rice-Golomb coded value.
     *
     * <p>The value is encoded as unary {@code q} followed by a remainder of
     * {@code k} bits, where {@code q = value >> k} and
     * {@code remainder = value & ((1 << k) - 1)}.</p>
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

    /**
     * Returns the current Rice parameter.
     *
     * @return the current Rice parameter
     */
    public int riceParam() {
        return riceParam;
    }

    /**
     * Updates the Rice parameter used by subsequent decode operations.
     *
     * @param k the new Rice parameter
     */
    public void setRiceParam(int k) {
        this.riceParam = k;
    }
}
