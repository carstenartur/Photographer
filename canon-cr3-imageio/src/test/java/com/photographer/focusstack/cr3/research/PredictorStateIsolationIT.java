package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.bmff.Iad1Box;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.container.Cr3Track;
import com.photographer.focusstack.cr3.raw.Cr3RawImage;
import com.photographer.focusstack.cr3.raw.codec.BitReader;
import com.photographer.focusstack.cr3.raw.codec.CrxHeader;
import com.photographer.focusstack.cr3.raw.codec.CrxLosslessRawDecoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Phase-B Predictor State Isolation experiment.
 *
 * <p>Isolates whether the remaining CR3 lossless RAW decoding failure is caused
 * by the predictor state machine. Tests three hypothesis groups:</p>
 *
 * <ul>
 *   <li><b>B1 — row context</b>: whether the predictor uses the wrong previous-row
 *       context (shifted or alternative N/NW/NE source).</li>
 *   <li><b>B2 — tile reset</b>: whether the predictor state is incorrectly reset,
 *       continued, or seeded at tile boundaries.</li>
 *   <li><b>B3 — plane reset</b>: whether the predictor state is incorrectly reset
 *       or shared across planes.</li>
 * </ul>
 *
 * <p>Primary metrics are OB falsification metrics from IAD1 optical-black regions:
 * OB stdDev, median stability, row/column profile flatness, lag-1 autocorrelation,
 * and banding score. PRVW Pearson is recorded as a secondary metric.</p>
 *
 * <p>Visual diagnostics are generated as PNG images under
 * {@code target/predictor-state-isolation/images/}.</p>
 *
 * <p>Outputs:</p>
 * <ul>
 *   <li>{@code target/predictor-state-isolation/results.tsv}</li>
 *   <li>{@code target/predictor-state-isolation/results.json}</li>
 *   <li>{@code target/predictor-state-isolation/results.md}</li>
 *   <li>{@code target/predictor-state-isolation/images/<fixture>-<variant>.png}</li>
 * </ul>
 *
 * <p>No production decoder code is changed. No broad sweeps are performed.</p>
 */
class PredictorStateIsolationIT {

    private static final Logger log = LoggerFactory.getLogger(PredictorStateIsolationIT.class);

    private static final Path SAMPLES_DIR = Path.of("src", "test", "resources", "samples");
    private static final Path RESEARCH_DIR = SAMPLES_DIR.resolve("research");
    private static final Path REPORT_DIR   = Path.of("target", "predictor-state-isolation");
    private static final Path IMAGE_DIR    = REPORT_DIR.resolve("images");

    private static final int HIST_BUCKET = 64;
    private static final int[] AUTOCORR_LAGS = {1, 2, 4, 8, 16, 32};

    // ──────────────────────────────────────────────────────────────────────────
    // Predictor variant definitions
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * All predictor-state variants tested in this experiment.
     *
     * <p>Each variant corresponds to a specific Phase-B hypothesis about what
     * the predictor state machine is doing wrong.</p>
     */
    enum Variant {
        /** B1/B2/B3 baseline: standard MED(W, N, NW), tile-local reset, plane-independent. */
        BASELINE("baseline", "B1/B2/B3",
                "Standard MED(W,N,NW), tile-local reset, plane-independent (production semantics)"),
        /** B1: above-row context shifted by one row (y-2 instead of y-1 for N/NW). */
        B1_ROW_OFFSET("b1-row-offset", "B1",
                "B1: N context shifted one row up (y-2 instead of y-1)"),
        /** B1: predict from North only, ignoring W and NW. */
        B1_NORTH_ONLY("b1-north-only", "B1",
                "B1: North-only predictor (N without MED)"),
        /** B1: predict from West only (left neighbor only). */
        B1_WEST_ONLY("b1-west-only", "B1",
                "B1: West-only predictor (W without MED)"),
        /** B2: carry last column of tile T as left-context for first column of tile T+1. */
        B2_TILE_CONTINUED("b2-tile-continued", "B2",
                "B2: Right-edge of tile T carried as left-context into tile T+1"),
        /** B2: seed first pixel of each tile at mid-scale (1<<(bd-1)) instead of 0. */
        B2_TILE_MIDSCALE_SEED("b2-tile-midscale", "B2",
                "B2: First pixel of each tile seeded at mid-scale"),
        /** B3: seed each plane N's first pixel from plane 0's first decoded pixel. */
        B3_PLANE_SEEDED("b3-plane-seeded", "B3",
                "B3: First pixel of each plane seeded from plane 0's first decoded pixel"),
        /** B3: seed each plane's first pixel at mid-scale (1<<(bd-1)). */
        B3_PLANE_MIDSCALE_SEED("b3-plane-midscale", "B3",
                "B3: First pixel of each plane seeded at mid-scale");

        final String id;
        final String hypothesis;
        final String description;

        Variant(String id, String hypothesis, String description) {
            this.id = id;
            this.hypothesis = hypothesis;
            this.description = description;
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Main test entry-point
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Phase-B predictor state isolation: B1/B2/B3 variants with OB falsification metrics")
    void runPredictorStateIsolation() throws Exception {
        if (!Files.isDirectory(SAMPLES_DIR)) {
            log.info("No samples dir at {} — skipping", SAMPLES_DIR.toAbsolutePath());
            return;
        }

        List<Path> all = new ArrayList<>();
        collectCr3(SAMPLES_DIR, all);
        if (Files.isDirectory(RESEARCH_DIR)) collectCr3(RESEARCH_DIR, all);
        all.sort(Comparator.comparing(Path::toString));
        if (all.isEmpty()) {
            log.info("No CR3 fixtures — skipping");
            return;
        }

        Files.createDirectories(REPORT_DIR);
        Files.createDirectories(IMAGE_DIR);

        List<VariantResult> allResults = new ArrayList<>();

        for (Path fixture : all) {
            try {
                analyseFixture(fixture, allResults);
            } catch (Exception ex) {
                log.warn("Skipped {}: {}", fixture.getFileName(), ex.toString());
            }
        }

        writeTsv(allResults, REPORT_DIR.resolve("results.tsv"));
        writeJson(allResults, REPORT_DIR.resolve("results.json"));
        writeMarkdown(allResults, REPORT_DIR.resolve("results.md"));

        log.info("Predictor state isolation complete. {} results written to {}",
                allResults.size(), REPORT_DIR.toAbsolutePath());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Per-fixture analysis
    // ──────────────────────────────────────────────────────────────────────────

    private static void analyseFixture(Path file, List<VariantResult> out) throws Exception {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            Cr3Container c = Cr3Parser.parse(in);
            Cr3Track raw = c.tracks().stream()
                    .filter(t -> t.type() == Cr3Track.TrackType.CRX_BIG
                              || t.type() == Cr3Track.TrackType.CRX_RAW)
                    .findFirst().orElse(null);
            if (raw == null) {
                log.debug("{}: no CRX_BIG/CRX_RAW track — skipping", file.getFileName());
                return;
            }

            Cmp1Box cmp1 = raw.cmp1();
            if (cmp1 != null && cmp1.waveletLevels() > 0) {
                log.debug("{}: C-RAW (waveletLevels={}) — skipping lossless analysis",
                        file.getFileName(), cmp1.waveletLevels());
                return;
            }

            Iad1Box iad1 = raw.iad1();
            if (iad1 == null) {
                log.debug("{}: no IAD1 box — OB metrics unavailable", file.getFileName());
                return;
            }

            int imgW = cmp1 != null && cmp1.imageWidth()  > 0 ? cmp1.imageWidth()  : raw.width();
            int imgH = cmp1 != null && cmp1.imageHeight() > 0 ? cmp1.imageHeight() : raw.height();
            int bd   = cmp1 != null && cmp1.bitsPerSample() > 0 ? cmp1.bitsPerSample() : 14;
            CrxHeader.CodecVersion ver = c.cncvBox() == null
                    ? CrxHeader.CodecVersion.V1
                    : CrxHeader.detectVersion(c.cncvBox().versionString());

            byte[] crxBytes = new byte[(int) raw.dataSize()];
            in.seek(raw.dataOffset());
            in.readFully(crxBytes);

            // OB regions from IAD1
            int[] leftOb = clampRect(iad1.leftObLeft(), iad1.leftObTop(),
                    iad1.leftObRight(), iad1.leftObBottom(), imgW, imgH);
            int[] topOb  = clampRect(iad1.topObLeft(),  iad1.topObTop(),
                    iad1.topObRight(),  iad1.topObBottom(),  imgW, imgH);
            boolean hasOb = (leftOb[2] > 0 && leftOb[3] > 0) || (topOb[2] > 0 && topOb[3] > 0);
            if (!hasOb) {
                log.debug("{}: IAD1 OB rectangles are empty — skipping OB analysis", file.getFileName());
                return;
            }

            String fixtureName = file.getFileName().toString().replace(".cr3", "");
            String scene = sceneTag(fixtureName);

            // Extract JPEG preview bytes once (for secondary Pearson metric)
            byte[] previewJpegBytes = extractPreviewJpeg(in, c);

            // Decode baseline via production decoder (verifies parseable)
            Cr3RawImage baselineImg;
            try {
                baselineImg = CrxLosslessRawDecoder.decode(crxBytes, imgW, imgH, bd, ver, cmp1);
            } catch (Exception e) {
                log.info("{}: production decoder failed — {}", file.getFileName(), e.toString());
                return;
            }

            log.info("Analysing {} ({}×{}, {}bit, scene={})", file.getFileName(), imgW, imgH, bd, scene);

            // For each variant, decode and measure OB metrics
            for (Variant variant : Variant.values()) {
                try {
                    int[] bayerData = decodeVariant(variant, crxBytes, imgW, imgH, bd, ver, cmp1,
                            baselineImg);

                    // Compute OB metrics for both regions, collect per plane
                    for (int plane = 0; plane < 4; plane++) {
                        ObStats leftStats = leftOb[2] > 0 && leftOb[3] > 0
                                ? computeObStats(bayerData, imgW, imgH,
                                                 leftOb[0], leftOb[1], leftOb[2], leftOb[3],
                                                 plane, bd)
                                : ObStats.EMPTY;
                        ObStats topStats = topOb[2] > 0 && topOb[3] > 0
                                ? computeObStats(bayerData, imgW, imgH,
                                                 topOb[0], topOb[1], topOb[2], topOb[3],
                                                 plane, bd)
                                : ObStats.EMPTY;

                        // Secondary: PRVW Pearson on plane 0 only (expensive)
                        double prvwPearson = Double.NaN;
                        if (plane == 0 && previewJpegBytes != null) {
                            try {
                                prvwPearson = computePrvwPearson(bayerData, imgW, imgH,
                                        (imgW + 1) / 2, (imgH + 1) / 2, previewJpegBytes);
                            } catch (Exception e) {
                                log.debug("{} {}: PRVW Pearson failed — {}", variant.id,
                                        file.getFileName(), e.toString());
                            }
                        }

                        out.add(new VariantResult(fixtureName, scene, variant,
                                plane, "leftOB", leftStats,
                                plane, "topOB", topStats,
                                prvwPearson));
                    }

                    // Visual: render plane 0 as PNG (once per variant, not per plane)
                    Path imagePath = IMAGE_DIR.resolve(fixtureName + "-" + variant.id + ".png");
                    int planeW = (imgW + 1) / 2;
                    int planeH = (imgH + 1) / 2;
                    int[] plane0 = extractPlane(bayerData, imgW, planeW, planeH, 0);
                    renderPlaneAsPng(plane0, planeW, planeH, bd,
                            leftOb, topOb, imagePath);

                } catch (Exception e) {
                    log.warn("  {} {}: decode/metrics failed — {}", fixtureName, variant.id, e.toString());
                }
            }

            // Visual: also render the embedded JPEG preview
            if (previewJpegBytes != null) {
                Path previewPath = IMAGE_DIR.resolve(fixtureName + "-preview.jpg");
                if (!Files.exists(previewPath)) {
                    Files.write(previewPath, previewJpegBytes);
                }
            }

            // Visual: difference images (variant vs baseline, plane 0)
            try {
                renderDifferenceImages(crxBytes, imgW, imgH, bd, ver, cmp1, baselineImg,
                        fixtureName, leftOb, topOb);
            } catch (Exception e) {
                log.debug("{}: difference images failed — {}", fixtureName, e.toString());
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Variant decoder
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Decodes the given CRX data using the specified variant predictor-state policy.
     *
     * <p>BASELINE defers to the production decoder. All other variants use a
     * test-scope reimplementation of the PATENT_BRACKET entropy path with the
     * specific predictor-state change applied. No production code is altered.</p>
     */
    private static int[] decodeVariant(Variant variant, byte[] crxBytes, int imgW, int imgH,
                                        int bd, CrxHeader.CodecVersion ver, Cmp1Box cmp1,
                                        Cr3RawImage baselineImg) throws Exception {
        if (variant == Variant.BASELINE) {
            return baselineImg.data().clone();
        }
        return decodeWithVariantPredictor(variant, crxBytes, imgW, imgH, bd, ver, cmp1);
    }

    /**
     * Decodes CRX lossless data with the specified predictor-state variant.
     *
     * <p>This is a test-scope decoder that reimplements the PATENT_BRACKET entropy
     * path (ones-until-zero unary, K_INIT=1, per-row K reset, bit-0 sign mapping)
     * while varying the predictor-state semantics as specified by the variant.</p>
     */
    private static int[] decodeWithVariantPredictor(Variant variant, byte[] crxBytes,
                                                     int imgW, int imgH, int bd,
                                                     CrxHeader.CodecVersion ver, Cmp1Box cmp1)
            throws IOException {
        // Parse the CRX header to understand tile/plane structure
        CrxHeader header;
        byte[] data;
        try {
            header = CrxHeader.parse(crxBytes, ver);
            data = crxBytes;
        } catch (IOException e) {
            int skip = cmp1 != null ? cmp1.mdatHeaderSize() : 0;
            if (skip > 0 && skip < crxBytes.length) {
                data = Arrays.copyOfRange(crxBytes, skip, crxBytes.length);
                header = CrxHeader.parse(data, ver);
            } else {
                throw e;
            }
        }

        int planeW = (imgW + 1) / 2;
        int planeH = (imgH + 1) / 2;
        int numTiles = header.tiles().size();
        int nominalTilePW = resolveTilePlaneWidth(cmp1, numTiles, planeW, imgW);
        int tilePH = resolveTilePlaneHeight(cmp1, planeH);

        int[][] planes = new int[4][];
        for (int i = 0; i < 4; i++) planes[i] = new int[planeW * planeH];

        CrxHeader.Tile[] sortedTiles = header.tiles().toArray(new CrxHeader.Tile[0]);
        Arrays.sort(sortedTiles, Comparator.comparingInt(CrxHeader.Tile::index));

        // For B3: track first decoded pixel per plane
        int[] planeSeedPixels = new int[4];
        Arrays.fill(planeSeedPixels, 0);

        int tileXOffset = 0;
        // For B2_TILE_CONTINUED: store the right-edge column of each plane per tile
        int[][][] prevTileRightCol = new int[4][][]; // [planeIdx][tileIdx][row]

        final byte[] finalData = data;

        for (int ti = 0; ti < sortedTiles.length; ti++) {
            CrxHeader.Tile tile = sortedTiles[ti];
            int remaining = planeW - tileXOffset;
            int tilePW = ti == sortedTiles.length - 1
                    ? remaining : Math.min(nominalTilePW, remaining);
            if (tilePW <= 0) break;

            final int finalTileXOffset = tileXOffset;

            for (CrxHeader.Plane plane : tile.planes()) {
                int planeIdx = plane.index();
                if (planeIdx < 0 || planeIdx >= 4) continue;
                if (plane.subbands().isEmpty()) continue;

                CrxHeader.Subband sub = plane.subbands().getFirst();
                int subOff  = (int) sub.dataOffset();
                int subEnd  = subOff + (int) sub.dataSize();
                subEnd = Math.min(subEnd, finalData.length);
                if (subOff >= finalData.length || subEnd <= subOff) continue;

                byte[] payload = Arrays.copyOfRange(finalData, subOff, subEnd);

                // Determine seed for first pixel of this tile/plane
                int firstPixelSeed = 0;
                // B2: tile-midscale seeds each tile's first pixel at midscale
                if (variant == Variant.B2_TILE_MIDSCALE_SEED) {
                    firstPixelSeed = 1 << (bd - 1);
                }
                // B3: plane-seeded seeds each plane N from plane 0's first pixel
                if (variant == Variant.B3_PLANE_SEEDED && planeIdx > 0 && ti == 0) {
                    firstPixelSeed = planeSeedPixels[0];
                }
                // B3: plane-midscale seeds each plane's first pixel at mid-scale
                if (variant == Variant.B3_PLANE_MIDSCALE_SEED) {
                    firstPixelSeed = 1 << (bd - 1);
                }

                // Previous tile right column (for B2_TILE_CONTINUED)
                int[] prevRightCol = null;
                if (variant == Variant.B2_TILE_CONTINUED && ti > 0
                        && prevTileRightCol[planeIdx] != null) {
                    int[][] tileRightCols = prevTileRightCol[planeIdx];
                    if (tileRightCols.length >= ti) {
                        prevRightCol = tileRightCols[ti - 1];
                    }
                }

                int[] decoded = decodePlaneVariant(variant, payload, tilePW, tilePH, bd,
                        firstPixelSeed, prevRightCol);

                // Store plane-0's first decoded pixel for B3 seeding
                if (planeIdx == 0 && ti == 0 && decoded.length > 0) {
                    planeSeedPixels[0] = decoded[0];
                }

                // For B2_TILE_CONTINUED: record this tile's right-edge column
                if (variant == Variant.B2_TILE_CONTINUED) {
                    if (prevTileRightCol[planeIdx] == null) {
                        prevTileRightCol[planeIdx] = new int[sortedTiles.length][];
                    }
                    int[] rightCol = new int[tilePH];
                    for (int row = 0; row < tilePH && row < decoded.length / tilePW; row++) {
                        rightCol[row] = decoded[row * tilePW + tilePW - 1];
                    }
                    prevTileRightCol[planeIdx][ti] = rightCol;
                }

                // Copy into full plane
                for (int row = 0; row < tilePH; row++) {
                    int srcBase = row * tilePW;
                    int dstBase = row * planeW + finalTileXOffset;
                    int copyLen = Math.min(tilePW, planeW - finalTileXOffset);
                    if (copyLen > 0 && srcBase + copyLen <= decoded.length
                            && dstBase + copyLen <= planes[planeIdx].length) {
                        System.arraycopy(decoded, srcBase, planes[planeIdx], dstBase, copyLen);
                    }
                }
            }
            tileXOffset += tilePW;
        }

        // Interleave planes into Bayer grid
        int[] bayer = new int[imgW * imgH];
        for (int py = 0; py < planeH; py++) {
            for (int px = 0; px < planeW; px++) {
                int pi = py * planeW + px;
                int by0 = py * 2, bx0 = px * 2;
                int by1 = by0 + 1, bx1 = bx0 + 1;
                if (by0 < imgH && bx0 < imgW) bayer[by0 * imgW + bx0] = planes[0][pi];
                if (by0 < imgH && bx1 < imgW) bayer[by0 * imgW + bx1] = planes[1][pi];
                if (by1 < imgH && bx0 < imgW) bayer[by1 * imgW + bx0] = planes[2][pi];
                if (by1 < imgH && bx1 < imgW) bayer[by1 * imgW + bx1] = planes[3][pi];
            }
        }
        return bayer;
    }

    /**
     * Decodes a single plane payload using the PATENT_BRACKET entropy path with
     * a configurable predictor-state variant.
     *
     * <p>Entropy semantics (unchanged from PATENT_BRACKET production):
     * ones-until-zero unary, K_INIT=1, per-row K reset, bit-0 sign mapping.</p>
     *
     * @param variant        which predictor-state variant to apply
     * @param payload        raw entropy-coded bytes for one plane
     * @param width          plane width in pixels
     * @param height         plane height in pixels
     * @param bd             bit depth
     * @param firstPixelSeed seed value for the first pixel (top-left corner)
     * @param prevTileRightCol right-edge column from the previous tile (for B2 continuation),
     *                         or {@code null} for first tile / non-B2 variants
     */
    private static int[] decodePlaneVariant(Variant variant, byte[] payload,
                                             int width, int height, int bd,
                                             int firstPixelSeed, int[] prevTileRightCol) {
        int[] out = new int[width * height];
        int maxVal = (1 << bd) - 1;
        BitReader r = new BitReader(payload);

        boolean exhausted = false;

        for (int y = 0; y < height; y++) {
            // PATENT_BRACKET: reset K state per row
            int k = 1;
            long accum = 0;
            long cnt = 1;

            for (int x = 0; x < width; x++) {
                int predicted = computePredictor(variant, out, x, y, width, height,
                        firstPixelSeed, bd, prevTileRightCol);

                int rawSymbol;
                if (exhausted) {
                    rawSymbol = 0;
                } else {
                    try {
                        rawSymbol = readOnesUntilZero(r, k);
                    } catch (IOException e) {
                        exhausted = true;
                        rawSymbol = 0;
                    }
                }

                // Bit-0 sign mapping (PATENT_BRACKET canonical)
                int magnitude = rawSymbol >>> 1;
                int signed = (rawSymbol & 1) == 0 ? magnitude : -magnitude;

                int pixel = predicted + signed;
                if (pixel < 0) pixel = 0;
                if (pixel > maxVal) pixel = maxVal;
                out[y * width + x] = pixel;

                // PATENT_BRACKET bounded K adaptation
                int abs = signed < 0 ? -signed : signed;
                accum += abs;
                cnt++;
                if (abs > (1 << (k + 1)) && k < 12) k++;
                else if (k > 0 && accum <= (cnt << (k - 1))) k--;
                if (cnt >= 1024) {
                    accum >>= 1;
                    cnt >>= 1;
                    if (cnt == 0) cnt = 1;
                }
            }
        }
        return out;
    }

    /**
     * Computes the MED predictor for pixel (x, y) under the given variant policy.
     */
    private static int computePredictor(Variant variant, int[] out, int x, int y,
                                         int width, int height, int firstPixelSeed, int bd,
                                         int[] prevTileRightCol) {
        switch (variant) {
            // ── B1_ROW_OFFSET: "above" comes from y-2 instead of y-1
            case B1_ROW_OFFSET -> {
                if (x == 0 && y == 0) return firstPixelSeed;
                if (y == 0) return out[x - 1];
                if (x == 0) {
                    // above = y-2 if available, else y-1
                    int aboveRow = Math.max(0, y - 2);
                    return out[aboveRow * width];
                }
                int aboveRow = Math.max(0, y - 2);
                int W  = out[y       * width + (x - 1)];
                int N  = out[aboveRow * width + x];
                int NW = out[aboveRow * width + (x - 1)];
                return medPredict(W, N, NW);
            }
            // ── B1_NORTH_ONLY: predict from N only
            case B1_NORTH_ONLY -> {
                if (x == 0 && y == 0) return firstPixelSeed;
                if (y == 0) return out[x - 1]; // first row: no N available, use W
                return out[(y - 1) * width + x]; // always predict from N
            }
            // ── B1_WEST_ONLY: predict from W only
            case B1_WEST_ONLY -> {
                if (x == 0 && y == 0) return firstPixelSeed;
                if (x == 0) return out[(y - 1) * width]; // first col: no W, use N
                return out[y * width + (x - 1)]; // always predict from W
            }
            // ── B2_TILE_CONTINUED: left-edge pixels use previous tile's right column
            case B2_TILE_CONTINUED -> {
                if (x == 0 && y == 0) {
                    // If there's a previous tile's right column, use its first pixel as
                    // a seed for the continuation; otherwise fall back to firstPixelSeed
                    return prevTileRightCol != null ? prevTileRightCol[0] : firstPixelSeed;
                }
                if (y == 0) return out[x - 1];
                if (x == 0) {
                    // "above" in the local tile; normal MED with left-context from prev tile
                    int N = out[(y - 1) * width]; // above in this tile
                    // "W" (left neighbor from previous tile) for NW and W computation:
                    int prevW = prevTileRightCol != null ? prevTileRightCol[y]     : 0;
                    int prevNW = prevTileRightCol != null ? prevTileRightCol[y - 1] : 0;
                    return medPredict(prevW, N, prevNW);
                }
                // Standard MED for interior pixels
                int W  = out[y       * width + (x - 1)];
                int N  = out[(y - 1) * width + x];
                int NW = out[(y - 1) * width + (x - 1)];
                return medPredict(W, N, NW);
            }
            // ── All other variants (B2_TILE_MIDSCALE, B3_*): standard MED with custom seed
            default -> {
                if (x == 0 && y == 0) return firstPixelSeed;
                if (y == 0) return out[x - 1];
                if (x == 0) return out[(y - 1) * width];
                int W  = out[y       * width + (x - 1)];
                int N  = out[(y - 1) * width + x];
                int NW = out[(y - 1) * width + (x - 1)];
                return medPredict(W, N, NW);
            }
        }
    }

    /** MED predictor (JPEG-LS Annex A.3 / ITU T.87). */
    private static int medPredict(int W, int N, int NW) {
        if (NW >= Math.max(W, N)) return Math.min(W, N);
        if (NW <= Math.min(W, N)) return Math.max(W, N);
        return W + N - NW;
    }

    /** Reads a Rice-Golomb symbol using ones-until-zero unary prefix (PATENT_BRACKET). */
    private static int readOnesUntilZero(BitReader r, int k) throws IOException {
        int q = 0;
        while (r.readBit() == 1) {
            q++;
            if (q > 65536) throw new IOException("CRX unary overflow in variant decoder");
        }
        int rem = k > 0 ? r.readBits(k) : 0;
        return (q << k) | rem;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Tile/plane geometry helpers (mirrors production code, test-scope only)
    // ──────────────────────────────────────────────────────────────────────────

    private static int resolveTilePlaneWidth(Cmp1Box cmp1, int numTiles,
                                              int planeWidth, int rawWidth) {
        if (cmp1 != null && cmp1.tileWidth() > 0) {
            int pw = Math.max(1, (cmp1.tileWidth() + 1) / 2);
            return Math.min(pw, planeWidth);
        }
        return numTiles > 1 ? Math.max(1, (rawWidth / numTiles + 1) / 2) : planeWidth;
    }

    private static int resolveTilePlaneHeight(Cmp1Box cmp1, int planeHeight) {
        if (cmp1 != null && cmp1.tileHeight() > 0) {
            int ph = Math.max(1, (cmp1.tileHeight() + 1) / 2);
            return Math.min(ph, planeHeight);
        }
        return planeHeight;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // OB metrics computation
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Computes OB falsification metrics for one Bayer plane within one OB region.
     *
     * @param bayer  full Bayer image data (row-major, width=imgW)
     * @param imgW   full image width
     * @param imgH   full image height
     * @param obX    OB region left (in full Bayer coords)
     * @param obY    OB region top
     * @param obW    OB region width
     * @param obH    OB region height
     * @param plane  Bayer plane index (0=TL, 1=TR, 2=BL, 3=BR)
     * @param bd     bit depth
     */
    private static ObStats computeObStats(int[] bayer, int imgW, int imgH,
                                           int obX, int obY, int obW, int obH,
                                           int plane, int bd) {
        int rowOff = (plane >> 1) & 1;
        int colOff = plane & 1;
        int planeH = (obH - rowOff + 1) / 2;
        int planeW = (obW - colOff + 1) / 2;
        if (planeH <= 0 || planeW <= 0) return ObStats.EMPTY;

        long count = 0;
        double sum = 0, sqSum = 0;
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        int buckets = (1 << bd) / HIST_BUCKET;
        long[] hist = new long[Math.max(1, buckets)];

        double[] rowSums = new double[planeH];
        long[]   rowN    = new long[planeH];
        double[] colSums = new double[planeW];
        long[]   colN    = new long[planeW];

        int sampMax = Math.min(8192, planeH * planeW);
        int[] sample = new int[sampMax];
        int sampGot = 0;
        int sampStep = Math.max(1, (planeH * planeW) / sampMax);
        int sampSeen = 0;

        for (int y = rowOff, py = 0; y < obH && py < planeH; y += 2, py++) {
            int srcY = obY + y;
            if (srcY >= imgH) break;
            int rowBase = srcY * imgW;
            for (int x = colOff, px = 0; x < obW && px < planeW; x += 2, px++) {
                int srcX = obX + x;
                if (srcX >= imgW) break;
                int v = bayer[rowBase + srcX];
                count++; sum += v; sqSum += (double) v * v;
                if (v < min) min = v;
                if (v > max) max = v;
                int bi = Math.min(hist.length - 1, Math.max(0, v / HIST_BUCKET));
                hist[bi]++;
                rowSums[py] += v; rowN[py]++;
                colSums[px] += v; colN[px]++;
                if (sampGot < sampMax && (sampSeen++ % sampStep) == 0) {
                    sample[sampGot++] = v;
                }
            }
        }

        if (count == 0) return ObStats.EMPTY;

        // Mode
        int modeBucket = 0;
        long modeCnt = 0;
        for (int i = 0; i < hist.length; i++) if (hist[i] > modeCnt) { modeCnt = hist[i]; modeBucket = i; }

        // Median
        int[] used = Arrays.copyOf(sample, sampGot);
        Arrays.sort(used);
        int median = used[used.length / 2];

        // Mean and stdDev
        double mean = sum / count;
        double variance = sqSum / count - mean * mean;
        double stdDev = Math.sqrt(Math.max(0, variance));

        // fracWithin256
        long near = 0;
        for (int v : used) if (Math.abs(v - median) <= 256) near++;
        double fracWithin256 = (double) near / used.length;

        // Row/col profile std (flatness indicator)
        double rowProfileStd = computeProfileStd(rowSums, rowN);
        double colProfileStd = computeProfileStd(colSums, colN);

        // Row-mean profile for autocorrelation
        double[] rowMeans = new double[planeH];
        for (int i = 0; i < planeH; i++) rowMeans[i] = rowN[i] > 0 ? rowSums[i] / rowN[i] : 0;

        double[] autocorr = new double[AUTOCORR_LAGS.length];
        for (int i = 0; i < AUTOCORR_LAGS.length; i++) {
            autocorr[i] = computeAutocorr(rowMeans, AUTOCORR_LAGS[i]);
        }

        double bandingScore = 0;
        for (double ac : autocorr) if (Math.abs(ac) > Math.abs(bandingScore)) bandingScore = ac;

        return new ObStats(count, min, max, median, modeBucket * HIST_BUCKET,
                mean, stdDev, fracWithin256, rowProfileStd, colProfileStd,
                autocorr, bandingScore);
    }

    private static double computeProfileStd(double[] sums, long[] counts) {
        int n = 0;
        double[] vals = new double[sums.length];
        for (int i = 0; i < sums.length; i++) if (counts[i] > 0) vals[n++] = sums[i] / counts[i];
        if (n < 2) return 0;
        double m = 0;
        for (int i = 0; i < n; i++) m += vals[i];
        m /= n;
        double v = 0;
        for (int i = 0; i < n; i++) { double d = vals[i] - m; v += d * d; }
        return Math.sqrt(v / n);
    }

    private static double computeAutocorr(double[] arr, int lag) {
        if (arr.length <= lag) return 0;
        double sum = 0;
        for (double v : arr) sum += v;
        double mean = sum / arr.length;
        double num = 0, den = 0;
        for (int i = 0; i < arr.length; i++) {
            double d = arr[i] - mean;
            den += d * d;
            if (i + lag < arr.length) num += d * (arr[i + lag] - mean);
        }
        return den > 0 ? num / den : 0;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PRVW Pearson (secondary metric)
    // ──────────────────────────────────────────────────────────────────────────

    private static double computePrvwPearson(int[] bayerData, int imgW, int imgH,
                                              int planeW, int planeH,
                                              byte[] previewJpegBytes) throws IOException {
        BufferedImage preview = ImageIO.read(new ByteArrayInputStream(previewJpegBytes));
        if (preview == null) return Double.NaN;

        // Downsample preview luminance to plane grid
        int[] refLum = new int[planeW * planeH];
        double scaleX = (double) preview.getWidth()  / planeW;
        double scaleY = (double) preview.getHeight() / planeH;
        for (int py = 0; py < planeH; py++) {
            int previewY = (int) Math.min(preview.getHeight() - 1, py * scaleY);
            for (int px = 0; px < planeW; px++) {
                int previewX = (int) Math.min(preview.getWidth() - 1, px * scaleX);
                int rgb = preview.getRGB(previewX, previewY);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >>  8) & 0xFF;
                int b =  rgb        & 0xFF;
                refLum[py * planeW + px] = (r * 299 + g * 587 + b * 114) / 1000;
            }
        }

        // Extract plane 0 from Bayer grid
        int[] plane0 = extractPlane(bayerData, imgW, planeW, planeH, 0);

        // Normalise plane0 to 0..255 for correlation
        int mn = Integer.MAX_VALUE, mx = Integer.MIN_VALUE;
        for (int v : plane0) { if (v < mn) mn = v; if (v > mx) mx = v; }
        int range = Math.max(1, mx - mn);
        int[] norm = new int[planeW * planeH];
        for (int i = 0; i < plane0.length; i++) norm[i] = (plane0[i] - mn) * 255 / range;

        return pearson(norm, refLum, Math.min(norm.length, refLum.length));
    }

    private static double pearson(int[] a, int[] b, int n) {
        if (n < 8) return Double.NaN;
        double sa = 0, sb = 0;
        for (int i = 0; i < n; i++) { sa += a[i]; sb += b[i]; }
        double ma = sa / n, mb = sb / n;
        double num = 0, da = 0, db = 0;
        for (int i = 0; i < n; i++) {
            double xa = a[i] - ma, xb = b[i] - mb;
            num += xa * xb; da += xa * xa; db += xb * xb;
        }
        double denom = Math.sqrt(da * db);
        return denom <= 0 ? 0 : num / denom;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Plane extraction and visualization
    // ──────────────────────────────────────────────────────────────────────────

    private static int[] extractPlane(int[] bayer, int imgW, int planeW, int planeH, int plane) {
        int rowOff = (plane >> 1) & 1;
        int colOff = plane & 1;
        int imgH = planeH * 2;
        int[] out = new int[planeW * planeH];
        for (int py = 0; py < planeH; py++) {
            int bayerY = py * 2 + rowOff;
            if (bayerY >= imgH) continue;
            for (int px = 0; px < planeW; px++) {
                int bayerX = px * 2 + colOff;
                if (bayerX < imgW && bayerY * imgW + bayerX < bayer.length) {
                    out[py * planeW + px] = bayer[bayerY * imgW + bayerX];
                }
            }
        }
        return out;
    }

    /**
     * Renders a plane array as a normalized 8-bit grayscale PNG.
     *
     * <p>OB regions are outlined with a white border overlay so human reviewers
     * can quickly see whether the black regions look flat (as they physically
     * should) or show banding/artefacts.</p>
     */
    private static void renderPlaneAsPng(int[] plane, int planeW, int planeH, int bd,
                                          int[] leftObBayer, int[] topObBayer, Path output)
            throws IOException {
        if (planeW <= 0 || planeH <= 0 || plane.length == 0) return;

        // Find range for 8-bit normalisation
        int mn = Integer.MAX_VALUE, mx = Integer.MIN_VALUE;
        for (int v : plane) { if (v < mn) mn = v; if (v > mx) mx = v; }
        int range = Math.max(1, mx - mn);

        BufferedImage img = new BufferedImage(planeW, planeH, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < planeH; y++) {
            for (int x = 0; x < planeW; x++) {
                int v = plane[y * planeW + x];
                int gray = (v - mn) * 255 / range;
                int rgb = (gray << 16) | (gray << 8) | gray;
                img.setRGB(x, y, rgb);
            }
        }

        // Overlay OB region borders (white, 1-pixel border)
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        // Left OB (convert Bayer coords → plane coords, plane 0 = colOff=0, rowOff=0)
        if (leftObBayer[2] > 0 && leftObBayer[3] > 0) {
            int ox = leftObBayer[0] / 2;
            int oy = leftObBayer[1] / 2;
            int ow = Math.max(1, leftObBayer[2] / 2);
            int oh = Math.max(1, leftObBayer[3] / 2);
            g.drawRect(Math.min(ox, planeW - 1), Math.min(oy, planeH - 1),
                       Math.min(ow, planeW - ox), Math.min(oh, planeH - oy));
        }
        // Top OB
        if (topObBayer[2] > 0 && topObBayer[3] > 0) {
            int ox = topObBayer[0] / 2;
            int oy = topObBayer[1] / 2;
            int ow = Math.max(1, topObBayer[2] / 2);
            int oh = Math.max(1, topObBayer[3] / 2);
            g.drawRect(Math.min(ox, planeW - 1), Math.min(oy, planeH - 1),
                       Math.min(ow, planeW - ox), Math.min(oh, planeH - oy));
        }
        g.dispose();

        ImageIO.write(img, "PNG", output.toFile());
    }

    /**
     * Renders difference images for all non-baseline variants against the baseline
     * (plane 0, normalized to ±128 around grey centre = no change).
     */
    private static void renderDifferenceImages(byte[] crxBytes, int imgW, int imgH, int bd,
                                                CrxHeader.CodecVersion ver, Cmp1Box cmp1,
                                                Cr3RawImage baselineImg,
                                                String fixtureName,
                                                int[] leftObBayer, int[] topObBayer)
            throws Exception {
        int planeW = (imgW + 1) / 2;
        int planeH = (imgH + 1) / 2;
        int[] basePlane0 = extractPlane(baselineImg.data(), imgW, planeW, planeH, 0);

        for (Variant v : Variant.values()) {
            if (v == Variant.BASELINE) continue;
            try {
                int[] varBayer = decodeWithVariantPredictor(v, crxBytes, imgW, imgH, bd, ver, cmp1);
                int[] varPlane0 = extractPlane(varBayer, imgW, planeW, planeH, 0);
                Path diffPath = IMAGE_DIR.resolve(fixtureName + "-" + v.id + "-diff.png");
                renderDiffPng(basePlane0, varPlane0, planeW, planeH, leftObBayer, topObBayer, diffPath);
            } catch (Exception e) {
                log.debug("{} diff vs {}: {}", fixtureName, v.id, e.toString());
            }
        }
    }

    private static void renderDiffPng(int[] base, int[] variant, int planeW, int planeH,
                                        int[] leftObBayer, int[] topObBayer, Path output)
            throws IOException {
        int n = Math.min(base.length, variant.length);
        BufferedImage img = new BufferedImage(planeW, planeH, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < n; i++) {
            int diff = variant[i] - base[i];
            // Map difference to 8-bit: centre=128, ±2048 range
            int v = 128 + (diff * 128 / 2048);
            v = Math.max(0, Math.min(255, v));
            img.setRGB(i % planeW, i / planeW, (v << 16) | (v << 8) | v);
        }
        // Overlay OB regions in red
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        if (leftObBayer[2] > 0 && leftObBayer[3] > 0) {
            int ox = leftObBayer[0] / 2, oy = leftObBayer[1] / 2;
            int ow = Math.max(1, leftObBayer[2] / 2), oh = Math.max(1, leftObBayer[3] / 2);
            g.drawRect(Math.min(ox, planeW - 1), Math.min(oy, planeH - 1),
                       Math.min(ow, planeW - ox), Math.min(oh, planeH - oy));
        }
        if (topObBayer[2] > 0 && topObBayer[3] > 0) {
            int ox = topObBayer[0] / 2, oy = topObBayer[1] / 2;
            int ow = Math.max(1, topObBayer[2] / 2), oh = Math.max(1, topObBayer[3] / 2);
            g.drawRect(Math.min(ox, planeW - 1), Math.min(oy, planeH - 1),
                       Math.min(ow, planeW - ox), Math.min(oh, planeH - oy));
        }
        g.dispose();
        ImageIO.write(img, "PNG", output.toFile());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Preview JPEG extraction
    // ──────────────────────────────────────────────────────────────────────────

    private static byte[] extractPreviewJpeg(ImageInputStream in, Cr3Container c) {
        try {
            for (Cr3Track t : c.tracks()) {
                if (t.type() == Cr3Track.TrackType.JPEG_BIG
                        || t.type() == Cr3Track.TrackType.JPEG_PREVIEW) {
                    byte[] buf = new byte[(int) t.dataSize()];
                    in.seek(t.dataOffset());
                    in.readFully(buf);
                    return buf;
                }
            }
            if (c.prvwBox() != null && c.prvwBox().jpegData() != null) {
                return c.prvwBox().jpegData();
            }
        } catch (Exception e) {
            log.debug("Could not extract preview JPEG: {}", e.toString());
        }
        return null;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Output writers
    // ──────────────────────────────────────────────────────────────────────────

    private static void writeTsv(List<VariantResult> results, Path path) throws IOException {
        try (var w = Files.newBufferedWriter(path)) {
            w.write("fixture\tscene\tvariant\thypothesis\tplane\tobRegion"
                    + "\tobCount\tobMin\tobMax\tobMedian\tobMode\tobMean\tobStdDev"
                    + "\tobFracWithin256\tobRowProfileStd\tobColProfileStd"
                    + "\tobAutocorrLag1\tobBandingScore"
                    + "\tprvwPearson\n");
            for (VariantResult r : results) {
                writeRow(w, r, "leftOB", r.leftOb());
                writeRow(w, r, "topOB",  r.topOb());
            }
        }
    }

    private static void writeRow(java.io.BufferedWriter w, VariantResult r,
                                  String region, ObStats s) throws IOException {
        w.write(String.format("%s\t%s\t%s\t%s\t%d\t%s"
                        + "\t%d\t%d\t%d\t%d\t%d\t%.2f\t%.2f"
                        + "\t%.4f\t%.2f\t%.2f"
                        + "\t%.4f\t%.4f"
                        + "\t%s%n",
                r.fixture(), r.scene(), r.variant().id, r.variant().hypothesis,
                r.plane(), region,
                s.count(), s.min(), s.max(), s.median(), s.mode(),
                s.mean(), s.stdDev(),
                s.fracWithin256(), s.rowProfileStd(), s.colProfileStd(),
                s.count() > 0 ? s.autocorr()[0] : 0.0,
                s.bandingScore(),
                Double.isNaN(r.prvwPearson()) ? "-" : String.format("%.4f", r.prvwPearson())));
    }

    private static void writeJson(List<VariantResult> results, Path path) throws IOException {
        try (var w = Files.newBufferedWriter(path)) {
            w.write("[\n");
            boolean first = true;
            for (VariantResult r : results) {
                if (!first) w.write(",\n");
                first = false;
                w.write("  {\n");
                w.write(String.format("    \"fixture\": \"%s\",%n", r.fixture()));
                w.write(String.format("    \"scene\": \"%s\",%n", r.scene()));
                w.write(String.format("    \"variant\": \"%s\",%n", r.variant().id));
                w.write(String.format("    \"hypothesis\": \"%s\",%n", r.variant().hypothesis));
                w.write(String.format("    \"plane\": %d,%n", r.plane()));
                writeJsonOb(w, "leftOB",  r.leftOb());
                w.write(",\n");
                writeJsonOb(w, "topOB", r.topOb());
                w.write(",\n");
                w.write(String.format("    \"prvwPearson\": %s%n",
                        Double.isNaN(r.prvwPearson()) ? "null"
                                : String.format("%.4f", r.prvwPearson())));
                w.write("  }");
            }
            w.write("\n]\n");
        }
    }

    private static void writeJsonOb(java.io.BufferedWriter w, String key, ObStats s)
            throws IOException {
        w.write(String.format("    \"%s\": {\"stdDev\": %.2f, \"median\": %d, "
                        + "\"fracWithin256\": %.4f, \"rowProfileStd\": %.2f, "
                        + "\"colProfileStd\": %.2f, \"autocorrLag1\": %.4f, "
                        + "\"bandingScore\": %.4f}",
                key, s.stdDev(), s.median(), s.fracWithin256(),
                s.rowProfileStd(), s.colProfileStd(),
                s.count() > 0 ? s.autocorr()[0] : 0.0, s.bandingScore()));
    }

    private static void writeMarkdown(List<VariantResult> results, Path path) throws IOException {
        try (var w = Files.newBufferedWriter(path)) {
            w.write("# Phase-B Predictor State Isolation — Results\n\n");
            w.write("Generated: " + java.time.Instant.now() + "\n\n");
            w.write("Primary metric: OB stdDev (lower = more physically plausible)\n\n");
            w.write("## Variants tested\n\n");
            for (Variant v : Variant.values()) {
                w.write(String.format("- **%s** (%s): %s%n", v.id, v.hypothesis, v.description));
            }
            w.write("\n## OB StdDev summary (plane 0, topOB region)\n\n");
            w.write("| Fixture | Scene | Variant | Hypothesis | OB StdDev | OB Median "
                    + "| Banding | FracWithin256 | PRVW Pearson |\n");
            w.write("|---|---|---|---|---|---|---|---|---|\n");

            for (VariantResult r : results) {
                if (r.plane() != 0) continue;
                ObStats s = r.topOb().count() > 0 ? r.topOb() : r.leftOb();
                if (s.count() == 0) continue;
                String pearson = Double.isNaN(r.prvwPearson()) ? "-"
                        : String.format("%.4f", r.prvwPearson());
                w.write(String.format("| %s | %s | %s | %s | %.1f | %d | %.4f | %.3f | %s |%n",
                        r.fixture(), r.scene(), r.variant().id, r.variant().hypothesis,
                        s.stdDev(), s.median(), s.bandingScore(), s.fracWithin256(), pearson));
            }

            w.write("\n## Visual comparisons\n\n");
            // Group by fixture
            Set<String> fixtures = new LinkedHashSet<>();
            for (VariantResult r : results) fixtures.add(r.fixture());
            for (String fixture : fixtures) {
                w.write("### " + fixture + "\n\n");
                w.write("| Preview | Baseline | B1-row-offset | B1-north-only |\n");
                w.write("|---|---|---|---|\n");
                w.write(String.format("| ![preview](images/%s-preview.jpg) "
                        + "| ![baseline](images/%s-baseline.png) "
                        + "| ![b1-row-offset](images/%s-b1-row-offset.png) "
                        + "| ![b1-north-only](images/%s-b1-north-only.png) |%n",
                        fixture, fixture, fixture, fixture));
                w.write("\n");
                w.write("| B2-tile-continued | B2-tile-midscale | B3-plane-seeded |\n");
                w.write("|---|---|---|\n");
                w.write(String.format("| ![b2-tile-continued](images/%s-b2-tile-continued.png) "
                        + "| ![b2-tile-midscale](images/%s-b2-tile-midscale.png) "
                        + "| ![b3-plane-seeded](images/%s-b3-plane-seeded.png) |%n",
                        fixture, fixture, fixture));
                w.write("\n**Difference images (vs baseline, red=OB region):**\n\n");
                w.write("| B1-row-offset diff | B1-north-only diff | B2-tile-continued diff |\n");
                w.write("|---|---|---|\n");
                w.write(String.format("| ![](images/%s-b1-row-offset-diff.png) "
                        + "| ![](images/%s-b1-north-only-diff.png) "
                        + "| ![](images/%s-b2-tile-continued-diff.png) |%n",
                        fixture, fixture, fixture));
                w.write("\n---\n\n");
            }

            // Conclusion section
            w.write("## Conclusion\n\n");
            w.write("Results are generated from the existing fixture corpus. "
                    + "See the committed result document in `docs/cr3-results/` "
                    + "for the Phase-B hypothesis status updates.\n\n");
            w.write("**Decision rule**: a variant is considered SUPPORTED if it reduces "
                    + "OB stdDev by ≥10% AND reduces banding score by ≥0.05 compared to "
                    + "baseline, across ≥2 fixtures.\n");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Utility methods
    // ──────────────────────────────────────────────────────────────────────────

    private static void collectCr3(Path dir, List<Path> out) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isRegularFile)
             .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".cr3"))
             .forEach(out::add);
        }
    }

    private static int[] clampRect(int x0, int y0, int x1, int y1, int W, int H) {
        x0 = Math.max(0, Math.min(W, x0));
        y0 = Math.max(0, Math.min(H, y0));
        x1 = Math.max(x0, Math.min(W, x1));
        y1 = Math.max(y0, Math.min(H, y1));
        return new int[]{x0, y0, x1 - x0, y1 - y0};
    }

    private static String sceneTag(String name) {
        String n = name.toLowerCase();
        if (n.contains("-dark-") || n.startsWith("dark")) return "dark";
        if (n.contains("-flat-") || n.startsWith("flat")) return "flat";
        if (n.contains("-detail-"))                        return "detail";
        if (n.contains("-asis-"))                          return "asis";
        if (n.contains("sample"))                          return "sample";
        return "?";
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Data records
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * OB falsification metrics for one (plane, OB-region, variant) combination.
     */
    record ObStats(long count, int min, int max, int median, int mode,
                   double mean, double stdDev, double fracWithin256,
                   double rowProfileStd, double colProfileStd,
                   double[] autocorr, double bandingScore) {
        static final ObStats EMPTY = new ObStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                new double[AUTOCORR_LAGS.length], 0);
    }

    /**
     * One row in the results table.
     *
     * <p>Note: leftOb and topOb carry the OB stats for each region separately.
     * The {@link #plane()} field applies to both regions (same plane index).</p>
     */
    record VariantResult(
            String fixture,
            String scene,
            Variant variant,
            int plane,
            String leftObRegion,
            ObStats leftOb,
            int topObPlane,
            String topObRegion,
            ObStats topOb,
            double prvwPearson) {}
}
