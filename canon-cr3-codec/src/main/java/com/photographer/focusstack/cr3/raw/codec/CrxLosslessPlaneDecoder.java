package com.photographer.focusstack.cr3.raw.codec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;

/**
 * Decodes one CRX lossless plane by composing entropy, sign, predictor and
 * adaptation stages.
 */
final class CrxLosslessPlaneDecoder {

    private static final Logger LOG = LoggerFactory.getLogger(CrxLosslessPlaneDecoder.class);

    private CrxLosslessPlaneDecoder() {
    }

    static int[] decode(
            byte[] data,
            int offset,
            int endOffset,
            int width,
            int height,
            int bitDepth,
            CrxLosslessRawDecoder.KUpdateRule rule) throws IOException {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(rule, "rule");
        if (offset < 0 || endOffset < offset || endOffset > data.length) {
            throw new IllegalArgumentException("Invalid CRX plane range [" + offset + ", " + endOffset + ")");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Plane dimensions must be positive");
        }
        if (bitDepth <= 0 || bitDepth > 16) {
            throw new IllegalArgumentException("Unsupported bit depth: " + bitDepth);
        }

        int[] reconstructed = new int[Math.multiplyExact(width, height)];
        int maximumValue = (1 << bitDepth) - 1;
        BitReader reader = new BitReader(data, offset, endOffset - offset);
        CrxRiceAdaptationState adaptation = new CrxRiceAdaptationState(rule, bitDepth);
        CrxRiceSymbolDecoder.UnaryDirection unaryDirection = CrxRiceSymbolDecoder.forRule(rule);
        CrxResidualSignMapper.Mapping signMapping = CrxResidualSignMapper.forRule(rule);
        boolean streamExhausted = false;

        for (int y = 0; y < height; y++) {
            adaptation.startRow();
            for (int x = 0; x < width; x++) {
                int predicted = CrxMedPredictor.predict(reconstructed, width, x, y);
                int encodedResidual = 0;

                if (streamExhausted || !reader.hasMore()) {
                    if (!streamExhausted) {
                        LOG.debug("CRX plane bitstream has no more data at ({},{}), padding remaining "
                                + "pixels with predicted values", x, y);
                        streamExhausted = true;
                    }
                } else {
                    try {
                        encodedResidual = CrxRiceSymbolDecoder.decode(reader, adaptation.k(), unaryDirection);
                    } catch (IOException exception) {
                        if (isEndOfStream(exception)) {
                            LOG.debug("CRX plane bitstream ended early at ({},{}), padding remaining "
                                    + "pixels with predicted values", x, y);
                            streamExhausted = true;
                            encodedResidual = 0;
                        } else {
                            throw exception;
                        }
                    }
                }

                int signedResidual = CrxResidualSignMapper.decode(encodedResidual, signMapping);
                int pixel = Math.max(0, Math.min(maximumValue, predicted + signedResidual));
                reconstructed[y * width + x] = pixel;
                adaptation.update(signedResidual);
            }
        }
        return reconstructed;
    }

    private static boolean isEndOfStream(IOException exception) {
        String message = exception.getMessage();
        return message != null
                && (message.contains("Unexpected end of stream") || message.contains("end of data"));
    }
}
