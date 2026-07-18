package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.container.Cr3Track;
import com.photographer.focusstack.cr3.raw.codec.BitReader;
import com.photographer.focusstack.cr3.raw.codec.CrxHeader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Decoder-lock oracle harness driven entirely by the black/dark and flat
 * lossless CR3 fixtures in {@code samples/research/}.
 *
 * <h3>Why this IT exists</h3>
 * <p>The production {@link com.photographer.focusstack.cr3.raw.codec.CrxLosslessRawDecoder}
 * masks several diagnostic signals the cleanroom research needs:</p>
 * <ul>
 *   <li>When the per-plane bitstream is exhausted mid-plane the decoder
 *       silently pads the remaining pixels with the MED prediction. The
 *       reconstructed image then looks plausible to a histogram even though
 *       the entropy decoder failed long before the end of the plane.</li>
 *   <li>When a subband's declared byte length runs past the CRX payload
 *       the decoder clamps and emits one DEBUG line. The number of bytes
 *       it actually consumed before clamping is never reported.</li>
 *   <li>The "all planes saturated at 16320" signature reported by
 *       {@code CrxKUpdateRuleBakeoffIT} can be either entropy-lock-loss
 *       (decoder produced garbage and {@code Math.min} clamped it to
 *       {@code maxVal}) or rail saturation from a legitimately bright
 *       residual sequence. Without per-plane bit-consumption and
 *       first-exhaustion coordinates the two are indistinguishable.</li>
 * </ul>
 *
 * <p>This IT therefore runs a <em>diagnostic copy</em> of the production
 * entropy decoder inline. It is byte-for-byte faithful to the production
 * algorithm (same first-pixel seed = 0, same MED predictor, same JPEG-LS
 * K-update, same zig-zag mapping) so the numbers it reports are exactly
 * what the production decoder would consume — it just doesn't throw the
 * intermediate state away.</p>
 *
 * <h3>What the report contains, per (file, tile, plane)</h3>
 * <ul>
 *   <li>{@code declaredBytes} — subband length from the CRX header.</li>
 *   <li>{@code consumedBytes} — bytes the entropy decoder actually
 *       consumed before either finishing the plane or running out.</li>
 *   <li>{@code firstExhaustX / firstExhaustY} — pixel coordinate at which
 *       the bitstream first ran out, or {@code -1} if the plane finished
 *       cleanly.</li>
 *   <li>{@code railFraction} — fraction of pixels saturating at the
 *       {@code (1 &lt;&lt; bitDepth) - 1} rail. Black frames must have
 *       this near zero on a physically correct decoder.</li>
 *   <li>{@code modeBucket64 / medianLsb} — central-tendency statistics
 *       per plane, same buckets used by {@code DarkFixtureLosslessDecoderIT}
 *       so the existing entropy-lock-loss signature is directly comparable.</li>
 *   <li>{@code finalK} — Rice-Golomb K parameter at the end of the plane;
 *       a value pinned at {@code bitDepth} is a strong indicator of
 *       run-away adaptation.</li>
 * </ul>
 *
 * <p>Hard assertions are deliberately limited to structural invariants
 * (parses, dimensions, non-negative counters) so the IT stays green while
 * research is in flight; the value is in the TSV + log output. Output goes
 * to {@code target/dark-frame-lock-oracle/lock.tsv}.</p>
 *
 * <p>C-RAW (lossy, {@code waveletLevels &gt; 0}) fixtures are tagged but
 * not decoded — the LL-only diagnostic decoder cannot interpret a wavelet
 * subband. They show up as a single {@code CRAW_NOT_DECODED} row each so
 * the absence is visible.</p>
 */
class DarkFrameDecoderLockOracleIT {

    private static final Logger log =
            LoggerFactory.getLogger(DarkFrameDecoderLockOracleIT.class);

    private static final Path RESEARCH_DIR =
            Path.of("src", "test", "resources", "samples", "research");
    private static final Path REPORT_DIR =
            Path.of("target", "dark-frame-lock-oracle");

    private static final int HIST_BUCKET = 64;
    private static final int MAX_RICE_UNARY_COUNT = 65536;
    private static final int NORMALIZATION_THRESHOLD = 1024;

    @Test
    @DisplayName("Per-plane entropy-decoder lock oracle on dark + flat CR3 fixtures")
    void emitLockOracle() throws Exception {
        if (!Files.isDirectory(RESEARCH_DIR)) {
            log.info("No research fixtures at {} — skipping",
                    RESEARCH_DIR.toAbsolutePath());
            return;
        }

        List<Path> fixtures;
        try (Stream<Path> s = Files.list(RESEARCH_DIR)) {
            fixtures = s.filter(p -> {
                String n = p.getFileName().toString().toLowerCase();
                return n.endsWith(".cr3")
                        && (n.contains("-dark-") || n.contains("-flat-")
                            || n.startsWith("dark-"));
            }).sorted().collect(Collectors.toList());
        }
        if (fixtures.isEmpty()) {
            log.info("No dark/flat fixtures in {} — skipping oracle",
                    RESEARCH_DIR.toAbsolutePath());
            return;
        }

        Files.createDirectories(REPORT_DIR);
        Path tsv = REPORT_DIR.resolve("lock.tsv");

        int losslessCount = 0;
        int planesClean = 0;
        int planesExhausted = 0;
        int planesRailDominated = 0;

        try (var out = Files.newBufferedWriter(tsv)) {
            out.write("file\tscene\tcompression\ttile\tplane\tplaneW\tplaneH\t"
                    + "declaredBytes\tconsumedBytes\tleftoverBytes\t"
                    + "firstExhaustX\tfirstExhaustY\tfinalK\t"
                    + "modeBucket64\tmedianLsb\trailFraction\tverdict\n");

            log.info("═══ Dark-frame decoder-lock oracle across {} fixtures ═══",
                    fixtures.size());

            for (Path file : fixtures) {
                ParsedRaw parsed;
                try {
                    parsed = readRawTrack(file);
                } catch (Exception e) {
                    log.warn("  ↷ {} unreadable as CR3 — {}", file.getFileName(), e.toString());
                    continue;
                }
                String scene = sceneTag(file.getFileName().toString());

                if (parsed.cmp1 != null && parsed.cmp1.waveletLevels() > 0) {
                    log.info("  ↷ {} is C-RAW (waveletLevels={}) — out of scope, tagging only",
                            file.getFileName(), parsed.cmp1.waveletLevels());
                    out.write(String.format(
                            "%s\t%s\tCRAW\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\t-\tCRAW_NOT_DECODED%n",
                            file.getFileName(), scene));
                    continue;
                }

                CrxHeader header;
                byte[] crxBytes;
                int stripped;
                try {
                    Parsed ph = parseHeader(parsed.crxBytes, parsed.codecVersion,
                            parsed.cmp1);
                    header = ph.header;
                    crxBytes = ph.data;
                    stripped = ph.stripped;
                } catch (IOException e) {
                    log.warn("  ↷ {} CRX header parse failed: {}", file.getFileName(),
                            e.toString());
                    continue;
                }

                losslessCount++;
                int planeWidthFull = (parsed.width + 1) / 2;
                int planeHeightFull = (parsed.height + 1) / 2;
                int numTiles = header.tiles().size();
                int tilePlaneWidth = resolveTilePlaneWidth(parsed.cmp1, numTiles,
                        planeWidthFull, parsed.width);
                int tilePlaneHeight = resolveTilePlaneHeight(parsed.cmp1, planeHeightFull);

                log.info("  {} ({}x{}, bd={}, tiles={}, mdatHdrStripped={})",
                        file.getFileName(), parsed.width, parsed.height, parsed.bitDepth,
                        numTiles, stripped);

                List<CrxHeader.Tile> tiles = new ArrayList<>(header.tiles());
                tiles.sort((a, b) -> Integer.compare(a.index(), b.index()));
                int tileXOffset = 0;
                for (int ti = 0; ti < tiles.size(); ti++) {
                    CrxHeader.Tile tile = tiles.get(ti);
                    int remaining = planeWidthFull - tileXOffset;
                    int tilePixelWidth = (ti == tiles.size() - 1)
                            ? remaining
                            : Math.min(tilePlaneWidth, remaining);

                    for (CrxHeader.Plane plane : tile.planes()) {
                        if (plane.subbands().isEmpty()) {
                            continue;
                        }
                        CrxHeader.Subband sub = plane.subbands().getFirst();
                        long off = sub.dataOffset();
                        long sz = sub.dataSize();
                        long end = off + sz;
                        if (off > Integer.MAX_VALUE || sz > Integer.MAX_VALUE
                                || end > Integer.MAX_VALUE || end < off) {
                            log.warn("    tile={} plane={} subband range overflow",
                                    tile.index(), plane.index());
                            continue;
                        }
                        int subOff = (int) off;
                        int subEnd = (int) Math.min(end, crxBytes.length);
                        if (subEnd <= subOff) {
                            continue;
                        }

                        PlaneReport rep = decodePlaneProbe(crxBytes, subOff, subEnd,
                                tilePixelWidth, tilePlaneHeight, parsed.bitDepth);

                        long declared = sz;
                        long consumed = rep.consumedBytes;
                        long leftover = declared - consumed;
                        String verdict = verdict(rep, parsed.bitDepth);
                        if ("EXHAUSTED".equals(verdict)) planesExhausted++;
                        else if ("RAIL_DOMINATED".equals(verdict)) planesRailDominated++;
                        else if ("CLEAN".equals(verdict)) planesClean++;

                        out.write(String.format(
                                "%s\t%s\tLOSSLESS\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%.4f\t%s%n",
                                file.getFileName(), scene, tile.index(), plane.index(),
                                tilePixelWidth, tilePlaneHeight,
                                declared, consumed, leftover,
                                rep.firstExhaustX, rep.firstExhaustY, rep.finalK,
                                rep.modeBucket * HIST_BUCKET, rep.median,
                                rep.railFraction, verdict));
                        log.info(
                            "    tile={} plane={} declared={}B consumed={}B leftover={}B "
                                + "exhaustAt=({},{}) finalK={} mode≈{} median={} rail={}% → {}",
                                tile.index(), plane.index(), declared, consumed, leftover,
                                rep.firstExhaustX, rep.firstExhaustY, rep.finalK,
                                rep.modeBucket * HIST_BUCKET, rep.median,
                                String.format("%.2f", 100.0 * rep.railFraction), verdict);

                        // Structural sanity — keep the IT useful as a regression net.
                        assertThat((long) rep.consumedBytes).isGreaterThanOrEqualTo(0L);
                        assertThat((long) rep.consumedBytes)
                                .as("plane never consumes more than declared+1 byte")
                                .isLessThanOrEqualTo(declared + 1);
                    }
                    tileXOffset += tilePixelWidth;
                }
            }
        }

        log.info("═══ Oracle summary ═══");
        log.info("    lossless fixtures probed = {}", losslessCount);
        log.info("    planes CLEAN             = {}", planesClean);
        log.info("    planes EXHAUSTED         = {}", planesExhausted);
        log.info("    planes RAIL_DOMINATED    = {}", planesRailDominated);
        log.info("    TSV → {}", tsv.toAbsolutePath());
    }

    // ──────────────────────────── probe decoder ────────────────────────────

    /**
     * Diagnostic copy of {@code CrxLosslessRawDecoder.decodePlane} that
     * additionally records consumed-byte count, first-exhaustion coordinate,
     * final K and rail saturation. Deliberately byte-for-byte faithful to
     * the production decoder so its measurements describe production state
     * without modifying production code.
     */
    private static PlaneReport decodePlaneProbe(byte[] data, int offset, int endOffset,
                                                int width, int height, int bitDepth)
            throws IOException {
        int maxVal = (1 << bitDepth) - 1;
        BitReader reader = new BitReader(data, offset, endOffset - offset);

        int k = 0;
        long accumulator = 0;
        long sampleCount = 1;
        boolean exhausted = false;
        int firstExhaustX = -1;
        int firstExhaustY = -1;
        int bytesAtExhaustion = -1;
        int[] result = new int[width * height];
        long rail = 0;
        int buckets = (1 << bitDepth) / HIST_BUCKET;
        long[] hist = new long[buckets];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int predicted;
                if (x == 0 && y == 0) {
                    predicted = 0;
                } else if (y == 0) {
                    predicted = result[x - 1];
                } else if (x == 0) {
                    predicted = result[(y - 1) * width];
                } else {
                    int a = result[y * width + (x - 1)];
                    int b = result[(y - 1) * width + x];
                    int c = result[(y - 1) * width + (x - 1)];
                    predicted = medPredict(a, b, c);
                }

                int residual;
                if (exhausted || !reader.hasMore()) {
                    if (!exhausted) {
                        exhausted = true;
                        firstExhaustX = x;
                        firstExhaustY = y;
                        bytesAtExhaustion = reader.bytePosition();
                    }
                    residual = 0;
                } else {
                    try {
                        residual = decodeRiceGolomb(reader, k);
                    } catch (IOException e) {
                        exhausted = true;
                        firstExhaustX = x;
                        firstExhaustY = y;
                        bytesAtExhaustion = reader.bytePosition();
                        residual = 0;
                    }
                }

                int signedResidual = ((residual & 1) == 0)
                        ? (residual >> 1)
                        : (-(residual >> 1) - 1);
                int pixel = predicted + signedResidual;
                pixel = Math.max(0, Math.min(maxVal, pixel));
                result[y * width + x] = pixel;
                if (pixel == maxVal) rail++;
                hist[Math.min(buckets - 1, Math.max(0, pixel / HIST_BUCKET))]++;

                // JPEG-LS K update (same as production).
                int absResidual = Math.abs(signedResidual);
                accumulator += absResidual;
                sampleCount++;
                long threshold = sampleCount << k;
                if (accumulator > threshold && k < bitDepth) {
                    k++;
                } else if (k > 0) {
                    long lowerThreshold = sampleCount << (k - 1);
                    if (accumulator <= lowerThreshold) {
                        k--;
                    }
                }
                if (sampleCount >= NORMALIZATION_THRESHOLD) {
                    accumulator >>= 1;
                    sampleCount >>= 1;
                    if (sampleCount == 0) sampleCount = 1;
                }
            }
        }

        int modeBucket = 0;
        long modeCount = 0;
        for (int i = 0; i < buckets; i++) {
            if (hist[i] > modeCount) { modeCount = hist[i]; modeBucket = i; }
        }
        int median = approxMedian(result);

        int consumed = exhausted ? bytesAtExhaustion : reader.bytePosition();
        return new PlaneReport(consumed, firstExhaustX, firstExhaustY, k,
                modeBucket, median, (double) rail / result.length);
    }

    private static int medPredict(int a, int b, int c) {
        if (c >= Math.max(a, b)) return Math.min(a, b);
        if (c <= Math.min(a, b)) return Math.max(a, b);
        return a + b - c;
    }

    private static int decodeRiceGolomb(BitReader reader, int k) throws IOException {
        int q = 0;
        while (true) {
            if (!reader.hasMore()) {
                throw new IOException("Unexpected end of stream while reading unary code");
            }
            if (reader.readBit() != 0) break;
            q++;
            if (q > MAX_RICE_UNARY_COUNT) {
                throw new IOException("Rice-Golomb unary overflow");
            }
        }
        int r = 0;
        if (k > 0) {
            if (!reader.hasMore()) {
                throw new IOException("Unexpected end of stream while reading remainder");
            }
            r = reader.readBits(k);
        }
        return (q << k) | r;
    }

    private static int approxMedian(int[] data) {
        if (data.length == 0) return 0;
        int n = Math.min(4096, data.length);
        int step = Math.max(1, data.length / n);
        int[] sample = new int[(data.length + step - 1) / step];
        int gi = 0;
        for (int i = 0; i < data.length && gi < sample.length; i += step) {
            sample[gi++] = data[i];
        }
        int[] used = Arrays.copyOf(sample, gi);
        Arrays.sort(used);
        return used[used.length / 2];
    }

    private static String verdict(PlaneReport rep, int bitDepth) {
        int maxVal = (1 << bitDepth) - 1;
        int railCenter = (maxVal / HIST_BUCKET) * HIST_BUCKET;
        boolean nearRail = rep.modeBucket * HIST_BUCKET >= railCenter - HIST_BUCKET;
        if (rep.firstExhaustX >= 0 || rep.firstExhaustY >= 0) {
            return "EXHAUSTED";
        }
        if (nearRail || rep.railFraction > 0.5) {
            return "RAIL_DOMINATED";
        }
        return "CLEAN";
    }

    // ──────────────────────────── plumbing ────────────────────────────

    /** Mirrors {@code CrxLosslessRawDecoder.parseCrxData}. */
    private static Parsed parseHeader(byte[] data, CrxHeader.CodecVersion version,
                                      Cmp1Box cmp1) throws IOException {
        try {
            CrxHeader header = CrxHeader.parse(data, version);
            if (!header.tiles().isEmpty()) {
                return new Parsed(data, header, 0);
            }
            int hs = cmp1 == null ? 0 : cmp1.mdatHeaderSize();
            if (hs > 0 && hs < data.length) {
                byte[] stripped = Arrays.copyOfRange(data, hs, data.length);
                return new Parsed(stripped, CrxHeader.parse(stripped, version), hs);
            }
            return new Parsed(data, header, 0);
        } catch (IOException first) {
            int hs = cmp1 == null ? 0 : cmp1.mdatHeaderSize();
            if (hs > 0 && hs < data.length) {
                byte[] stripped = Arrays.copyOfRange(data, hs, data.length);
                return new Parsed(stripped, CrxHeader.parse(stripped, version), hs);
            }
            throw first;
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

    private static String sceneTag(String n) {
        String s = n.toLowerCase();
        if (s.contains("-dark-") || s.startsWith("dark-")) return "dark";
        if (s.contains("-flat-")) return "flat";
        return "?";
    }

    private static ParsedRaw readRawTrack(Path file) throws Exception {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            Cr3Container c = Cr3Parser.parse(in);
            Cr3Track raw = c.tracks().stream()
                    .filter(t -> t.type() == Cr3Track.TrackType.CRX_BIG
                            || t.type() == Cr3Track.TrackType.CRX_RAW)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "No RAW track in " + file.getFileName()));
            CrxHeader.CodecVersion ver = c.cncvBox() == null
                    ? CrxHeader.CodecVersion.V1
                    : CrxHeader.detectVersion(c.cncvBox().versionString());
            Cmp1Box cmp1 = raw.cmp1();
            int w = (cmp1 != null && cmp1.imageWidth() > 0) ? cmp1.imageWidth() : raw.width();
            int h = (cmp1 != null && cmp1.imageHeight() > 0) ? cmp1.imageHeight() : raw.height();
            int bd = (cmp1 != null && cmp1.bitsPerSample() > 0) ? cmp1.bitsPerSample() : 14;
            byte[] bytes = new byte[(int) raw.dataSize()];
            in.seek(raw.dataOffset());
            in.readFully(bytes);
            return new ParsedRaw(bytes, w, h, bd, ver, cmp1);
        }
    }

    private record PlaneReport(int consumedBytes, int firstExhaustX, int firstExhaustY,
                               int finalK, int modeBucket, int median, double railFraction) { }

    private record Parsed(byte[] data, CrxHeader header, int stripped) { }

    private record ParsedRaw(byte[] crxBytes, int width, int height, int bitDepth,
                             CrxHeader.CodecVersion codecVersion, Cmp1Box cmp1) { }

    @SuppressWarnings("unused")
    private static final List<String> UNUSED_TAGS = Collections.emptyList();
}
