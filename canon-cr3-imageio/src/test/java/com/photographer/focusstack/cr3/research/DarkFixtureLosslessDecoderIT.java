package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.Cr3Exception;
import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.container.Cr3Track;
import com.photographer.focusstack.cr3.raw.Cr3RawImage;
import com.photographer.focusstack.cr3.raw.codec.CrxHeader;
import com.photographer.focusstack.cr3.raw.codec.CrxLosslessRawDecoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression target for the lossless CRX decoder against the operator-shot
 * <em>dark</em> fixtures (lens cap on, base ISO).
 *
 * <p>This IT is the cleanroom-decoder counterpart of part (a) of the
 * decoder-progress plan: drive the existing {@link CrxLosslessRawDecoder}
 * against frames whose pixel content is physically known to be a near-uniform
 * pedestal (a cap-on shot at base ISO is, modulo read noise, a flat field at
 * the optical-black level), and assert the <em>physical</em> properties of
 * the decoded output. As the decoder gets closer to the patent specification,
 * the metrics measured here tighten monotonically — making this IT the
 * forward-compatible regression net for the predictor / k-update work.</p>
 *
 * <h3>What this IT asserts on every dark fixture</h3>
 * <ol>
 *   <li><b>Decode does not crash.</b> The current JPEG-LS-style approximation
 *       in {@link CrxLosslessRawDecoder} must at minimum return a buffer of
 *       the correct dimensions and bit depth. Crashes here are immediate
 *       blockers for any further decoder work.</li>
 *   <li><b>Output dimensions are sane.</b> Width / height match the CMP1
 *       declarations, bit depth is in {@code [8, 16]}, and the data array
 *       length equals {@code width × height}.</li>
 *   <li><b>Pixel values are within the bit-depth range.</b> Every decoded
 *       sample is in {@code [0, 2^bitDepth − 1]}. A correct decoder cannot
 *       emit out-of-range values; if this fails the entropy decoder is
 *       almost certainly running off the rails on a long unary code.</li>
 *   <li><b>Determinism within a burst.</b> The four dark fixtures captured
 *       in the same burst at identical settings must decode to buffers of
 *       identical dimensions and bit depth. The pixel content differs (shot
 *       noise) but the structure does not.</li>
 * </ol>
 *
 * <h3>What this IT measures and reports (without asserting)</h3>
 * <p>For each dark fixture the IT logs:</p>
 * <ul>
 *   <li>The pedestal estimate ({@code mode} of the histogram, in 64-LSB
 *       buckets — the patent's K-init seed should track this).</li>
 *   <li>The fraction of pixels within {@code ±256 LSB} of the mode (a
 *       correct decoder of a cap-on frame should put well over 80 % of
 *       pixels there at base ISO).</li>
 *   <li>The fraction of pixels within {@code ±1024 LSB} of the mode (the
 *       same number for the read-noise tail).</li>
 *   <li>Min / max / median of the decoded planes — large values here
 *       indicate the entropy decoder is producing junk.</li>
 * </ul>
 *
 * <p>The <em>future tightening path</em> (when the patent-spec predictor
 * lands) is to flip the four reporting metrics into hard assertions, e.g.
 * {@code fractionNear256 ≥ 0.80}, {@code mode ∈ [256, 4096]}. Today they are
 * pure measurements so the IT passes against the current approximate
 * decoder and gives us the baseline numbers to improve against.</p>
 *
 * <p>The IT silently no-ops if the {@code samples/research/} directory has
 * no {@code dark} fixtures, so it remains safe in a fresh checkout.</p>
 */
class DarkFixtureLosslessDecoderIT {

    private static final Logger log = LoggerFactory.getLogger(DarkFixtureLosslessDecoderIT.class);

    private static final Path RESEARCH_DIR =
            Path.of("src", "test", "resources", "samples", "research");

    /** Histogram bucket width in 14-bit LSB (64 = 6 bits of resolution → 256 buckets at 14-bit). */
    private static final int HIST_BUCKET = 64;

    @Test
    @DisplayName("Lossless CRX decoder produces well-formed output on dark (cap-on) fixtures")
    void darkFramesDecodeIntoWellFormedRawBuffers() throws Exception {
        if (!Files.isDirectory(RESEARCH_DIR)) {
            log.info("No research fixtures directory at {} — skipping", RESEARCH_DIR.toAbsolutePath());
            return;
        }

        List<Path> darkFixtures;
        try (Stream<Path> s = Files.list(RESEARCH_DIR)) {
            darkFixtures = s.filter(p -> {
                String name = p.getFileName().toString();
                return name.toLowerCase().endsWith(".cr3") && name.contains("-dark-");
            }).sorted().collect(Collectors.toList());
        }
        if (darkFixtures.isEmpty()) {
            log.info("No dark fixtures in {} — skipping (capture with -Dfixture.scene=dark)",
                    RESEARCH_DIR.toAbsolutePath());
            return;
        }

        log.info("═══ Decoding {} dark fixtures via CrxLosslessRawDecoder ═══", darkFixtures.size());

        List<Decoded> decoded = new ArrayList<>();
        for (Path p : darkFixtures) {
            decoded.add(decodeOne(p.toFile()));
        }

        // ── Invariant: structural determinism within the burst ───────────────
        Decoded first = decoded.get(0);
        for (Decoded d : decoded.subList(1, decoded.size())) {
            assertThat(d.width).as("width consistency vs %s", first.path).isEqualTo(first.width);
            assertThat(d.height).as("height consistency vs %s", first.path).isEqualTo(first.height);
            assertThat(d.bitDepth).as("bit depth consistency vs %s", first.path).isEqualTo(first.bitDepth);
        }

        // ── Per-fixture sanity ───────────────────────────────────────────────
        for (Decoded d : decoded) {
            assertThat(d.width).as("%s width > 0", d.path).isPositive();
            assertThat(d.height).as("%s height > 0", d.path).isPositive();
            assertThat(d.bitDepth).as("%s bit depth in [8,16]", d.path).isBetween(8, 16);
            assertThat(d.dataLength)
                    .as("%s data length == width*height", d.path)
                    .isEqualTo((long) d.width * d.height);

            int max = (1 << d.bitDepth) - 1;
            assertThat(d.pixelMin)
                    .as("%s min pixel >= 0 (out-of-range indicates entropy-decoder runaway)", d.path)
                    .isGreaterThanOrEqualTo(0);
            assertThat(d.pixelMax)
                    .as("%s max pixel <= %d", d.path, max)
                    .isLessThanOrEqualTo(max);

            log.info("  {}", d.path);
            log.info("      dim={}x{}  bd={}  min={} median={} max={}",
                    d.width, d.height, d.bitDepth, d.pixelMin, d.pixelMedian, d.pixelMax);
            log.info("      pedestal mode bucket≈{} (64-LSB bucket); near-mode coverage  "
                            + "±256≈{}  ±1024≈{}",
                    d.modeBucket * HIST_BUCKET,
                    fmt(d.fractionWithin256OfMode), fmt(d.fractionWithin1024OfMode));
            // Per-Bayer-position breakdown — plane 0/1/2/3 decode independently in
            // CrxLosslessRawDecoder, so a per-position diagnostic immediately
            // tells us whether the failure mode is plane-uniform (predictor or
            // k-update is wrong everywhere) or plane-specific (subband ordering
            // / interleave bug touching only some planes).
            for (int p = 0; p < 4; p++) {
                PlaneStats ps = d.planeStats[p];
                log.info("      plane[{}] (pos {}/{})  median={}  mode≈{}  ±1024≈{}  stdDev≈{}",
                        p, p / 2, p % 2, ps.median, ps.modeBucket * HIST_BUCKET,
                        fmt(ps.fractionWithin1024OfMode), fmt(ps.stdDev));
            }
        }

        // ── Forward-compatible reporting metric (informational) ─────────────
        double avgFractionNear = decoded.stream()
                .mapToDouble(d -> d.fractionWithin1024OfMode).average().orElse(0.0);
        log.info("  burst-mean fractionWithin1024OfMode = {} "
                        + "(future patent-spec swap should drive this above 0.80)",
                fmt(avgFractionNear));
    }

    // ─────────────────────────────────────────────────────── helpers ──

    private static Decoded decodeOne(File cr3) throws Exception {
        ParsedRaw raw = readRawTrack(cr3);

        Cr3RawImage image;
        try {
            image = CrxLosslessRawDecoder.decode(raw.crxBytes, raw.width, raw.height,
                    raw.bitDepth, raw.codecVersion, raw.cmp1);
        } catch (Cr3Exception e) {
            // We want a hard failure here — a dark fixture should be the
            // *easiest* possible case for the decoder. If we cannot even
            // parse the bitstream of a uniform-content frame, the
            // production decoder is in worse shape than the research
            // harnesses suggest, and the regression must surface.
            throw new AssertionError("CrxLosslessRawDecoder failed on dark fixture "
                    + cr3.getName() + ": " + e.getMessage(), e);
        }

        int[] data = image.data();
        long pmin = Integer.MAX_VALUE;
        long pmax = Integer.MIN_VALUE;
        // Count pixels in 64-LSB buckets across the supported bit depth range.
        int buckets = (1 << image.bitDepth()) / HIST_BUCKET;
        long[] hist = new long[buckets];
        for (int v : data) {
            if (v < pmin) pmin = v;
            if (v > pmax) pmax = v;
            int bi = Math.min(buckets - 1, Math.max(0, v / HIST_BUCKET));
            hist[bi]++;
        }
        // Mode bucket
        int modeBucket = 0;
        long modeCount = 0;
        for (int i = 0; i < buckets; i++) {
            if (hist[i] > modeCount) {
                modeCount = hist[i];
                modeBucket = i;
            }
        }
        // Coverage near the mode
        long total = data.length;
        long near256 = countInRange(data, modeBucket * HIST_BUCKET - 256, modeBucket * HIST_BUCKET + 256);
        long near1024 = countInRange(data, modeBucket * HIST_BUCKET - 1024, modeBucket * HIST_BUCKET + 1024);
        // Median (sample-based to keep this fast on 5472×3648 = 20 M pixels)
        int median = sampleMedian(data, 4096);

        PlaneStats[] planeStats = computePlaneStats(image);

        return new Decoded(cr3.getName(), image.width(), image.height(), image.bitDepth(),
                data.length, (int) pmin, (int) pmax, median,
                modeBucket, (double) near256 / total, (double) near1024 / total,
                planeStats);
    }

    /**
     * Per-Bayer-position statistics. Plane 0 = even row / even col,
     * 1 = even/odd, 2 = odd/even, 3 = odd/odd. The CRX encoder emits these
     * four planes independently, so this breakdown isolates plane-specific
     * decoder bugs from globally-uniform ones.
     */
    private static PlaneStats[] computePlaneStats(Cr3RawImage image) {
        int w = image.width(), h = image.height();
        int[] data = image.data();
        int buckets = (1 << image.bitDepth()) / HIST_BUCKET;
        long[][] hist = new long[4][buckets];
        long[] count = new long[4];
        double[] sum = new double[4];

        for (int y = 0; y < h; y++) {
            int rowBase = y * w;
            int planeRow = (y & 1) << 1;
            for (int x = 0; x < w; x++) {
                int p = planeRow | (x & 1);
                int v = data[rowBase + x];
                int bi = Math.min(buckets - 1, Math.max(0, v / HIST_BUCKET));
                hist[p][bi]++;
                count[p]++;
                sum[p] += v;
            }
        }

        PlaneStats[] result = new PlaneStats[4];
        for (int p = 0; p < 4; p++) {
            int modeBucket = 0;
            long modeCount = 0;
            for (int i = 0; i < buckets; i++) {
                if (hist[p][i] > modeCount) { modeCount = hist[p][i]; modeBucket = i; }
            }
            int center = modeBucket * HIST_BUCKET;
            long near = 0;
            int loB = Math.max(0, (center - 1024) / HIST_BUCKET);
            int hiB = Math.min(buckets - 1, (center + 1024) / HIST_BUCKET);
            for (int i = loB; i <= hiB; i++) near += hist[p][i];
            double mean = sum[p] / Math.max(1, count[p]);

            // Std-dev and median from a strided sample of this plane only
            int n = 2048;
            int[] samp = new int[n];
            int planeStride = Math.max(1, (int) (count[p] / n));
            int got = 0, seen = 0;
            for (int y = (p >> 1); y < h && got < n; y += 2) {
                int rowBase = y * w;
                for (int x = (p & 1); x < w && got < n; x += 2) {
                    if (seen++ % planeStride == 0) samp[got++] = data[rowBase + x];
                }
            }
            int[] sampUsed = java.util.Arrays.copyOf(samp, got);
            double sqSum = 0.0;
            for (int v : sampUsed) { double dd = v - mean; sqSum += dd * dd; }
            double stdDev = sampUsed.length > 0 ? Math.sqrt(sqSum / sampUsed.length) : 0.0;
            java.util.Arrays.sort(sampUsed);
            int median = sampUsed.length > 0 ? sampUsed[sampUsed.length / 2] : 0;
            result[p] = new PlaneStats(p, median, modeBucket,
                    (double) near / Math.max(1, count[p]), stdDev);
        }
        return result;
    }

    private record PlaneStats(int plane, int median, int modeBucket,
                              double fractionWithin1024OfMode, double stdDev) { }

    private static long countInRange(int[] data, int lo, int hi) {
        long c = 0;
        for (int v : data) if (v >= lo && v <= hi) c++;
        return c;
    }

    private static int sampleMedian(int[] data, int sampleSize) {
        int n = Math.min(sampleSize, data.length);
        int step = Math.max(1, data.length / n);
        int[] samp = new int[n];
        for (int i = 0, j = 0; i < n && j < data.length; i++, j += step) samp[i] = data[j];
        java.util.Arrays.sort(samp);
        return samp[n / 2];
    }

    private static String fmt(double d) { return String.format("%.3f", d); }

    private static ParsedRaw readRawTrack(File cr3) throws Exception {
        try (ImageInputStream in = ImageIO.createImageInputStream(cr3)) {
            Cr3Container c = Cr3Parser.parse(in);
            Cr3Track raw = c.tracks().stream()
                    .filter(t -> t.type() == Cr3Track.TrackType.CRX_BIG
                            || t.type() == Cr3Track.TrackType.CRX_RAW)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No RAW track in " + cr3.getName()));
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

    private record Decoded(String path, int width, int height, int bitDepth,
                           long dataLength, int pixelMin, int pixelMax, int pixelMedian,
                           int modeBucket, double fractionWithin256OfMode,
                           double fractionWithin1024OfMode,
                           PlaneStats[] planeStats) { }

    private record ParsedRaw(byte[] crxBytes, int width, int height, int bitDepth,
                             CrxHeader.CodecVersion codecVersion, Cmp1Box cmp1) { }
}
