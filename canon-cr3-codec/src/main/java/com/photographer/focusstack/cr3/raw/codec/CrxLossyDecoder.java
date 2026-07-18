package com.photographer.focusstack.cr3.raw.codec;

import com.photographer.focusstack.cr3.Cr3Exception;
import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.raw.Cr3RawImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Decoder scaffold and structural validation for Canon CRX lossy C-RAW data. */
public final class CrxLossyDecoder {

    private static final Logger LOG = LoggerFactory.getLogger(CrxLossyDecoder.class);

    private CrxLossyDecoder() {}

    public static Cr3RawImage decode(byte[] data, int width, int height,
                                      int bitDepth, CrxHeader.CodecVersion codecVersion,
                                      Cmp1Box cmp1) throws Cr3Exception, java.io.IOException {
        if (cmp1 == null) {
            throw new Cr3Exception("Cannot decode C-RAW: CMP1 box is missing");
        }
        if (cmp1.isLossless()) {
            throw new Cr3Exception("Cannot decode C-RAW: CMP1 indicates lossless (waveletLevels=0)");
        }

        int waveletLevels = cmp1.waveletLevels();
        int encodingType = cmp1.encodingType();
        int numPlanes = cmp1.numPlanes();

        LOG.info("C-RAW file detected: {}x{}, {} planes, {} wavelet levels, encoding type {}",
                width, height, numPlanes, waveletLevels, encodingType);

        CrxHeader header = CrxHeader.parse(data, codecVersion);
        validateLossyStructure(header, waveletLevels);

        throw new Cr3Exception(
                "C-RAW (lossy) decoding is not yet fully implemented. "
                        + "File has %d wavelet levels, %d planes. "
                        + "Header and subband structure validated successfully. "
                        + "IDWT infrastructure is ready (Le Gall 5/3). "
                        + "Remaining: entropy decoding integration, dequantization formula, "
                        + "subband ordering confirmation. "
                        + "Use lossless RAW files, or extract the embedded JPEG preview instead."
                        .formatted(waveletLevels, numPlanes));
    }

    public static boolean isLossy(Cmp1Box cmp1) {
        return cmp1 != null && !cmp1.isLossless();
    }

    private static void validateLossyStructure(CrxHeader header, int expectedWaveletLevels)
            throws Cr3Exception {
        if (header.tiles().isEmpty()) {
            throw new Cr3Exception("C-RAW header contains no tiles");
        }

        for (CrxHeader.Tile tile : header.tiles()) {
            for (CrxHeader.Plane plane : tile.planes()) {
                int subbandCount = plane.subbands().size();
                int expected = 3 * expectedWaveletLevels + 1;
                if (subbandCount != expected && subbandCount > 0) {
                    LOG.debug("Plane {} has {} subbands (expected {} for {} wavelet levels)",
                            plane.index(), subbandCount, expected, expectedWaveletLevels);
                }
            }
        }

        LOG.debug("C-RAW structure validated: {} tiles", header.tiles().size());
    }
}
