package com.photographer.focusstack.cr3.raw.codec;

import com.photographer.focusstack.cr3.Cr3Exception;
import com.photographer.focusstack.cr3.bmff.Cmp1Box;

import java.io.IOException;

/** A validated native CRX track that can reconstruct Bayer samples one tile-plane row at a time. */
@FunctionalInterface
public interface CrxSensorDecoder {
    /**
     * Receives unsigned sensor samples at (x, y), (x + 2, y), and so on.
     * The row is read-only and valid only during this call.
     */
    @FunctionalInterface
    interface RowConsumer {
        void write(int x, int y, int[] samples);
    }

    /** Validates the profile, dimensions and directory before the caller allocates its output. */
    static CrxSensorDecoder prepare(byte[] track, int width, int height, int depth,
                                    CrxHeader.CodecVersion version, Cmp1Box cmp) throws Cr3Exception, IOException {
        if (CrxLossyDecoder.isLossy(cmp)) {
            return CrxLossyDecoder.prepare(track, width, height, depth, version, cmp);
        }
        return CrxNativeLosslessImage.prepare(track, width, height, depth, version, cmp);
    }

    /** Decodes every sample. Rows of one Bayer plane and tile are delivered consecutively. */
    void decodeRows(RowConsumer consumer) throws Cr3Exception, IOException;
}
