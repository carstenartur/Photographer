package com.photographer.focusstack.cr3.raw.codec;

import com.photographer.focusstack.cr3.Cr3Exception;
import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.raw.Cr3RawImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Decoder for Canon CRX lossless RAW data.
 *
 * <p>CRX lossless compression uses adaptive Rice-Golomb entropy coding over
 * positional Bayer planes. The native decoder uses the CMP1-declared start of each plane. Legacy
 * profiles remain selectable for explicit experiments and historical synthetic inputs.</p>
 *
 * <p>The implementation composes explicit package-private stages:</p>
 * <ol>
 *   <li>{@link CrxHeader} parses tile, plane and subband descriptors.</li>
 *   <li>{@link CrxRiceSymbolDecoder} decodes unsigned entropy symbols.</li>
 *   <li>{@link CrxResidualSignMapper} maps symbols to signed residuals.</li>
 *   <li>{@link CrxMedPredictor} predicts and reconstructs plane samples.</li>
 *   <li>{@link CrxRiceAdaptationState} updates the Rice parameter.</li>
 *   <li>{@link CrxPlaneAssembler} copies tiles and interleaves positional planes.</li>
 * </ol>
 *
 * <p>This is a clean-room implementation; no third-party CRX decoder source
 * was consulted.</p>
 */
public final class CrxLosslessRawDecoder {

    private static final Logger LOG = LoggerFactory.getLogger(CrxLosslessRawDecoder.class);

    /** Selects the Rice-Golomb entropy/adaptation profile for a plane. */
    public enum KUpdateRule {
        /** Legacy JPEG-LS-like zeros-until-one unary and zig-zag residual mapping. */
        JPEG_LS,
        /**
         * Row-local CRX research profile: K seed 1, ones-until-zero unary,
         * bit-0 sign/magnitude mapping and bounded per-symbol adaptation.
         */
        PATENT_BRACKET
    }

    /** Production default retained from the dark/flat reference research panel. */
    public static final KUpdateRule DEFAULT_K_UPDATE_RULE = KUpdateRule.PATENT_BRACKET;

    private CrxLosslessRawDecoder() {
    }

    /**
     * Decodes CRX lossless RAW data using codec version V1 and no CMP1 metadata.
     *
     * @param data compressed CRX track bytes
     * @param width output image width in pixels
     * @param height output image height in pixels
     * @param bitDepth output sample bit depth
     * @return the decoded Bayer image
     * @throws Cr3Exception if the bitstream or dimensions are unsupported or malformed
     */
    public static Cr3RawImage decode(byte[] data, int width, int height, int bitDepth)
            throws Cr3Exception {
        return decode(data, width, height, bitDepth, CrxHeader.CodecVersion.V1, null);
    }

    /**
     * Decodes CRX lossless RAW data using the supplied codec version.
     *
     * @param data compressed CRX track bytes
     * @param width output image width in pixels
     * @param height output image height in pixels
     * @param bitDepth output sample bit depth
     * @param version CRX marker/version family
     * @return the decoded Bayer image
     * @throws Cr3Exception if the bitstream or dimensions are unsupported or malformed
     */
    public static Cr3RawImage decode(
            byte[] data,
            int width,
            int height,
            int bitDepth,
            CrxHeader.CodecVersion version) throws Cr3Exception {
        return decode(data, width, height, bitDepth, version, null);
    }

    /**
     * Decodes CRX lossless RAW data using codec version and optional CMP1 metadata.
     *
     * @param data compressed CRX track bytes
     * @param width output image width in pixels
     * @param height output image height in pixels
     * @param bitDepth output sample bit depth
     * @param version CRX marker/version family
     * @param cmp1 optional CRX compression metadata used for tile, plane and CFA layout
     * @return the decoded Bayer image
     * @throws Cr3Exception if the bitstream or metadata are unsupported or malformed
     */
    public static Cr3RawImage decode(
            byte[] data,
            int width,
            int height,
            int bitDepth,
            CrxHeader.CodecVersion version,
            Cmp1Box cmp1) throws Cr3Exception {
        if (cmp1 != null && cmp1.mdatHeaderSize() > 0 && data != null && data.length >= 2
                && version != null && ((data[0] & 255) << 8 | (data[1] & 255)) == version.tileMarker) {
            return decodeNative(data, width, height, bitDepth, version, cmp1);
        }
        return decode(data, width, height, bitDepth, version, cmp1, DEFAULT_K_UPDATE_RULE);
    }

    /** Decodes the sensor track of a camera file with validated CMP1 framing. */
    public static Cr3RawImage decodeNative(byte[] data, int width, int height, int bitDepth,
                                           CrxHeader.CodecVersion version, Cmp1Box cmp1) throws Cr3Exception {
        return CrxNativeLosslessImage.decode(data, width, height, bitDepth, version, cmp1, resolveBayerPattern(cmp1));
    }

    /**
     * Decodes CRX lossless RAW data with an explicit entropy profile.
     *
     * <p>The explicit-rule overload is experimental and exists so reference
     * research can compare the legacy and current residual semantics without
     * changing the normal production call sites.</p>
     *
     * @param data compressed CRX track bytes
     * @param width output image width in pixels
     * @param height output image height in pixels
     * @param bitDepth output sample bit depth
     * @param version CRX marker/version family
     * @param cmp1 optional CRX compression metadata used for tile, plane and CFA layout
     * @param kUpdateRule entropy, residual-sign and Rice-adaptation profile
     * @return the decoded Bayer image
     * @throws Cr3Exception if the bitstream, metadata or selected profile cannot be decoded
     */
    public static Cr3RawImage decode(
            byte[] data,
            int width,
            int height,
            int bitDepth,
            CrxHeader.CodecVersion version,
            Cmp1Box cmp1,
            KUpdateRule kUpdateRule) throws Cr3Exception {
        if (kUpdateRule == null) {
            throw new IllegalArgumentException("kUpdateRule must not be null");
        }
        if (version == null) {
            throw new IllegalArgumentException("version must not be null");
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

            int numberOfTiles = header.tiles().size();
            int nominalTilePlaneWidth = resolveTilePlaneWidth(cmp1, numberOfTiles, planeWidth, width);
            int tilePlaneHeight = resolveTilePlaneHeight(cmp1, planeHeight);
            int[][] planes = new int[4][planeWidth * planeHeight];

            CrxHeader.Tile[] sortedTiles = header.tiles().toArray(new CrxHeader.Tile[0]);
            Arrays.sort(sortedTiles, Comparator.comparingInt(CrxHeader.Tile::index));
            int tileXOffset = 0;

            for (int tilePosition = 0; tilePosition < sortedTiles.length; tilePosition++) {
                CrxHeader.Tile tile = sortedTiles[tilePosition];
                if (tile.planes().size() < 4) {
                    throw new Cr3Exception("Expected 4 planes, got " + tile.planes().size()
                            + " in tile " + tile.index());
                }
                if (tile.index() < 0 || tile.index() >= numberOfTiles) {
                    throw new Cr3Exception("Tile index " + tile.index()
                            + " must be between 0 and " + (numberOfTiles - 1) + " (inclusive)");
                }

                int remainingPlaneWidth = planeWidth - tileXOffset;
                int tilePixelWidth = tilePosition == sortedTiles.length - 1
                        ? remainingPlaneWidth
                        : Math.min(nominalTilePlaneWidth, remainingPlaneWidth);
                if (tilePixelWidth <= 0 || tileXOffset + tilePixelWidth > planeWidth) {
                    throw new Cr3Exception("Tile " + tile.index() + " exceeds plane width: "
                            + (tileXOffset + tilePixelWidth) + " > " + planeWidth);
                }

                final int destinationX = tileXOffset;
                final int decodedTileWidth = tilePixelWidth;
                try {
                    tile.planes().parallelStream().forEach(plane -> {
                        try {
                            decodeTilePlane(
                                    plane,
                                    tile,
                                    crxData,
                                    planes,
                                    destinationX,
                                    decodedTileWidth,
                                    tilePlaneHeight,
                                    planeWidth,
                                    bitDepth,
                                    kUpdateRule);
                        } catch (Cr3Exception | IOException exception) {
                            throw new DecodePlaneException(exception);
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

            int[] bayerData = new int[Math.multiplyExact(width, height)];
            CrxPlaneAssembler.interleaveByPosition(
                    planes, bayerData, width, height, planeWidth, planeHeight);
            Cr3RawImage.BayerPattern bayerPattern = resolveBayerPattern(cmp1);
            LOG.info("CRX lossless decode complete: {} pixels, pattern={}",
                    bayerData.length, bayerPattern);
            return new Cr3RawImage(width, height, bayerData, bitDepth, bayerPattern);
        } catch (IOException exception) {
            throw new Cr3Exception("CRX lossless decoding failed: " + exception.getMessage(), exception);
        }
    }

    /** Package-visible compatibility seam used by existing tests. */
    static int medPredict(int left, int above, int aboveLeft) {
        return CrxMedPredictor.med(left, above, aboveLeft);
    }

    static Cr3RawImage.BayerPattern resolveBayerPattern(Cmp1Box cmp1) {
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

    private static ParsedCrx parseCrxData(
            byte[] data,
            CrxHeader.CodecVersion version,
            Cmp1Box cmp1) throws IOException {
        try {
            CrxHeader header = CrxHeader.parse(data, version);
            if (!header.tiles().isEmpty()) {
                return new ParsedCrx(data, header);
            }
            int headerSize = cmp1 != null ? cmp1.mdatHeaderSize() : 0;
            if (headerSize > 0 && headerSize < data.length) {
                byte[] stripped = Arrays.copyOfRange(data, headerSize, data.length);
                LOG.debug("CRX header had no tiles at offset 0, retrying with CMP1 mdatHeaderSize={} bytes stripped",
                        headerSize);
                return new ParsedCrx(stripped, CrxHeader.parse(stripped, version));
            }
            return new ParsedCrx(data, header);
        } catch (IOException firstError) {
            int headerSize = cmp1 != null ? cmp1.mdatHeaderSize() : 0;
            if (headerSize > 0 && headerSize < data.length) {
                byte[] stripped = Arrays.copyOfRange(data, headerSize, data.length);
                LOG.debug("CRX header parse failed at offset 0, retrying with CMP1 mdatHeaderSize={} bytes stripped",
                        headerSize);
                return new ParsedCrx(stripped, CrxHeader.parse(stripped, version));
            }
            throw firstError;
        }
    }

    private static int resolveTilePlaneWidth(
            Cmp1Box cmp1,
            int numberOfTiles,
            int planeWidth,
            int rawWidth) {
        if (cmp1 != null && cmp1.tileWidth() > 0) {
            int cmp1PlaneTileWidth = Math.max(1, (cmp1.tileWidth() + 1) / 2);
            return Math.min(cmp1PlaneTileWidth, planeWidth);
        }
        return numberOfTiles > 1 ? Math.max(1, (rawWidth / numberOfTiles + 1) / 2) : planeWidth;
    }

    private static int resolveTilePlaneHeight(Cmp1Box cmp1, int planeHeight) {
        if (cmp1 != null && cmp1.tileHeight() > 0) {
            int cmp1PlaneTileHeight = Math.max(1, (cmp1.tileHeight() + 1) / 2);
            return Math.min(cmp1PlaneTileHeight, planeHeight);
        }
        return planeHeight;
    }

    private static void decodeTilePlane(
            CrxHeader.Plane plane,
            CrxHeader.Tile tile,
            byte[] crxData,
            int[][] planes,
            int tileXOffset,
            int tilePixelWidth,
            int tilePlaneHeight,
            int planeWidth,
            int bitDepth,
            KUpdateRule kUpdateRule) throws Cr3Exception, IOException {
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
        if (rawOffset > Integer.MAX_VALUE
                || rawSize > Integer.MAX_VALUE
                || rawEnd > Integer.MAX_VALUE
                || rawEnd < rawOffset) {
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
            LOG.debug("Subband {} in plane {} tile {} extends {} bytes past CRX payload; clamping to available data",
                    subband.index(), planeIndex, tile.index(), subDataEnd - crxData.length);
            subDataEnd = crxData.length;
        }
        if (subDataEnd <= subDataOffset) {
            throw new Cr3Exception("Invalid subband range: [" + subDataOffset + ", " + subDataEnd + ")");
        }

        int[] decodedTile = CrxLosslessPlaneDecoder.decode(
                crxData,
                subDataOffset,
                subDataEnd,
                tilePixelWidth,
                tilePlaneHeight,
                bitDepth,
                kUpdateRule);
        CrxPlaneAssembler.copyTilePlane(
                decodedTile,
                planes[planeIndex],
                tilePixelWidth,
                tilePlaneHeight,
                planeWidth,
                tileXOffset);
    }

    private record ParsedCrx(byte[] data, CrxHeader header) {
    }

    private static final class DecodePlaneException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        DecodePlaneException(Throwable cause) {
            super(cause);
        }
    }
}
