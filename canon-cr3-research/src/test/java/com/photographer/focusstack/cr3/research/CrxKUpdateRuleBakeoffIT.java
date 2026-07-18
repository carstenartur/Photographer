package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.container.Cr3Track;
import com.photographer.focusstack.cr3.raw.Cr3RawImage;
import com.photographer.focusstack.cr3.raw.codec.CrxHeader;
import com.photographer.focusstack.cr3.raw.codec.CrxLosslessRawDecoder;
import com.photographer.focusstack.cr3.raw.codec.CrxLosslessRawDecoder.KUpdateRule;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Side‑by‑side bake‑off of {@link KUpdateRule#JPEG_LS} vs.
 * {@link KUpdateRule#PATENT_BRACKET} on the operator‑shot dark + flat
 * regression panel.
 *
 * <p>{@link KUpdateRule#PATENT_BRACKET} is now the production default; this IT
 * keeps the legacy {@link KUpdateRule#JPEG_LS} path measurable so future CRX
 * entropy changes can still prove they do not regress the dark/flat panel.</p>
 *
 * <ul>
 *   <li>Per‑fixture, per‑rule {@code fractionWithin1024OfMode} — the burst‑wide
 *       quality metric the existing {@code DarkFixtureLosslessDecoderIT}
 *       already tracks.</li>
 *   <li>Per‑plane mode bucket so the 16320‑saturation signature of the
 *       entropy‑lock‑loss bug stays visible.</li>
 *   <li>A burst‑mean delta {@code PATENT_BRACKET − JPEG_LS} so future entropy
 *       changes can be compared against the legacy failure mode on hard
 *       evidence rather than hand‑waving.</li>
 * </ul>
 *
 * <p>The IT asserts only the structural invariants (both rules return
 * buffers of identical shape). Dark-frame physical quality is gated by
 * {@code DarkFixtureLosslessDecoderIT}; this bake‑off remains a reporting
 * harness for A/B deltas.</p>
 *
 * <p>Silently no‑ops when neither dark nor flat fixtures are present (fresh
 * checkout without operator captures).</p>
 */
class CrxKUpdateRuleBakeoffIT {

    private static final Logger log = LoggerFactory.getLogger(CrxKUpdateRuleBakeoffIT.class);

    private static final Path RESEARCH_DIR =
            Path.of("src", "test", "resources", "samples", "research");

    /** Histogram bucket width in 14‑bit LSB (mirrors DarkFixtureLosslessDecoderIT). */
    private static final int HIST_BUCKET = 64;

    @Test
    @DisplayName("PATENT_BRACKET vs JPEG_LS K‑update rule on dark + flat lossless fixtures")
    void bakeoff() throws Exception {
        if (!Files.isDirectory(RESEARCH_DIR)) {
            log.info("No research fixtures at {} — skipping bake‑off",
                    RESEARCH_DIR.toAbsolutePath());
            return;
        }

        List<Path> fixtures;
        try (Stream<Path> s = Files.list(RESEARCH_DIR)) {
            fixtures = s.filter(p -> {
                String name = p.getFileName().toString().toLowerCase();
                return name.endsWith(".cr3")
                        && (name.contains("-dark-") || name.contains("-flat-"));
            }).sorted().collect(Collectors.toList());
        }
        if (fixtures.isEmpty()) {
            log.info("No dark/flat fixtures in {} — skipping bake‑off",
                    RESEARCH_DIR.toAbsolutePath());
            return;
        }

        log.info("═══ K‑update rule bake‑off across {} fixtures ═══", fixtures.size());

        Map<KUpdateRule, List<Double>> burstMeans = new EnumMap<>(KUpdateRule.class);
        burstMeans.put(KUpdateRule.JPEG_LS, new ArrayList<>());
        burstMeans.put(KUpdateRule.PATENT_BRACKET, new ArrayList<>());

        for (Path p : fixtures) {
            ParsedRaw raw = readRawTrack(p.toFile());
            if (raw.cmp1 != null && raw.cmp1.waveletLevels() > 0) {
                log.info("  ↷ {} is C‑RAW (lossy) — skipping (out of scope for lossless bake‑off)",
                        p.getFileName());
                continue;
            }

            Map<KUpdateRule, Result> perRule = new EnumMap<>(KUpdateRule.class);
            for (KUpdateRule rule : KUpdateRule.values()) {
                Cr3RawImage img = CrxLosslessRawDecoder.decode(raw.crxBytes, raw.width,
                        raw.height, raw.bitDepth, raw.codecVersion, raw.cmp1, rule);
                perRule.put(rule, measure(img));
                burstMeans.get(rule).add(perRule.get(rule).fractionWithin1024OfMode);
            }

            Result jls = perRule.get(KUpdateRule.JPEG_LS);
            Result pat = perRule.get(KUpdateRule.PATENT_BRACKET);
            // Structural invariant: both rules must return identically shaped
            // buffers. Numeric content may legitimately differ.
            assertThat(pat.width).as("width invariance for %s", p.getFileName())
                    .isEqualTo(jls.width);
            assertThat(pat.height).as("height invariance for %s", p.getFileName())
                    .isEqualTo(jls.height);
            assertThat(pat.bitDepth).as("bit‑depth invariance for %s", p.getFileName())
                    .isEqualTo(jls.bitDepth);

            double delta = pat.fractionWithin1024OfMode - jls.fractionWithin1024OfMode;
            log.info("  {}", p.getFileName());
            log.info("      JPEG_LS         f±1024={}  mode≈{}  planeModes≈{}",
                    fmt(jls.fractionWithin1024OfMode),
                    jls.modeBucket * HIST_BUCKET, planeModesStr(jls));
            log.info("      PATENT_BRACKET  f±1024={}  mode≈{}  planeModes≈{}",
                    fmt(pat.fractionWithin1024OfMode),
                    pat.modeBucket * HIST_BUCKET, planeModesStr(pat));
            log.info("      Δ(patent − jpegls) = {}", fmt(delta));
        }

        double meanJls = burstMeans.get(KUpdateRule.JPEG_LS).stream()
                .mapToDouble(Double::doubleValue).average().orElse(0.0);
        double meanPat = burstMeans.get(KUpdateRule.PATENT_BRACKET).stream()
                .mapToDouble(Double::doubleValue).average().orElse(0.0);
        log.info("═══ Burst means across {} lossless fixtures ═══",
                burstMeans.get(KUpdateRule.JPEG_LS).size());
        log.info("    JPEG_LS         burst‑mean f±1024 = {}", fmt(meanJls));
        log.info("    PATENT_BRACKET  burst‑mean f±1024 = {}", fmt(meanPat));
        log.info("    Δ(patent − jpegls)               = {}", fmt(meanPat - meanJls));
        log.info("    → PATENT_BRACKET is the production default; this delta is retained as "
                + "an A/B regression signal against the legacy JPEG_LS path.");
    }

    // ───────────────────────── measurement helpers ─────────────────────────

    private static Result measure(Cr3RawImage image) {
        int[] data = image.data();
        int bd = image.bitDepth();
        int buckets = (1 << bd) / HIST_BUCKET;
        long[] hist = new long[buckets];
        for (int v : data) {
            hist[Math.min(buckets - 1, Math.max(0, v / HIST_BUCKET))]++;
        }
        int modeBucket = 0;
        long modeCount = 0;
        for (int i = 0; i < buckets; i++) {
            if (hist[i] > modeCount) {
                modeCount = hist[i];
                modeBucket = i;
            }
        }
        int center = modeBucket * HIST_BUCKET;
        long near = 0;
        for (int v : data) {
            if (v >= center - 1024 && v <= center + 1024) near++;
        }

        // Per‑plane mode buckets — same Bayer position decomposition as
        // DarkFixtureLosslessDecoderIT so the entropy‑lock‑loss signature
        // (one plane near pedestal, three at 16320) is visible side‑by‑side.
        int w = image.width(), h = image.height();
        long[][] planeHist = new long[4][buckets];
        for (int y = 0; y < h; y++) {
            int rowBase = y * w;
            int planeRow = (y & 1) << 1;
            for (int x = 0; x < w; x++) {
                int p = planeRow | (x & 1);
                int v = data[rowBase + x];
                planeHist[p][Math.min(buckets - 1, Math.max(0, v / HIST_BUCKET))]++;
            }
        }
        int[] planeModeBucket = new int[4];
        for (int p = 0; p < 4; p++) {
            long mc = 0;
            for (int i = 0; i < buckets; i++) {
                if (planeHist[p][i] > mc) {
                    mc = planeHist[p][i];
                    planeModeBucket[p] = i;
                }
            }
        }

        return new Result(w, h, bd, modeBucket, (double) near / data.length, planeModeBucket);
    }

    private static String planeModesStr(Result r) {
        StringBuilder sb = new StringBuilder("[");
        for (int p = 0; p < 4; p++) {
            if (p > 0) sb.append(", ");
            sb.append(r.planeModeBucket[p] * HIST_BUCKET);
        }
        return sb.append(']').toString();
    }

    private static String fmt(double d) { return String.format("%.4f", d); }

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

    private record Result(int width, int height, int bitDepth, int modeBucket,
                          double fractionWithin1024OfMode, int[] planeModeBucket) { }

    private record ParsedRaw(byte[] crxBytes, int width, int height, int bitDepth,
                             CrxHeader.CodecVersion codecVersion, Cmp1Box cmp1) { }
}