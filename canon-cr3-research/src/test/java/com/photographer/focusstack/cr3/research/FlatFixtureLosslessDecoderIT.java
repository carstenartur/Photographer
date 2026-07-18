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
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lossless decoder regression target for the operator-shot <em>flat</em>
 * (defocused, near-uniform white) fixtures.
 *
 * <h3>Background</h3>
 * <p>The original plan was to use the flat fixtures for C-RAW (lossy) IDWT
 * bring-up, but empirical inspection of the captured CR3s reveals the
 * G5 X Mark II actually emits <em>lossless</em> CRX at ISO 1600 with the
 * standard RAW format setting (CMP1.{@code waveletLevels} = 0, {@code isLossless}
 * = {@code true}). The files are small (~1 MB instead of ~18 MB) only
 * because the near-uniform scene compresses extraordinarily well under
 * MED + Rice. Documented in {@code samples/research/README.md} update
 * 2026-05-04.</p>
 *
 * <p>That makes the flat fixtures an even <em>better</em> test target for the
 * lossless decoder than the dark fixtures: a cap-on shot at base ISO is
 * dominated by Gaussian read noise around a low pedestal, while a
 * defocused-white-wall shot is dominated by signal at a single mid-grey
 * value with very little high-frequency content. The reconstructed image
 * should therefore have a <em>tighter</em> unimodal histogram than the dark
 * one, and (when the patent-spec predictor lands) the per-channel standard
 * deviation should drop into the read-noise floor.</p>
 *
 * <h3>What this IT asserts on every flat fixture</h3>
 * <ol>
 *   <li><b>Decode does not crash.</b></li>
 *   <li><b>Output dimensions match CMP1.</b></li>
 *   <li><b>Pixel values are within the bit-depth range.</b></li>
 *   <li><b>Structural determinism within the burst.</b></li>
 *   <li><b>Better-than-dark concentration metric.</b> Because the scene is
 *       uniform, the histogram peak should be sharper than for the dark
 *       fixtures. We do not assert this against the dark IT directly today
 *       (the production decoder is too noisy on both), but the metric is
 *       reported and the assertion is staged as a TODO for after the
 *       patent-spec predictor lands.</li>
 * </ol>
 *
 * <h3>Forward-compatible upgrades (after the patent-spec swap)</h3>
 * <ul>
 *   <li>Assert {@code fractionWithin256OfMode ≥ 0.50} on every flat
 *       fixture (today's measurement is well below that — see the IT
 *       output below).</li>
 *   <li>Assert {@code stdDev ≤ 0.05 × mode} on every flat fixture (a
 *       physically motivated bound: read noise at ISO 1600 on the G5X II
 *       is roughly 5 % of mid-grey).</li>
 *   <li>Cross-fixture: assert that the flat-fixture concentration metric
 *       is at least as tight as the dark-fixture one.</li>
 * </ul>
 *
 * <p>Skipped silently if no flat fixtures are present.</p>
 */
class FlatFixtureLosslessDecoderIT {

    private static final Logger log = LoggerFactory.getLogger(FlatFixtureLosslessDecoderIT.class);

    private static final Path RESEARCH_DIR =
            Path.of("src", "test", "resources", "samples", "research");

    /** Histogram bucket width in LSB (64 → 256 buckets at 14-bit). */
    private static final int HIST_BUCKET = 64;

    @Test
    @DisplayName("Lossless CRX decoder produces well-formed output on flat (defocused-white) fixtures")
    void flatFramesDecodeIntoWellFormedRawBuffers() throws Exception {
        if (!Files.isDirectory(RESEARCH_DIR)) {
            log.info("No research fixtures directory at {} — skipping", RESEARCH_DIR.toAbsolutePath());
            return;
        }

        List<Path> flatFixtures;
        try (Stream<Path> s = Files.list(RESEARCH_DIR)) {
            flatFixtures = s.filter(p -> {
                String name = p.getFileName().toString();
                return name.toLowerCase().endsWith(".cr3") && name.contains("-flat-");
            }).sorted().collect(Collectors.toList());
        }
        if (flatFixtures.isEmpty()) {
            log.info("No flat fixtures in {} — skipping (capture with -Dfixture.scene=flat)",
                    RESEARCH_DIR.toAbsolutePath());
            return;
        }

        log.info("═══ Decoding {} flat fixtures via CrxLosslessRawDecoder ═══", flatFixtures.size());

        List<Decoded> decoded = new ArrayList<>();
        int skippedLossy = 0;
        for (Path p : flatFixtures) {
            // C-RAW (lossy CRX, waveletLevels > 0) cannot be processed by the
            // lossless decoder by design — see CrawLossyBringUpIT instead.
            if (isCrawLossy(p.toFile())) {
                log.info("  ↷ skipping C-RAW (lossy) flat fixture {}", p.getFileName());
                skippedLossy++;
                continue;
            }
            decoded.add(decodeOne(p.toFile()));
        }
        if (decoded.isEmpty()) {
            log.info("All {} flat fixtures are C-RAW (lossy) — nothing for the lossless decoder; "
                    + "see CrawLossyBringUpIT for the C-RAW research path", skippedLossy);
            return;
        }

        // Structural determinism within the burst
        Decoded first = decoded.get(0);
        for (Decoded d : decoded.subList(1, decoded.size())) {
            assertThat(d.width).as("width vs %s", first.path).isEqualTo(first.width);
            assertThat(d.height).as("height vs %s", first.path).isEqualTo(first.height);
            assertThat(d.bitDepth).as("bit depth vs %s", first.path).isEqualTo(first.bitDepth);
        }

        // Per-fixture sanity
        for (Decoded d : decoded) {
            assertThat(d.width).as("%s width > 0", d.path).isPositive();
            assertThat(d.height).as("%s height > 0", d.path).isPositive();
            assertThat(d.bitDepth).as("%s bit depth in [8,16]", d.path).isBetween(8, 16);
            assertThat(d.dataLength)
                    .as("%s data length == width*height", d.path)
                    .isEqualTo((long) d.width * d.height);

            int max = (1 << d.bitDepth) - 1;
            assertThat(d.pixelMin).as("%s min pixel >= 0", d.path).isGreaterThanOrEqualTo(0);
            assertThat(d.pixelMax).as("%s max pixel <= %d", d.path, max).isLessThanOrEqualTo(max);

            log.info("  {}", d.path);
            log.info("      dim={}x{}  bd={}  min={} median={} max={}",
                    d.width, d.height, d.bitDepth, d.pixelMin, d.pixelMedian, d.pixelMax);
            log.info("      mode bucket≈{}  stdDev≈{}  near-mode coverage  ±256≈{}  ±1024≈{}",
                    d.modeBucket * HIST_BUCKET, fmt(d.stdDev),
                    fmt(d.fractionWithin256OfMode), fmt(d.fractionWithin1024OfMode));
            for (int p = 0; p < 4; p++) {
                PlaneStats ps = d.planeStats[p];
                log.info("      plane[{}] (pos {}/{})  median={}  mode≈{}  ±1024≈{}  stdDev≈{}",
                        p, p / 2, p % 2, ps.median, ps.modeBucket * HIST_BUCKET,
                        fmt(ps.fractionWithin1024OfMode), fmt(ps.stdDev));
            }
        }

        double avgFractionNear = decoded.stream()
                .mapToDouble(d -> d.fractionWithin1024OfMode).average().orElse(0.0);
        double avgStdDev = decoded.stream().mapToDouble(d -> d.stdDev).average().orElse(0.0);
        log.info("  burst-mean fractionWithin1024OfMode = {} (target ≥ 0.80 post patent-spec)",
                fmt(avgFractionNear));
        log.info("  burst-mean stdDev = {} LSB (target ≤ 0.05 × mode after patent-spec)",
                fmt(avgStdDev));
    }

    // ─────────────────────────────────────────────────────── helpers ──

    private static Decoded decodeOne(File cr3) throws Exception {
        ParsedRaw raw = readRawTrack(cr3);
        Cr3RawImage image;
        try {
            image = CrxLosslessRawDecoder.decode(raw.crxBytes, raw.width, raw.height,
                    raw.bitDepth, raw.codecVersion, raw.cmp1);
        } catch (Cr3Exception e) {
            throw new AssertionError("CrxLosslessRawDecoder failed on flat fixture "
                    + cr3.getName() + ": " + e.getMessage(), e);
        }

        int[] data = image.data();
        long pmin = Integer.MAX_VALUE, pmax = Integer.MIN_VALUE;
        int buckets = (1 << image.bitDepth()) / HIST_BUCKET;
        long[] hist = new long[buckets];
        double sum = 0.0;
        for (int v : data) {
            if (v < pmin) pmin = v;
            if (v > pmax) pmax = v;
            sum += v;
            int bi = Math.min(buckets - 1, Math.max(0, v / HIST_BUCKET));
            hist[bi]++;
        }
        double mean = sum / data.length;
        double sqSum = 0.0;
        for (int v : data) { double d = v - mean; sqSum += d * d; }
        double stdDev = Math.sqrt(sqSum / data.length);

        int modeBucket = 0;
        long modeCount = 0;
        for (int i = 0; i < buckets; i++) {
            if (hist[i] > modeCount) { modeCount = hist[i]; modeBucket = i; }
        }
        long total = data.length;
        long near256 = countInRange(data, modeBucket * HIST_BUCKET - 256, modeBucket * HIST_BUCKET + 256);
        long near1024 = countInRange(data, modeBucket * HIST_BUCKET - 1024, modeBucket * HIST_BUCKET + 1024);
        int median = sampleMedian(data, 4096);

        return new Decoded(cr3.getName(), image.width(), image.height(), image.bitDepth(),
                data.length, (int) pmin, (int) pmax, median,
                modeBucket, (double) near256 / total, (double) near1024 / total, stdDev,
                computePlaneStats(image));
    }

    /** Per-Bayer-position stats: see {@link DarkFixtureLosslessDecoderIT} for rationale. */
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
            int[] sampUsed = Arrays.copyOf(samp, got);
            double sqSum = 0.0;
            for (int v : sampUsed) { double dd = v - mean; sqSum += dd * dd; }
            double stdDev = sampUsed.length > 0 ? Math.sqrt(sqSum / sampUsed.length) : 0.0;
            Arrays.sort(sampUsed);
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
        Arrays.sort(samp);
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

    /**
     * Cheap CMP1 probe: returns {@code true} when the CR3 file's RAW track
     * declares {@code waveletLevels > 0}, i.e. C-RAW / lossy CRX. Falls back
     * to {@code false} on any parse error so the caller still tries the
     * decoder and surfaces the real failure cause.
     */
    private static boolean isCrawLossy(File cr3) {
        try (ImageInputStream in = ImageIO.createImageInputStream(cr3)) {
            Cr3Container c = Cr3Parser.parse(in);
            return c.tracks().stream()
                    .filter(t -> t.type() == Cr3Track.TrackType.CRX_BIG
                            || t.type() == Cr3Track.TrackType.CRX_RAW)
                    .findFirst()
                    .map(Cr3Track::cmp1)
                    .map(cmp1 -> cmp1 != null && cmp1.waveletLevels() > 0)
                    .orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    private record Decoded(String path, int width, int height, int bitDepth,
                           long dataLength, int pixelMin, int pixelMax, int pixelMedian,
                           int modeBucket, double fractionWithin256OfMode,
                           double fractionWithin1024OfMode, double stdDev,
                           PlaneStats[] planeStats) { }

    private record ParsedRaw(byte[] crxBytes, int width, int height, int bitDepth,
                             CrxHeader.CodecVersion codecVersion, Cmp1Box cmp1) { }
}