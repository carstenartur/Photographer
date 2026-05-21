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
 * <p>CRX (Canon Raw eXtended) lossless compression uses adaptive Rice-Golomb
 * entropy coding over Bayer planes. The production default is the
 * dark-frame-validated {@link KUpdateRule#PATENT_BRACKET} entropy profile;
 * the legacy JPEG-LS approximation remains selectable for research A/B runs.</p>
 *
 * <p>This is a cleanroom implementation; no third-party CRX decoder source
 * was consulted.</p>
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

    /** Clamp used by the patent-aligned lossless K adaptation. */
    private static final int PATENT_LOSSLESS_K_MAX = 12;

    /**
     * Selects the Rice‑Golomb {@code K} adaptation rule used by the
     * per‑plane entropy decoder.
     *
     * <p>Two rules are implemented:</p>
     *
     * <ul>
     *   <li>{@link #JPEG_LS} — the legacy ITU‑T T.87 (JPEG‑LS) accumulator
     *       approximation. It is retained for A/B research and documents the
     *       old entropy-lock-loss signature on dark/flat fixtures.</li>
     *   <li>{@link #PATENT_BRACKET} — the production CRX lossless entropy
     *       profile promoted from the dark-frame research panel: row-local
     *       state, K seed 1, ones-until-zero unary, bit-0 sign mapping, and
     *       bounded per-symbol K adaptation.</li>
     * </ul>
     */
    public enum KUpdateRule {
        /** ITU‑T T.87 accumulator rule (legacy CRX approximation). */
        JPEG_LS,
        /**
         * Patent-aligned CRX lossless entropy profile validated by the dark-frame
         * research fixtures: K starts at 1, state resets at every row, unary is
         * read as ones-until-zero, residuals use bit-0 sign mapping, and K is
         * adapted per symbol with the bounded lossless rule.
         */
        PATENT_BRACKET
    }

    /** Production default — promoted after the dark/flat research panel showed JPEG_LS lock-loss. */
    public static final KUpdateRule DEFAULT_K_UPDATE_RULE = KUpdateRule.PATENT_BRACKET;

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
        return decode(data, width, height, bitDepth, CrxHeader.CodecVersion.V1, null);
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
        return decode(data, width, height, bitDepth, version, null);
    }

    /**
     * Decodes CRX lossless RAW data with codec version and CMP1 metadata.
     *
     * @param data     the raw CRX compressed data
     * @param width    expected image width
     * @param height   expected image height
     * @param bitDepth expected bit depth
     * @param version  the CRX codec version
     * @param cmp1     optional CMP1 metadata from the track
     * @return the decoded RAW image
     * @throws Cr3Exception if decoding fails
     */
    public static Cr3RawImage decode(byte[] data, int width, int height,
                                     int bitDepth, CrxHeader.CodecVersion version, Cmp1Box cmp1)
            throws Cr3Exception {
        return decode(data, width, height, bitDepth, version, cmp1, DEFAULT_K_UPDATE_RULE);
    }

    /**
     * Decodes CRX lossless RAW data, allowing the caller to select the
     * Rice‑Golomb {@code K} adaptation rule.
     *
     * <p>This overload exists primarily so the research ITs can continue to
     * measure {@link KUpdateRule#JPEG_LS} and {@link KUpdateRule#PATENT_BRACKET}
     * side‑by‑side on dark / flat panels while normal production call sites use
     * {@link #DEFAULT_K_UPDATE_RULE}.</p>
     *
     * @param kUpdateRule the K adaptation rule to use; must not be {@code null}
     */
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
            LOG.debug("Parsed CRX header: {} tiles, header size={}", header.tiles().size(),
                    header.headerSize());

            if (header.tiles().isEmpty()) {
                throw new Cr3Exception("No tiles found in CRX header");
            }

            if (cmp1 != null && !cmp1.isLossless()) {
                throw new Cr3Exception("Unsupported CRX mode: waveletLevels="
                        + cmp1.waveletLevels() + " (only lossless is supported)");
            }

            // 2. Determine plane dimensions
            // Each Bayer plane is half the width and half the height
            int planeWidth = (width + 1) / 2;
            int planeHeight = (height + 1) / 2;
            int requiredPlanes = cmp1 != null && cmp1.numPlanes() > 0 ? cmp1.numPlanes() : 4;
            if (requiredPlanes < 4) {
                throw new Cr3Exception("Unsupported number of planes: " + requiredPlanes
                        + " (expected at least 4 for Bayer)");
            }

            // 3. Decode each tile's planes
            int numTiles = header.tiles().size();
            int nominalTilePlaneWidth = resolveTilePlaneWidth(cmp1, numTiles, planeWidth, width);
            int tilePlaneHeight = resolveTilePlaneHeight(cmp1, planeHeight);

            // Allocate output planes (one per Bayer channel)
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

                // Validate tile index is within expected range
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

                // Decode the (typically 4) Bayer planes of this tile in parallel.
                // Each plane is independent: decodePlane() is pure on a slice of
                // crxData, and the per-plane writes target a distinct planes[planeIdx]
                // slot (planeIdx ∈ {0,1,2,3}) so there is no data race.
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
                    if (cause instanceof Cr3Exception ce) {
                        throw ce;
                    }
                    if (cause instanceof IOException ioe) {
                        throw ioe;
                    }
                    throw wrapped;
                }

                tileXOffset += tilePixelWidth;
            }

            if (tileXOffset != planeWidth) {
                throw new Cr3Exception("CRX tile widths do not cover full image plane: "
                        + tileXOffset + " != " + planeWidth);
            }

            // 4. Interleave planes positionally into a 2×2-subsampled Bayer grid.
            // The resolved BayerPattern determines the actual colour assignment downstream.
            int[] bayerData = new int[width * height];
            interleavePlanesByPosition(planes, bayerData, width, height, planeWidth, planeHeight);

            // Resolve the actual CFA pattern from CMP1 (cfaLayout field).
            // The CRX encoder stores planes in positional order (top-left, top-right,
            // bottom-left, bottom-right) regardless of CFA pattern; we only need to
            // set the BayerPattern so that downstream processors (WB, demosaic) apply
            // the correct colour assignment at each pixel position.
            Cr3RawImage.BayerPattern bayerPattern = resolveBayerPattern(cmp1);
            LOG.info("CRX lossless decode complete: {} pixels, pattern={}", bayerData.length, bayerPattern);
            return new Cr3RawImage(width, height, bayerData, bitDepth, bayerPattern);

        } catch (IOException e) {
            throw new Cr3Exception("CRX lossless decoding failed: " + e.getMessage(), e);
        }
    }

    /**
     * Maps the CMP1 cfaLayout field (0–3) to the corresponding {@link Cr3RawImage.BayerPattern}.
     *
     * <p>The CRX encoder always stores Bayer planes in positional order
     * (plane 0 = top-left, plane 1 = top-right, plane 2 = bottom-left, plane 3 = bottom-right).
     * The CFA layout only determines which colour occupies each position.</p>
     */
    private static Cr3RawImage.BayerPattern resolveBayerPattern(Cmp1Box cmp1) {
        if (cmp1 == null) {
            return Cr3RawImage.BayerPattern.RGGB; // Safe default for Canon
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

    private static ParsedCrx parseCrxData(byte[] data, CrxHeader.CodecVersion version, Cmp1Box cmp1)
            throws IOException {
        try {
            CrxHeader header = CrxHeader.parse(data, version);
            if (!header.tiles().isEmpty()) {
                return new ParsedCrx(data, header);
            }
            int headerSize = cmp1 != null ? cmp1.mdatHeaderSize() : 0;
            if (headerSize > 0 && headerSize < data.length) {
                byte[] stripped = Arrays.copyOfRange(data, headerSize, data.length);
                LOG.debug("CRX header had no tiles at offset 0, retrying with CMP1 mdatHeaderSize={} "
                        + "bytes stripped", headerSize);
                return new ParsedCrx(stripped, CrxHeader.parse(stripped, version));
            }
            return new ParsedCrx(data, header);
        } catch (IOException firstError) {
            int headerSize = cmp1 != null ? cmp1.mdatHeaderSize() : 0;
            if (headerSize > 0 && headerSize < data.length) {
                byte[] stripped = Arrays.copyOfRange(data, headerSize, data.length);
                LOG.debug("CRX header parse failed at offset 0, retrying with CMP1 mdatHeaderSize={} "
                        + "bytes stripped", headerSize);
                return new ParsedCrx(stripped, CrxHeader.parse(stripped, version));
            }
            throw firstError;
        }
    }

    private static int resolveTilePlaneWidth(Cmp1Box cmp1, int numTiles, int planeWidth, int rawWidth) {
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

    /**
     * Validates a single plane's subband range and decodes it into the shared
     * {@code planes} grid. Extracted from {@link #decode} so it can be invoked
     * from a {@code parallelStream} over the planes of a tile — each plane
     * writes to a distinct {@code planes[planeIdx]} slot, so the four
     * concurrent invocations share no mutable state.
     */
    private static void decodeTilePlane(CrxHeader.Plane plane, CrxHeader.Tile tile,
                                        byte[] crxData, int[][] planes,
                                        int tileXOffset, int tilePixelWidth, int tilePlaneHeight,
                                        int planeWidth, int bitDepth, KUpdateRule kUpdateRule)
            throws Cr3Exception, IOException {
        if (plane.subbands().isEmpty()) {
            throw new Cr3Exception("No subbands in plane " + plane.index()
                    + " of tile " + tile.index());
        }

        int planeIdx = plane.index();
        if (planeIdx < 0 || planeIdx >= 4) {
            LOG.warn("Unexpected plane index {}, skipping", planeIdx);
            return;
        }

        // For lossless, there is exactly 1 subband per plane (LL only)
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
                            + "clamping to available data", subband.index(), planeIdx,
                    tile.index(), (subDataEnd - crxData.length));
            subDataEnd = crxData.length;
        }
        if (subDataEnd <= subDataOffset) {
            throw new Cr3Exception("Invalid subband range: [" + subDataOffset + ", "
                    + subDataEnd + ")");
        }

        // Decode this plane's subband
        int[] decoded = decodePlane(crxData, subDataOffset, subDataEnd,
                tilePixelWidth, tilePlaneHeight, bitDepth, kUpdateRule);

        // Copy decoded tile data into the full plane array.
        // Different planes write to distinct planes[planeIdx] slots, so this
        // is race-free across the parallel forEach over tile.planes().
        for (int row = 0; row < tilePlaneHeight; row++) {
            System.arraycopy(decoded, row * tilePixelWidth,
                    planes[planeIdx], row * planeWidth + tileXOffset,
                    tilePixelWidth);
        }
    }

    /**
     * Unchecked wrapper used to smuggle {@link Cr3Exception} / {@link IOException}
     * out of the per-plane parallel stream in {@link #decode}. The caller
     * unwraps and re-throws the original checked exception.
     */
    private static final class DecodePlaneException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        DecodePlaneException(Throwable cause) {
            super(cause);
        }
    }

    /**
     * Decodes a single plane using MED prediction and the selected CRX lossless
     * entropy profile.
     */
    private static int[] decodePlane(byte[] data, int offset, int endOffset,
                                      int width, int height, int bitDepth,
                                      KUpdateRule kUpdateRule)
            throws IOException {
        int[] result = new int[width * height];
        int maxVal = (1 << bitDepth) - 1;

        // Read directly from the original data with offset/length to avoid extra buffer copies
        BitReader reader = new BitReader(data, offset, endOffset - offset);

        // CRX lossless entropy parameters. JPEG_LS is retained as the legacy
        // approximation for A/B tests; PATENT_BRACKET uses the dark-frame-
        // validated canonical profile from CrxNormativeLosslessCandidateIT.
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
                // MED prediction (Median Edge Detection, from JPEG-LS / ITU T.87).
                //
                // The seed for the very first pixel was historically the
                // mid‑range value (1 << (bitDepth − 1) = 8192 at 14‑bit),
                // which is what JPEG‑LS prescribes for natural images.
                // Per the cleanroom research (samples/research/README.md
                // §"Implication for next-iteration decoder work" and the
                // dark/flat per‑plane diagnostics) Canon CRX seeds the
                // first pixel with **0** (the optical‑black pedestal is
                // delivered by the first residual). The mid‑range seed
                // pins the histogram peak at near‑saturation on dark
                // and flat fixtures and is the dominant cause of
                // `mode bucket = 16320` in the lossless regression
                // panel. Round‑9 also implicitly relies on the seed‑0
                // baseline (`LZ_TILE_FIRST` in the research harness).
                int predicted;
                if (x == 0 && y == 0) {
                    predicted = 0; // CRX seeds the first pixel at 0; OB pedestal arrives via residual
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

                // Adaptive Rice-Golomb decode of the prediction residual.
                // When the bitstream is exhausted (truncated CRX data), use residual=0
                // so remaining pixels are padded with predicted values rather than zeros.
                int residual;
                if (streamExhausted || !reader.hasMore()) {
                    if (!streamExhausted) {
                        LOG.debug("CRX plane bitstream has no more data at ({},{}), padding remaining "
                                + "pixels with predicted values", x, y);
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
                            LOG.debug("CRX plane bitstream ended early at ({},{}), padding remaining "
                                    + "pixels with predicted values", x, y);
                            streamExhausted = true;
                            residual = 0;
                        } else {
                            throw e;
                        }
                    }
                }

                // Signed residual mapping. The legacy path uses zig-zag; the
                // patent-aligned lossless path stores magnitude in the upper bits
                // and the sign in bit 0.
                int signedResidual;
                if (kUpdateRule == KUpdateRule.PATENT_BRACKET) {
                    int magnitude = residual >>> 1;
                    signedResidual = (residual & 1) == 0 ? magnitude : -magnitude;
                } else if ((residual & 1) == 0) {
                    signedResidual = residual >> 1;
                } else {
                    signedResidual = -(residual >> 1) - 1;
                }

                // Reconstruct pixel
                int pixel = predicted + signedResidual;
                pixel = Math.max(0, Math.min(maxVal, pixel));
                result[y * width + x] = pixel;

                // Update Rice parameter adaptively (rule selected per call).
                int absResidual = Math.abs(signedResidual);
                switch (kUpdateRule) {
                    case JPEG_LS -> {
                        // ITU-T T.87 Annex A.5 accumulator rule.
                        accumulator += absResidual;
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
                        // Periodic normalization (prevent overflow)
                        if (sampleCount >= NORMALIZATION_THRESHOLD) {
                            accumulator >>= 1;
                            sampleCount >>= 1;
                            if (sampleCount == 0) sampleCount = 1;
                        }
                    }
                    case PATENT_BRACKET -> {
                        // Dark-frame-validated lossless rule: react to the
                        // current residual magnitude, but keep the row-local
                        // running lower-bound check so low-noise dark/flat rows
                        // collapse K quickly instead of drifting into lock-loss.
                        accumulator += absResidual;
                        sampleCount++;
                        if (absResidual > (1 << (k + 1)) && k < PATENT_LOSSLESS_K_MAX) {
                            k++;
                        } else if (k > 0 && accumulator <= (sampleCount << (k - 1))) {
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
     * Decodes a CRX lossless Rice-Golomb symbol whose unary prefix is encoded
     * as a run of one bits terminated by zero. This is the direction that keeps
     * the bundled dark lossless fixtures in entropy lock through the full plane.
     */
    private static int decodeOnesUntilZeroRiceGolomb(BitReader reader, int k) throws IOException {
        int q = 0;
        while (true) {
            if (!reader.hasMore()) {
                throw new IOException("Unexpected end of stream while reading CRX unary code");
            }
            if (reader.readBit() == 0) {
                break;
            }
            q++;
            if (q > MAX_RICE_UNARY_COUNT) {
                throw new IOException("CRX unary overflow (>" + MAX_RICE_UNARY_COUNT + ")");
            }
        }
        int r = 0;
        if (k > 0) {
            if (!reader.hasMore()) {
                throw new IOException("Unexpected end of stream while reading CRX Rice-Golomb remainder");
            }
            r = reader.readBits(k);
        }
        return (q << k) | r;
    }

    /**
     * Interleaves 4 decoded planes into a single Bayer-grid array by <em>position</em>
     * (independent of CFA pattern).
     *
     * <p>Plane 0 → top-left of each 2×2 cell (even row, even column)<br>
     * Plane 1 → top-right of each 2×2 cell (even row, odd column)<br>
     * Plane 2 → bottom-left of each 2×2 cell (odd row, even column)<br>
     * Plane 3 → bottom-right of each 2×2 cell (odd row, odd column)</p>
     *
     * <p>The CRX encoder always stores planes in this positional order; the
     * actual colour at each position is determined by the CFA layout (see
     * {@link #resolveBayerPattern(Cmp1Box)}) and applied by downstream
     * white-balance and demosaic stages.</p>
     */
    private static void interleavePlanesByPosition(int[][] planes, int[] output,
                                             int bayerWidth, int bayerHeight,
                                             int planeWidth, int planeHeight) {
        for (int py = 0; py < planeHeight; py++) {
            int bayerY0 = py * 2;
            int bayerY1 = bayerY0 + 1;
            for (int px = 0; px < planeWidth; px++) {
                int planeIdx = py * planeWidth + px;
                int bayerX0 = px * 2;
                int bayerX1 = bayerX0 + 1;

                // Plane 0 → top-left of 2×2 cell (even row, even col)
                if (bayerY0 < bayerHeight && bayerX0 < bayerWidth) {
                    output[bayerY0 * bayerWidth + bayerX0] = planes[0][planeIdx];
                }
                // Plane 1 → top-right (even row, odd col)
                if (bayerY0 < bayerHeight && bayerX1 < bayerWidth) {
                    output[bayerY0 * bayerWidth + bayerX1] = planes[1][planeIdx];
                }
                // Plane 2 → bottom-left (odd row, even col)
                if (bayerY1 < bayerHeight && bayerX0 < bayerWidth) {
                    output[bayerY1 * bayerWidth + bayerX0] = planes[2][planeIdx];
                }
                // Plane 3 → bottom-right (odd row, odd col)
                if (bayerY1 < bayerHeight && bayerX1 < bayerWidth) {
                    output[bayerY1 * bayerWidth + bayerX1] = planes[3][planeIdx];
                }
            }
        }
    }
}