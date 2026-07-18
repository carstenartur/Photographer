package com.photographer.focusstack.cr3.raw.codec;

import com.photographer.focusstack.cr3.Cr3Exception;
import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.raw.Cr3RawImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;

/** Clean-room decoder for Canon CRX lossless RAW data. */
public final class CrxLosslessRawDecoder {

    private static final Logger LOG = LoggerFactory.getLogger(CrxLosslessRawDecoder.class);
    private static final int MAX_RICE_UNARY_COUNT = 65536;
    private static final int NORMALIZATION_THRESHOLD = 1024;
    private static final int PATENT_LOSSLESS_K_MAX = 12;

    /** Selects the adaptive Rice-Golomb profile used by the per-plane decoder. */
    public enum KUpdateRule {
        JPEG_LS,
        PATENT_BRACKET
    }

    public static final KUpdateRule DEFAULT_K_UPDATE_RULE = KUpdateRule.PATENT_BRACKET;

    private CrxLosslessRawDecoder() {}

    public static Cr3RawImage decode(byte[] data, int width, int height, int bitDepth)
            throws Cr3Exception {
        return decode(data, width, height, bitDepth, CrxHeader.CodecVersion.V1, null);
    }

    public static Cr3RawImage decode(byte[] data, int width, int height,
                                     int bitDepth, CrxHeader.CodecVersion version)
            throws Cr3Exception {
        return decode(data, width, height, bitDepth, version, null);
    }

    public static Cr3RawImage decode(byte[] data, int width, int height,
                                     int bitDepth, CrxHeader.CodecVersion version, Cmp1Box cmp1)
            throws Cr3Exception {
        return decode(data, width, height, bitDepth, version, cmp1, DEFAULT_K_UPDATE_RULE);
    }

    public static Cr3RawImage decode(byte[] data, int width, int height,
                                     int bitDepth, CrxHeader.CodecVersion version, Cmp1Box cmp1,
                                     KUpdateRule kUpdateRule)
            throws Cr3Exception {
        if (kUpdateRule == null) {
            throw new IllegalArgumentException("kUpdateRule must not be null");
        }
        if (data == null || data.length == 0) {
            throw new Cr3Exception("CRX data is empty");
        }
        if (width <= 0 || height <= 0) {
            throw new Cr3Exception("Invalid RAW dimensions: " + width + "x" + height);
        }
        if (bitDepth <= 0 || bitDepth > 16) {
            throw new Cr3Exception("Unsupported bit depth: " + bitDepth);
        }

        LOG.info("CRX lossless decode: {}x{}, {}-bit, {} bytes compressed, codec {}",
                width, height, bitDepth, data.length, version);

        try {
            ParsedCrx parsedCrx = parseCrxData(data, version, cmp1);
            byte[] crxData = parsedCrx.data();
            CrxHeader header = parsedCrx.header();
            LOG.debug("Parsed CRX header: {} tiles, header size={}",
                    header.tiles().size(), header.headerSize());

            if (header.tiles().isEmpty()) {
                throw new Cr3Exception("No tiles found in CRX header");
            }
            if (cmp1 != null && !cmp1.isLossless()) {
                throw new Cr3Exception("Unsupported CRX mode: waveletLevels="
                        + cmp1.waveletLevels() + " (only lossless is supported)");
            }

            int planeWidth = (width + 1) / 2;
            int planeHeight = (height + 1) / 2;
            int requiredPlanes = cmp1 != null && cmp1.numPlanes() > 0 ? cmp1.numPlanes() : 4;
            if (requiredPlanes < 4) {
                throw new Cr3Exception("Unsupported number of planes: " + requiredPlanes
                        + " (expected at least 4 for Bayer)");
            }

            int numTiles = header.tiles().size();
            int nominalTilePlaneWidth = resolveTilePlaneWidth(cmp1, numTiles, planeWidth, width);
            int tilePlaneHeight = resolveTilePlaneHeight(cmp1, planeHeight);

            int[][] planes = new int[4][];
            for (int i = 0; i < 4; i++) {
                planes[i] = new int[planeWidth * planeHeight];
            }

            CrxHeader.Tile[] sortedTiles = header.tiles().toArray(new CrxHeader.Tile[0]);
            Arrays.sort(sortedTiles, Comparator.comparingInt(CrxHeader.Tile::index));
            int tileXOffset = 0;

            for (int tilePosition = 0; tilePosition < sortedTiles.length; tilePosition++) {
                CrxHeader.Tile tile = sortedTiles[tilePosition];
                if (tile.planes().size() < 4) {
                    throw new Cr3Exception("Expected 4 planes, got " + tile.planes().size()
                            + " in tile " + tile.index());
                }
                if (tile.index() < 0 || tile.index() >= numTiles) {
                    throw new Cr3Exception("Tile index " + tile.index()
                            + " must be between 0 and " + (numTiles - 1) + " (inclusive)");
                }

                int remainingPlaneWidth = planeWidth - tileXOffset;
                int tilePixelWidth = tilePosition == sortedTiles.length - 1
                        ? remainingPlaneWidth
                        : Math.min(nominalTilePlaneWidth, remainingPlaneWidth);
                if (tilePixelWidth <= 0 || tileXOffset + tilePixelWidth > planeWidth) {
                    throw new Cr3Exception("Tile " + tile.index() + " exceeds plane width: "
                            + (tileXOffset + tilePixelWidth) + " > " + planeWidth);
                }

                final int finalTileXOffset = tileXOffset;
                final int finalTilePixelWidth = tilePixelWidth;
                final int finalTilePlaneHeight = tilePlaneHeight;
                final int finalBitDepth = bitDepth;
                final byte[] finalCrxData = crxData;
                final int[][] finalPlanes = planes;
                final int finalPlaneWidth = planeWidth;
                final CrxHeader.Tile finalTile = tile;
                final KUpdateRule finalKRule = kUpdateRule;

                try {
                    finalTile.planes().parallelStream().forEach(plane -> {
                        try {
                            decodeTilePlane(plane, finalTile, finalCrxData, finalPlanes,
                                    finalTileXOffset, finalTilePixelWidth, finalTilePlaneHeight,
                                    finalPlaneWidth, finalBitDepth, finalKRule);
                        } catch (Cr3Exception | IOException e) {
                            throw new DecodePlaneException(e);
                        }
                    });
                } catch (DecodePlaneException wrapped) {
                    Throwable cause = wrapped.getCause();
                    if (cause instanceof Cr3Exception cr3Exception) {
                        throw cr3Exception;
                    }
                    if (cause instanceof IOException ioException) {
                        throw ioException;
                    }
                    throw wrapped;
                }

                tileXOffset += tilePixelWidth;
            }

            if (tileXOffset != planeWidth) {
                throw new Cr3Exception("CRX tile widths do not cover full image plane: "
                        + tileXOffset + " != " + planeWidth);
            }

            int[] bayerData = new int[width * height];
            interleavePlanesByPosition(planes, bayerData, width, height, planeWidth, planeHeight);
            Cr3RawImage.BayerPattern bayerPattern = resolveBayerPattern(cmp1);
            LOG.info("CRX lossless decode complete: {} pixels, pattern={}",
                    bayerData.length, bayerPattern);
            return new Cr3RawImage(width, height, bayerData, bitDepth, bayerPattern);
        } catch (IOException e) {
            throw new Cr3Exception("CRX lossless decoding failed: " + e.getMessage(), e);
        }
    }

    private static Cr3RawImage.BayerPattern resolveBayerPattern(Cmp1Box cmp1) {
        if (cmp1 == null) {
            return Cr3RawImage.BayerPattern.RGGB;
        }
        return switch (cmp1.cfaLayout()) {
            case 0 -> Cr3RawImage.BayerPattern.RGGB;
            case 1 -> Cr3RawImage.BayerPattern.GRBG;
            case 2 -> Cr3RawImage.BayerPattern.GBRG;
            case 3 -> Cr3RawImage.BayerPattern.BGGR;
            default -> {
                LOG.warn("Unknown CFA layout {} in CMP1, defaulting to RGGB", cmp1.cfaLayout());
                yield Cr3RawImage.BayerPattern.RGGB;
            }
        };
    }

    private static ParsedCrx parseCrxData(byte[] data, CrxHeader.CodecVersion version,
                                          Cmp1Box cmp1) throws IOException {
        try {
            CrxHeader header = CrxHeader.parse(data, version);
            if (!header.tiles().isEmpty()) {
                return new ParsedCrx(data, header);
            }
            int headerSize = cmp1 != null ? cmp1.mdatHeaderSize() : 0;
            if (headerSize > 0 && headerSize < data.length) {
                byte[] stripped = Arrays.copyOfRange(data, headerSize, data.length);
                LOG.debug("CRX header had no tiles at offset 0, retrying with CMP1 "
                        + "mdatHeaderSize={} bytes stripped", headerSize);
                return new ParsedCrx(stripped, CrxHeader.parse(stripped, version));
            }
            return new ParsedCrx(data, header);
        } catch (IOException firstError) {
            int headerSize = cmp1 != null ? cmp1.mdatHeaderSize() : 0;
            if (headerSize > 0 && headerSize < data.length) {
                byte[] stripped = Arrays.copyOfRange(data, headerSize, data.length);
                LOG.debug("CRX header parse failed at offset 0, retrying with CMP1 "
                        + "mdatHeaderSize={} bytes stripped", headerSize);
                return new ParsedCrx(stripped, CrxHeader.parse(stripped, version));
            }
            throw firstError;
        }
    }

    private static int resolveTilePlaneWidth(Cmp1Box cmp1, int numTiles,
                                             int planeWidth, int rawWidth) {
        if (cmp1 != null && cmp1.tileWidth() > 0) {
            int cmp1PlaneTileWidth = Math.max(1, (cmp1.tileWidth() + 1) / 2);
            return Math.min(cmp1PlaneTileWidth, planeWidth);
        }
        return numTiles > 1 ? Math.max(1, (rawWidth / numTiles + 1) / 2) : planeWidth;
    }

    private static int resolveTilePlaneHeight(Cmp1Box cmp1, int planeHeight) {
        if (cmp1 != null && cmp1.tileHeight() > 0) {
            int cmp1PlaneTileHeight = Math.max(1, (cmp1.tileHeight() + 1) / 2);
            return Math.min(cmp1PlaneTileHeight, planeHeight);
        }
        return planeHeight;
    }

    private record ParsedCrx(byte[] data, CrxHeader header) {}

    private static void decodeTilePlane(CrxHeader.Plane plane, CrxHeader.Tile tile,
                                        byte[] crxData, int[][] planes,
                                        int tileXOffset, int tilePixelWidth,
                                        int tilePlaneHeight, int planeWidth,
                                        int bitDepth, KUpdateRule kUpdateRule)
            throws Cr3Exception, IOException {
        if (plane.subbands().isEmpty()) {
            throw new Cr3Exception("No subbands in plane " + plane.index()
                    + " of tile " + tile.index());
        }

        int planeIndex = plane.index();
        if (planeIndex < 0 || planeIndex >= 4) {
            LOG.warn("Unexpected plane index {}, skipping", planeIndex);
            return;
        }

        CrxHeader.Subband subband = plane.subbands().getFirst();
        long rawOffset = subband.dataOffset();
        long rawSize = subband.dataSize();
        long rawEnd = rawOffset + rawSize;
        if (rawOffset > Integer.MAX_VALUE || rawSize > Integer.MAX_VALUE
                || rawEnd > Integer.MAX_VALUE || rawEnd < rawOffset) {
            throw new Cr3Exception("Subband data exceeds 2 GB limit: offset="
                    + rawOffset + ", size=" + rawSize);
        }
        int subDataOffset = (int) rawOffset;
        int subDataEnd = (int) rawEnd;

        if (subDataOffset >= crxData.length) {
            throw new Cr3Exception("Subband offset past end of CRX data: "
                    + subDataOffset + " >= " + crxData.length);
        }
        if (subDataEnd > crxData.length) {
            LOG.debug("Subband {} in plane {} tile {} extends {} bytes past CRX payload; "
                            + "clamping to available data", subband.index(), planeIndex,
                    tile.index(), subDataEnd - crxData.length);
            subDataEnd = crxData.length;
        }
        if (subDataEnd <= subDataOffset) {
            throw new Cr3Exception("Invalid subband range: [" + subDataOffset + ", "
                    + subDataEnd + ")");
        }

        int[] decoded = decodePlane(crxData, subDataOffset, subDataEnd,
                tilePixelWidth, tilePlaneHeight, bitDepth, kUpdateRule);
        for (int row = 0; row < tilePlaneHeight; row++) {
            System.arraycopy(decoded, row * tilePixelWidth,
                    planes[planeIndex], row * planeWidth + tileXOffset,
                    tilePixelWidth);
        }
    }

    private static final class DecodePlaneException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        DecodePlaneException(Throwable cause) {
            super(cause);
        }
    }

    private static int[] decodePlane(byte[] data, int offset, int endOffset,
                                     int width, int height, int bitDepth,
                                     KUpdateRule kUpdateRule) throws IOException {
        int[] result = new int[width * height];
        int maxValue = (1 << bitDepth) - 1;
        BitReader reader = new BitReader(data, offset, endOffset - offset);

        int k = kUpdateRule == KUpdateRule.PATENT_BRACKET ? 1 : 0;
        long accumulator = 0;
        long sampleCount = 1;
        boolean streamExhausted = false;

        for (int y = 0; y < height; y++) {
            if (kUpdateRule == KUpdateRule.PATENT_BRACKET) {
                k = 1;
                accumulator = 0;
                sampleCount = 1;
            }
            for (int x = 0; x < width; x++) {
                int predicted;
                if (x == 0 && y == 0) {
                    predicted = 0;
                } else if (y == 0) {
                    predicted = result[x - 1];
                } else if (x == 0) {
                    predicted = result[(y - 1) * width];
                } else {
                    int left = result[y * width + x - 1];
                    int above = result[(y - 1) * width + x];
                    int aboveLeft = result[(y - 1) * width + x - 1];
                    predicted = medPredict(left, above, aboveLeft);
                }

                int residual;
                if (streamExhausted || !reader.hasMore()) {
                    if (!streamExhausted) {
                        LOG.debug("CRX plane bitstream has no more data at ({},{}), "
                                + "padding remaining pixels with predicted values", x, y);
                        streamExhausted = true;
                    }
                    residual = 0;
                } else {
                    try {
                        residual = kUpdateRule == KUpdateRule.PATENT_BRACKET
                                ? decodeOnesUntilZeroRiceGolomb(reader, k)
                                : decodeAdaptiveRiceGolomb(reader, k);
                    } catch (IOException e) {
                        if (e.getMessage() != null
                                && (e.getMessage().contains("Unexpected end of stream")
                                || e.getMessage().contains("end of data"))) {
                            LOG.debug("CRX plane bitstream ended early at ({},{}), "
                                    + "padding remaining pixels with predicted values", x, y);
                            streamExhausted = true;
                            residual = 0;
                        } else {
                            throw e;
                        }
                    }
                }

                int signedResidual;
                if (kUpdateRule == KUpdateRule.PATENT_BRACKET) {
                    int magnitude = residual >>> 1;
                    signedResidual = (residual & 1) == 0 ? magnitude : -magnitude;
                } else if ((residual & 1) == 0) {
                    signedResidual = residual >> 1;
                } else {
                    signedResidual = -(residual >> 1) - 1;
                }

                int pixel = Math.max(0, Math.min(maxValue, predicted + signedResidual));
                result[y * width + x] = pixel;

                int absoluteResidual = Math.abs(signedResidual);
                switch (kUpdateRule) {
                    case JPEG_LS -> {
                        accumulator += absoluteResidual;
                        sampleCount++;
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
                        if (sampleCount >= NORMALIZATION_THRESHOLD) {
                            accumulator >>= 1;
                            sampleCount >>= 1;
                            if (sampleCount == 0) sampleCount = 1;
                        }
                    }
                    case PATENT_BRACKET -> {
                        accumulator += absoluteResidual;
                        sampleCount++;
                        if (absoluteResidual > (1 << (k + 1))
                                && k < PATENT_LOSSLESS_K_MAX) {
                            k++;
                        } else if (k > 0
                                && accumulator <= (sampleCount << (k - 1))) {
                            k--;
                        }
                        if (sampleCount >= NORMALIZATION_THRESHOLD) {
                            accumulator >>= 1;
                            sampleCount >>= 1;
                            if (sampleCount == 0) sampleCount = 1;
                        }
                    }
                }
            }
        }
        return result;
    }

    static int medPredict(int a, int b, int c) {
        if (c >= Math.max(a, b)) {
            return Math.min(a, b);
        } else if (c <= Math.min(a, b)) {
            return Math.max(a, b);
        }
        return a + b - c;
    }

    private static int decodeAdaptiveRiceGolomb(BitReader reader, int k) throws IOException {
        int quotient = 0;
        while (true) {
            if (!reader.hasMore()) {
                throw new IOException(
                        "Unexpected end of stream while reading Rice-Golomb unary code");
            }
            if (reader.readBit() != 0) break;
            quotient++;
            if (quotient > MAX_RICE_UNARY_COUNT) {
                throw new IOException("Rice-Golomb unary overflow (>"
                        + MAX_RICE_UNARY_COUNT + ")");
            }
        }
        int remainder = 0;
        if (k > 0) {
            if (!reader.hasMore()) {
                throw new IOException(
                        "Unexpected end of stream while reading Rice-Golomb remainder");
            }
            remainder = reader.readBits(k);
        }
        return (quotient << k) | remainder;
    }

    private static int decodeOnesUntilZeroRiceGolomb(BitReader reader, int k)
            throws IOException {
        int quotient = 0;
        while (true) {
            if (!reader.hasMore()) {
                throw new IOException("Unexpected end of stream while reading CRX unary code");
            }
            if (reader.readBit() == 0) break;
            quotient++;
            if (quotient > MAX_RICE_UNARY_COUNT) {
                throw new IOException("CRX unary overflow (>" + MAX_RICE_UNARY_COUNT + ")");
            }
        }
        int remainder = 0;
        if (k > 0) {
            if (!reader.hasMore()) {
                throw new IOException(
                        "Unexpected end of stream while reading CRX Rice-Golomb remainder");
            }
            remainder = reader.readBits(k);
        }
        return (quotient << k) | remainder;
    }

    private static void interleavePlanesByPosition(int[][] planes, int[] output,
                                                   int bayerWidth, int bayerHeight,
                                                   int planeWidth, int planeHeight) {
        for (int planeY = 0; planeY < planeHeight; planeY++) {
            int bayerY0 = planeY * 2;
            int bayerY1 = bayerY0 + 1;
            for (int planeX = 0; planeX < planeWidth; planeX++) {
                int sourceIndex = planeY * planeWidth + planeX;
                int bayerX0 = planeX * 2;
                int bayerX1 = bayerX0 + 1;
                if (bayerY0 < bayerHeight && bayerX0 < bayerWidth) {
                    output[bayerY0 * bayerWidth + bayerX0] = planes[0][sourceIndex];
                }
                if (bayerY0 < bayerHeight && bayerX1 < bayerWidth) {
                    output[bayerY0 * bayerWidth + bayerX1] = planes[1][sourceIndex];
                }
                if (bayerY1 < bayerHeight && bayerX0 < bayerWidth) {
                    output[bayerY1 * bayerWidth + bayerX0] = planes[2][sourceIndex];
                }
                if (bayerY1 < bayerHeight && bayerX1 < bayerWidth) {
                    output[bayerY1 * bayerWidth + bayerX1] = planes[3][sourceIndex];
                }
            }
        }
    }
}
