package com.photographer.focusstack.cr3.raw.codec;

import com.photographer.focusstack.cr3.Cr3Exception;
import com.photographer.focusstack.cr3.Cr3Workers;
import com.photographer.focusstack.cr3.bmff.Cmp1Box;

import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;

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
        return prepare(track == null ? null : CrxTrackInput.fromArray(track), width, height, depth, version, cmp);
    }

    /** Reads independent subbands on demand from an open, seekable stream owned by the caller. */
    static CrxSensorDecoder prepare(ImageInputStream input, long offset, int length, int width, int height,
                                    int depth, CrxHeader.CodecVersion version, Cmp1Box cmp)
            throws Cr3Exception, IOException {
        return prepare(CrxTrackInput.fromStream(input, offset, length), width, height, depth, version, cmp);
    }

    /** Reads the track with positional I/O, leaving the caller's channel open and its cursor untouched. */
    static CrxSensorDecoder prepare(FileChannel channel, long offset, int length, int width, int height,
                                    int depth, CrxHeader.CodecVersion version, Cmp1Box cmp)
            throws Cr3Exception, IOException {
        return prepare(CrxTrackInput.fromChannel(channel, offset, length), width, height, depth, version, cmp);
    }

    private static CrxSensorDecoder prepare(CrxTrackInput track, int width, int height, int depth,
                                            CrxHeader.CodecVersion version, Cmp1Box cmp)
            throws Cr3Exception, IOException {
        if (CrxLossyDecoder.isLossy(cmp)) {
            return CrxLossyDecoder.prepare(track, width, height, depth, version, cmp);
        }
        return CrxNativeLosslessImage.prepare(track, width, height, depth, version, cmp);
    }

    /** Decodes synchronously in tile and plane directory order, with consecutive rows in each plane. */
    void decodeRows(RowConsumer consumer) throws Cr3Exception, IOException;

    /**
     * Decodes with an upper bound on workers (zero selects automatically, one selects serial decoding).
     * The consumer must support concurrent callbacks for different Bayer positions. Rows of the same
     * Bayer position are sequential across tiles. All callbacks finish before this method returns.
     * Implementations supporting only serial decoding may delegate to the one-argument method.
     */
    default void decodeRows(RowConsumer consumer, int maxWorkers) throws Cr3Exception, IOException {
        Cr3Workers.count(maxWorkers, 1);
        decodeRows(consumer);
    }
}
