package com.photographer.focusstack.cr3.raw.codec;

import com.photographer.focusstack.cr3.Cr3Exception;
import com.photographer.focusstack.cr3.raw.Cr3RawImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Decoder for Canon CRX lossless RAW data.
 *
 * <p>CRX (Canon Raw eXtended) lossless compression uses adaptive Rice-Golomb
 * entropy coding with Median Edge Detection (MED) prediction, similar to
 * JPEG-LS (ITU T.87). This is a cleanroom implementation based on the
 * published JPEG-LS standard and public CR3 format documentation.</p>
 *
 * <p>The decoding pipeline:</p>
 * <ol>
 *   <li>Parse CRX tile/plane/subband headers</li>
 *   <li>For each plane: entropy-decode using adaptive Rice-Golomb</li>
 *   <li>Apply MED prediction inverse to reconstruct pixel values</li>
 *   <li>Interleave RGGB planes into a single Bayer-pattern image</li>
 * </ol>
 */
public final class CrxLosslessRawDecoder {

    private static final Logger LOG = LoggerFactory.getLogger(CrxLosslessRawDecoder.class);

    /** Maximum unary code length before we consider the stream corrupt. */
    private static final int MAX_RICE_UNARY_COUNT = 65536;
    /** Threshold for periodic normalization of adaptation counters. */
    private static final int NORMALIZATION_THRESHOLD = 1024;

    private CrxLosslessRawDecoder() {
        // utility class
    }

    /**
     * Decodes CRX lossless RAW data into a linear Bayer image.
     *
     * @param data     the raw CRX compressed data (from mdat)
     * @param width    expected image width (full sensor)
     * @param height   expected image height (full sensor)
     * @param bitDepth expected bit depth (typically 14)
     * @return the decoded RAW image
     * @throws Cr3Exception if decoding fails
     */
    public static Cr3RawImage decode(byte[] data, int width, int height, int bitDepth)
            throws Cr3Exception {
        return decode(data, width, height, bitDepth, CrxHeader.CodecVersion.V1);
    }

    /**
     * Decodes CRX lossless RAW data with the specified codec version.
     *
     * @param data     the raw CRX compressed data
     * @param width    expected image width
     * @param height   expected image height
     * @param bitDepth expected bit depth
     * @param version  the CRX codec version
     * @return the decoded RAW image
     * @throws Cr3Exception if decoding fails
     */
    public static Cr3RawImage decode(byte[] data, int width, int height,
                                      int bitDepth, CrxHeader.CodecVersion version)
            throws Cr3Exception {
        LOG.info("CRX lossless decode: {}x{}, {}-bit, {} bytes compressed, codec {}",
                width, height, bitDepth, data.length, version);

        try {
            // 1. Parse CRX header
            CrxHeader header = CrxHeader.parse(data, version);
            LOG.debug("Parsed CRX header: {} tiles, header size={}", header.tiles().size(),
                    header.headerSize());

            if (header.tiles().isEmpty()) {
                throw new Cr3Exception("No tiles found in CRX header");
            }

            // 2. Determine plane dimensions
            // Each Bayer plane is half the width and half the height
            int planeWidth = width / 2;
            int planeHeight = height / 2;

            // 3. Decode each tile's planes
            // For big images with 2 tiles: left half and right half
            int numTiles = header.tiles().size();
            int tilePixelWidth = numTiles > 1 ? planeWidth / 2 : planeWidth;

            // Allocate output planes (one per Bayer channel)
            int[][] planes = new int[4][];
            for (int i = 0; i < 4; i++) {
                planes[i] = new int[planeWidth * planeHeight];
            }

            for (CrxHeader.Tile tile : header.tiles()) {
                if (tile.planes().size() < 4) {
                    throw new Cr3Exception("Expected 4 planes, got " + tile.planes().size()
                            + " in tile " + tile.index());
                }

                // Validate tile index is within expected range
                if (tile.index() < 0 || tile.index() >= numTiles) {
                    throw new Cr3Exception("Tile index " + tile.index()
                            + " is out of range [0, " + numTiles + ")");
                }

                int tileXOffset = tile.index() * tilePixelWidth;
                if (tileXOffset + tilePixelWidth > planeWidth) {
                    throw new Cr3Exception("Tile " + tile.index() + " exceeds plane width: "
                            + (tileXOffset + tilePixelWidth) + " > " + planeWidth);
                }

                for (CrxHeader.Plane plane : tile.planes()) {
                    if (plane.subbands().isEmpty()) {
                        throw new Cr3Exception("No subbands in plane " + plane.index()
                                + " of tile " + tile.index());
                    }

                    int planeIdx = plane.index();
                    if (planeIdx < 0 || planeIdx >= 4) {
                        LOG.warn("Unexpected plane index {}, skipping", planeIdx);
                        continue;
                    }

                    // For lossless, there is exactly 1 subband per plane (LL only)
                    CrxHeader.Subband subband = plane.subbands().getFirst();
                    int subDataOffset = (int) subband.dataOffset();
                    int subDataEnd = subDataOffset + (int) subband.dataSize();

                    if (subDataEnd > data.length) {
                        throw new Cr3Exception("Subband data extends past end of CRX data: "
                                + subDataEnd + " > " + data.length);
                    }

                    // Decode this plane's subband
                    int[] decoded = decodePlane(data, subDataOffset, subDataEnd,
                            tilePixelWidth, planeHeight, bitDepth);

                    // Copy decoded tile data into the full plane array
                    for (int row = 0; row < planeHeight; row++) {
                        System.arraycopy(decoded, row * tilePixelWidth,
                                planes[planeIdx], row * planeWidth + tileXOffset,
                                tilePixelWidth);
                    }
                }
            }

            // 4. Interleave planes into Bayer pattern
            int[] bayerData = new int[width * height];
            interleaveBayerRGGB(planes, bayerData, width, height, planeWidth, planeHeight);

            LOG.info("CRX lossless decode complete: {} pixels", bayerData.length);
            return new Cr3RawImage(width, height, bayerData, bitDepth,
                    Cr3RawImage.BayerPattern.RGGB);

        } catch (IOException e) {
            throw new Cr3Exception("CRX lossless decoding failed: " + e.getMessage(), e);
        }
    }

    /**
     * Decodes a single plane using adaptive Rice-Golomb coding with MED prediction.
     *
     * <p>This implements the JPEG-LS-style prediction and entropy decoding that
     * CRX lossless uses. The algorithm is based on the published ITU T.87 standard.</p>
     */
    private static int[] decodePlane(byte[] data, int offset, int endOffset,
                                      int width, int height, int bitDepth)
            throws IOException {
        int[] result = new int[width * height];
        int maxVal = (1 << bitDepth) - 1;

        // Read directly from the original data with offset/length to avoid extra buffer copies
        BitReader reader = new BitReader(data, offset, endOffset - offset);

        // JPEG-LS-style adaptive parameters
        int k = 0;  // Rice parameter
        long accumulator = 0;
        long sampleCount = 1;
        int range = maxVal + 1;
        int near = 0; // lossless: near = 0

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (!reader.hasMore()) {
                    // Pad remaining pixels with zero
                    break;
                }

                // MED prediction (Median Edge Detection, from JPEG-LS / ITU T.87)
                int predicted;
                if (x == 0 && y == 0) {
                    predicted = 1 << (bitDepth - 1); // midpoint for first pixel
                } else if (y == 0) {
                    predicted = result[x - 1]; // first row: predict from left
                } else if (x == 0) {
                    predicted = result[(y - 1) * width]; // first column: predict from above
                } else {
                    int a = result[y * width + (x - 1)];       // left
                    int b = result[(y - 1) * width + x];       // above
                    int c = result[(y - 1) * width + (x - 1)]; // above-left
                    predicted = medPredict(a, b, c);
                }

                // Adaptive Rice-Golomb decode of the prediction residual
                int residual = decodeAdaptiveRiceGolomb(reader, k);

                // Zigzag decode: even→positive, odd→negative
                int signedResidual;
                if ((residual & 1) == 0) {
                    signedResidual = residual >> 1;
                } else {
                    signedResidual = -(residual >> 1) - 1;
                }

                // Reconstruct pixel
                int pixel = predicted + signedResidual;
                pixel = Math.max(0, Math.min(maxVal, pixel));
                result[y * width + x] = pixel;

                // Update Rice parameter adaptively (JPEG-LS style)
                int absResidual = Math.abs(signedResidual);
                accumulator += absResidual;
                sampleCount++;

                // Adapt k: k = floor(log2(accumulator / sampleCount))
                if (sampleCount > 0) {
                    long threshold = sampleCount << k;
                    if (accumulator > threshold && k < bitDepth) {
                        k++;
                    } else if (k > 0) {
                        long lowerThreshold = sampleCount << (k - 1);
                        if (accumulator <= lowerThreshold) {
                            k--;
                        }
                    }
                }

                // Periodic normalization (prevent overflow)
                if (sampleCount >= NORMALIZATION_THRESHOLD) {
                    accumulator >>= 1;
                    sampleCount >>= 1;
                    if (sampleCount == 0) sampleCount = 1;
                }
            }
        }

        return result;
    }

    /**
     * Median Edge Detection predictor (from JPEG-LS / ITU T.87).
     *
     * <p>Given three neighboring pixels a (left), b (above), c (above-left):</p>
     * <ul>
     *   <li>If c ≥ max(a, b): predict min(a, b)</li>
     *   <li>If c ≤ min(a, b): predict max(a, b)</li>
     *   <li>Otherwise: predict a + b − c</li>
     * </ul>
     */
    static int medPredict(int a, int b, int c) {
        if (c >= Math.max(a, b)) {
            return Math.min(a, b);
        } else if (c <= Math.min(a, b)) {
            return Math.max(a, b);
        } else {
            return a + b - c;
        }
    }

    /**
     * Decodes a single unsigned value using Rice-Golomb coding.
     */
    private static int decodeAdaptiveRiceGolomb(BitReader reader, int k) throws IOException {
        // Read unary part: count zero bits before a one bit.
        // EOF is not a valid unary terminator; truncated streams must fail.
        int q = 0;
        while (true) {
            if (!reader.hasMore()) {
                throw new IOException("Unexpected end of stream while reading Rice-Golomb unary code");
            }
            if (reader.readBit() != 0) {
                break;
            }
            q++;
            if (q > MAX_RICE_UNARY_COUNT) {
                throw new IOException("Rice-Golomb unary overflow (>" + MAX_RICE_UNARY_COUNT + ")");
            }
        }
        // Read k-bit remainder; truncated remainder bits must also fail.
        int r = 0;
        if (k > 0) {
            if (!reader.hasMore()) {
                throw new IOException("Unexpected end of stream while reading Rice-Golomb remainder");
            }
            r = reader.readBits(k);
        }
        return (q << k) | r;
    }

    /**
     * Interleaves 4 decoded Bayer planes into a single RGGB Bayer array.
     *
     * <p>Plane 0 = Red (even rows, even columns)<br>
     * Plane 1 = Green in red row (even rows, odd columns)<br>
     * Plane 2 = Green in blue row (odd rows, even columns)<br>
     * Plane 3 = Blue (odd rows, odd columns)</p>
     */
    private static void interleaveBayerRGGB(int[][] planes, int[] output,
                                             int bayerWidth, int bayerHeight,
                                             int planeWidth, int planeHeight) {
        for (int py = 0; py < planeHeight; py++) {
            int bayerY0 = py * 2;
            int bayerY1 = bayerY0 + 1;
            for (int px = 0; px < planeWidth; px++) {
                int planeIdx = py * planeWidth + px;
                int bayerX0 = px * 2;
                int bayerX1 = bayerX0 + 1;

                // R (even row, even col)
                if (bayerY0 < bayerHeight && bayerX0 < bayerWidth) {
                    output[bayerY0 * bayerWidth + bayerX0] = planes[0][planeIdx];
                }
                // Gr (even row, odd col)
                if (bayerY0 < bayerHeight && bayerX1 < bayerWidth) {
                    output[bayerY0 * bayerWidth + bayerX1] = planes[1][planeIdx];
                }
                // Gb (odd row, even col)
                if (bayerY1 < bayerHeight && bayerX0 < bayerWidth) {
                    output[bayerY1 * bayerWidth + bayerX0] = planes[2][planeIdx];
                }
                // B (odd row, odd col)
                if (bayerY1 < bayerHeight && bayerX1 < bayerWidth) {
                    output[bayerY1 * bayerWidth + bayerX1] = planes[3][planeIdx];
                }
            }
        }
    }
}
