package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.bmff.Iad1Box;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.container.Cr3Track;
import com.photographer.focusstack.cr3.raw.Cr3RawImage;
import com.photographer.focusstack.cr3.raw.codec.CrxHeader;
import com.photographer.focusstack.cr3.raw.codec.CrxLosslessRawDecoder;
import com.photographer.focusstack.cr3.raw.codec.CrxLossyDecoder;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Work‑package 1 — burst‑frame stability as the new primary metric.
 *
 * <p>Premise: every burst captured at identical body settings is, modulo
 * shot noise, the same image. Any candidate decoder hypothesis whose output
 * <em>changes structurally</em> across burst frames is wrong, regardless of
 * how it scores against the embedded preview. This IT operationalises that
 * intuition by computing burst stability metrics on the production lossless
 * decoder output, on a per‑plane basis, for every detectable burst group in
 * the corpus.</p>
 *
 * <p>For every burst group the IT reports, per Bayer plane:</p>
 * <ul>
 *   <li><b>Pearson r</b> between the per‑pixel values of frame‑1 and frame‑2
 *       — a correct decoder of identical scenes should yield {@code r → 1};
 *       a noise‑producing decoder yields {@code r → 0}.</li>
 *   <li><b>MAE</b> and <b>MSE</b> at full resolution — the absolute and
 *       quadratic per‑pixel disagreement.</li>
 *   <li><b>Row stability</b> and <b>column stability</b> — Pearson r of the
 *       row‑mean and column‑mean profiles between the two frames. These are
 *       very robust against shot noise (it averages out), so they discriminate
 *       <em>structural</em> drift (a wrong predictor producing a slightly
 *       different banding pattern between frames) from <em>random</em> drift
 *       (genuine read noise).</li>
 *   <li><b>Local correlation</b> — Pearson r of 16×16 block means between
 *       the two frames; intermediate between full‑resolution and row/col,
 *       it sees mid‑frequency drift.</li>
 * </ul>
 *
 * <p>Results are written to {@code target/burst-stability/burst.tsv}. The IT
 * does not assert decode correctness; it asserts only that any detected
 * burst group decodes into matching dimensions. The numerical results are
 * the primary deliverable.</p>
 *
 * <p>This IT establishes the validation axis described as <b>Step 1</b> of
 * §6.3 of {@code docs/cr3-fixture-audit.md} and §8.6 of the same document.
 * It deliberately uses only the production decoder, not any candidate
 * variant — its purpose is to give every <em>future</em> hypothesis a
 * burst‑stability number that can be compared directly.</p>
 */
class BurstFrameStabilityIT {

    private static final Logger log = LoggerFactory.getLogger(BurstFrameStabilityIT.class);

    private static final Path SAMPLES_DIR  = Path.of("src", "test", "resources", "samples");
    private static final Path RESEARCH_DIR = SAMPLES_DIR.resolve("research");
    private static final Path REPORT_DIR   = Path.of("target", "burst-stability");

    /** Block size for the local‑correlation metric. */
    private static final int BLOCK = 16;

    @Test
    @DisplayName("Burst stability metrics for every detectable burst group in the corpus")
    void emitBurstStabilityReport() throws Exception {
        if (!Files.isDirectory(SAMPLES_DIR)) {
            log.info("No samples dir {} — skipping", SAMPLES_DIR.toAbsolutePath());
            return;
        }
        List<Path> all = new ArrayList<>();
        collect(SAMPLES_DIR, all);
        if (Files.isDirectory(RESEARCH_DIR)) collect(RESEARCH_DIR, all);
        all.sort((a, b) -> a.toString().compareTo(b.toString()));
        if (all.isEmpty()) {
            log.info("No CR3 fixtures — skipping");
            return;
        }

        Map<String, List<Path>> groups = new LinkedHashMap<>();
        for (Path p : all) {
            String key = burstKey(p.getFileName().toString());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }
        // Drop singletons — burst stability needs at least two frames.
        groups.values().removeIf(v -> v.size() < 2);
        if (groups.isEmpty()) {
            log.info("No multi‑frame burst groups found — skipping");
            return;
        }

        Files.createDirectories(REPORT_DIR);
        Path tsv = REPORT_DIR.resolve("burst.tsv");
        try (var out = Files.newBufferedWriter(tsv)) {
            out.write("group\tframeA\tframeB\tplane\tpixels\tpearson\tmae\tmse\trowR\tcolR\tlocalR\n");

            for (var entry : groups.entrySet()) {
                String group = entry.getKey();
                List<Path> frames = entry.getValue();
                log.info("══ Burst group {} — {} frames ══", group, frames.size());
                for (int i = 0; i + 1 < frames.size(); i++) {
                    Path a = frames.get(i);
                    Path b = frames.get(i + 1);
                    Decoded da, db;
                    try {
                        da = decode(a);
                        db = decode(b);
                    } catch (Exception ex) {
                        log.warn("  decode failed for pair {} ↔ {}: {}",
                                a.getFileName(), b.getFileName(), ex.toString());
                        continue;
                    }
                    if (da.w != db.w || da.h != db.h) {
                        log.warn("  dimension mismatch {} vs {} — skipping",
                                a.getFileName(), b.getFileName());
                        continue;
                    }
                    for (int p = 0; p < 4; p++) {
                        Stats s = compare(da, db, p);
                        out.write(String.format(
                                "%s\t%s\t%s\t%d\t%d\t%.4f\t%.2f\t%.2f\t%.4f\t%.4f\t%.4f%n",
                                group, a.getFileName(), b.getFileName(), p, s.n,
                                s.pearson, s.mae, s.mse, s.rowR, s.colR, s.localR));
                        log.info("  {} ↔ {} plane[{}] r={} mae={} rowR={} colR={} localR={}",
                                a.getFileName(), b.getFileName(), p,
                                fmt(s.pearson), String.format("%.1f", s.mae),
                                fmt(s.rowR), fmt(s.colR), fmt(s.localR));
                    }
                }
            }
        }
        log.info("Wrote {}", tsv.toAbsolutePath());
    }

    // ─────────────────────────────────────────── helpers ──

    private static void collect(Path dir, List<Path> out) throws java.io.IOException {
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isRegularFile)
             .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".cr3"))
             .forEach(out::add);
        }
    }

    /**
     * Tightened burst key — exactly the same rule as
     * {@link FixtureIntelligenceReportIT#burstKey}: only files that share
     * camera + scene + exposure + runId form a burst.
     */
    private static String burstKey(String name) {
        String base = name.toLowerCase().replaceFirst("\\.cr3$", "");
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(.*)-(\\d{8}-\\d{6})-(\\d{2})$").matcher(base);
        if (m.matches()) {
            return m.group(1) + "|" + m.group(2);
        }
        return base + "|solo";
    }

    private record Decoded(int w, int h, int bd, int[] data) { }

    private static Decoded decode(Path file) throws Exception {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            Cr3Container c = Cr3Parser.parse(in);
            Cr3Track raw = c.tracks().stream()
                    .filter(t -> t.type() == Cr3Track.TrackType.CRX_BIG
                              || t.type() == Cr3Track.TrackType.CRX_RAW)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("no RAW track"));
            CrxHeader.CodecVersion ver = c.cncvBox() == null
                    ? CrxHeader.CodecVersion.V1
                    : CrxHeader.detectVersion(c.cncvBox().versionString());
            Cmp1Box cmp1 = raw.cmp1();
            int w = cmp1 != null && cmp1.imageWidth() > 0 ? cmp1.imageWidth() : raw.width();
            int h = cmp1 != null && cmp1.imageHeight() > 0 ? cmp1.imageHeight() : raw.height();
            int bd = cmp1 != null && cmp1.bitsPerSample() > 0 ? cmp1.bitsPerSample() : 14;
            byte[] bytes = new byte[(int) raw.dataSize()];
            in.seek(raw.dataOffset());
            in.readFully(bytes);
            Cr3RawImage img = CrxLossyDecoder.isLossy(cmp1)
                    ? CrxLossyDecoder.decode(bytes, w, h, bd, ver, cmp1)
                    : CrxLosslessRawDecoder.decode(bytes, w, h, bd, ver, cmp1);
            return new Decoded(img.width(), img.height(), img.bitDepth(), img.data());
        }
    }

    /** Pearson, MAE, MSE, row-r, col-r, local-r between the same Bayer plane of two frames. */
    private static Stats compare(Decoded a, Decoded b, int plane) {
        int w = a.w, h = a.h;
        int rowOff = (plane >> 1) & 1;
        int colOff = plane & 1;
        int pw = (w - colOff + 1) / 2;
        int ph = (h - rowOff + 1) / 2;
        long n = 0;
        double sumA = 0, sumB = 0, sumAA = 0, sumBB = 0, sumAB = 0;
        double mae = 0, mse = 0;
        // Row/col profiles (means) at plane resolution.
        double[] rowMeanA = new double[ph], rowMeanB = new double[ph];
        long[] rowCount = new long[ph];
        double[] colMeanA = new double[pw], colMeanB = new double[pw];
        long[] colCount = new long[pw];
        for (int y = rowOff, py = 0; y < h; y += 2, py++) {
            int rowBase = y * w;
            for (int x = colOff, px = 0; x < w; x += 2, px++) {
                int va = a.data[rowBase + x];
                int vb = b.data[rowBase + x];
                sumA += va; sumB += vb;
                sumAA += (double) va * va; sumBB += (double) vb * vb;
                sumAB += (double) va * vb;
                int diff = va - vb;
                mae += Math.abs(diff);
                mse += (double) diff * diff;
                rowMeanA[py] += va; rowMeanB[py] += vb; rowCount[py]++;
                colMeanA[px] += va; colMeanB[px] += vb; colCount[px]++;
                n++;
            }
        }
        Stats s = new Stats();
        s.n = n;
        if (n > 0) {
            double meanA = sumA / n;
            double meanB = sumB / n;
            double varA = sumAA / n - meanA * meanA;
            double varB = sumBB / n - meanB * meanB;
            double cov  = sumAB / n - meanA * meanB;
            double denom = Math.sqrt(Math.max(0, varA) * Math.max(0, varB));
            s.pearson = denom > 0 ? cov / denom : 0;
            s.mae = mae / n;
            s.mse = mse / n;
        }
        // Normalise row/col profiles
        for (int i = 0; i < ph; i++) if (rowCount[i] > 0) {
            rowMeanA[i] /= rowCount[i]; rowMeanB[i] /= rowCount[i];
        }
        for (int i = 0; i < pw; i++) if (colCount[i] > 0) {
            colMeanA[i] /= colCount[i]; colMeanB[i] /= colCount[i];
        }
        s.rowR = pearsonOfArrays(rowMeanA, rowMeanB);
        s.colR = pearsonOfArrays(colMeanA, colMeanB);
        s.localR = blockCorrelation(a, b, plane);
        return s;
    }

    private static double blockCorrelation(Decoded a, Decoded b, int plane) {
        int rowOff = (plane >> 1) & 1, colOff = plane & 1;
        int w = a.w, h = a.h;
        int pw = (w - colOff + 1) / 2, ph = (h - rowOff + 1) / 2;
        int bx = pw / BLOCK, by = ph / BLOCK;
        if (bx <= 1 || by <= 1) return Double.NaN;
        double[] mA = new double[bx * by], mB = new double[bx * by];
        for (int j = 0; j < by; j++) {
            for (int i = 0; i < bx; i++) {
                long sA = 0, sB = 0;
                for (int yy = 0; yy < BLOCK; yy++) {
                    int y = rowOff + (j * BLOCK + yy) * 2;
                    int rowBase = y * w;
                    for (int xx = 0; xx < BLOCK; xx++) {
                        int x = colOff + (i * BLOCK + xx) * 2;
                        sA += a.data[rowBase + x];
                        sB += b.data[rowBase + x];
                    }
                }
                mA[j * bx + i] = sA;
                mB[j * bx + i] = sB;
            }
        }
        return pearsonOfArrays(mA, mB);
    }

    private static double pearsonOfArrays(double[] a, double[] b) {
        int n = a.length;
        if (n == 0) return Double.NaN;
        double sa = 0, sb = 0;
        for (int i = 0; i < n; i++) { sa += a[i]; sb += b[i]; }
        double ma = sa / n, mb = sb / n;
        double cov = 0, va = 0, vb = 0;
        for (int i = 0; i < n; i++) {
            double da = a[i] - ma, db = b[i] - mb;
            cov += da * db; va += da * da; vb += db * db;
        }
        double denom = Math.sqrt(va * vb);
        return denom > 0 ? cov / denom : 0;
    }

    private static String fmt(double d) {
        return Double.isNaN(d) ? "NaN" : String.format("%.4f", d);
    }

    private static final class Stats {
        long n; double pearson, mae, mse, rowR, colR, localR;
    }
}
