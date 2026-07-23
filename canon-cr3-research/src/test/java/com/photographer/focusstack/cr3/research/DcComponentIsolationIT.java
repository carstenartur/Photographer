package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.Cr3Exception;
import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.bmff.Iad1Box;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.container.Cr3Track;
import com.photographer.focusstack.cr3.raw.Cr3RawImage;
import com.photographer.focusstack.cr3.raw.codec.CrxHeader;
import com.photographer.focusstack.cr3.raw.codec.CrxLosslessRawDecoder;
import com.photographer.focusstack.cr3.raw.codec.SubbandExtractor;
import com.photographer.focusstack.cr3.reference.ReferenceFixtureResolver;
import com.photographer.focusstack.cr3.reference.ReferenceImage;
import com.photographer.focusstack.cr3.reference.ReferenceImageLoader;
import com.photographer.focusstack.cr3.reference.ReferenceMetrics;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Phase E — DC Component Isolation.
 *
 * <p>Investigates where the DC component is lost in the CRX lossless decoder
 * by measuring pixel means across regions and sweeping different first-pixel
 * seed values in the test decoder. No production code is modified.</p>
 *
 * <h3>Tests performed</h3>
 * <ul>
 *   <li><b>Test 1 — DC Means</b>: For each fixture with a reference TIFF, measures
 *       {@code mean(reference)}, {@code mean(decoder)}, {@code mean(OB region)},
 *       {@code mean(active region)}, and the DC bias. Answers: is the error
 *       constant (uniform offset) or image-dependent?</li>
 *   <li><b>Test 2 — Seed Variants</b>: Runs the test decoder
 *       ({@link CrxNormativeLosslessCandidate}) on plane 0 of each fixture with
 *       four first-pixel seed strategies — Seed=0, Seed=OB_Mean, Seed=First_Pixel
 *       (first pixel of the reference plane), Seed=Tile_Start (reference pixel at
 *       the start of each tile) — and reports MAE and Pearson against the
 *       reference plane.</li>
 *   <li><b>Test 3 — First Error Point</b>: For the first fixture with a large DC
 *       bias, emits {@code decoder-step-report.md} showing predictor, decoded, and
 *       reference values for the first {@value #STEP_PIXELS} pixels of plane 0.</li>
 *   <li><b>Test 4 — OB Level</b>: Compares {@code OB_Mean(decoded)} to
 *       {@code mean(reference)} to detect errors that originate before the
 *       predictor (e.g. wrong seed or incorrect OB handling).</li>
 * </ul>
 *
 * <p>Output: {@code canon-cr3-imageio/target/dc-component-isolation/report.md}
 * and {@code .../decoder-step-report.md}.</p>
 */
class DcComponentIsolationIT {

    private static final Path SAMPLES_DIR = Path.of("src/test/resources/samples");
    private static final ReferenceFixtureResolver FIXTURES =
            new ReferenceFixtureResolver(SAMPLES_DIR);
    private static final Path OUT_DIR = Path.of("target/dc-component-isolation");

    /** Number of pixels captured in the step report. */
    private static final int STEP_PIXELS = 100;

    /**
     * DC bias threshold (in raw LSB) above which a fixture is considered a
     * strong D1 candidate and used for the step report.
     */
    private static final double D1_BIAS_THRESHOLD = 500.0;

    @Test
    void testDcComponentIsolation() throws Exception {
        List<Path> fixtures = discoverFixturesWithReferences();
        assumeTrue(!fixtures.isEmpty(), "No lossless CR3 fixtures with reference TIFFs found");

        Files.createDirectories(OUT_DIR);

        StringBuilder md = new StringBuilder();
        md.append("# Phase E — DC Component Isolation\n\n");
        md.append("Generated: ").append(Instant.now()).append("\n\n");
        md.append("> No production code was modified. This is a pure measurement run.\n\n");

        // Per-fixture data collected in one pass, consumed by all four tests.
        List<FixtureData> data = collectFixtureData(fixtures, md);

        // ── Test 1 — DC Means ─────────────────────────────────────────────
        appendTest1(md, data);

        // ── Test 2 — Seed Variants ────────────────────────────────────────
        appendTest2(md, data, fixtures);

        // ── Test 3 — First Error Point (step report) ──────────────────────
        appendTest3(data);

        md.append("---\n\n");
        md.append("*See decoder-step-report.md for the pixel-level step trace.*\n");

        // ── Test 4 — OB Level ─────────────────────────────────────────────
        appendTest4(md, data);

        String report = md.toString();
        System.out.println("\n=== DC COMPONENT ISOLATION REPORT ===\n" + report);
        Files.writeString(OUT_DIR.resolve("report.md"), report);

        assertThat(OUT_DIR.resolve("report.md")).exists();
        // Diagnostic test — always passes.
    }

    // ── Data collection ───────────────────────────────────────────────────

    private record FixtureData(
            String name,
            Path fixture,
            // means
            double refMean,
            double decoderMean,
            double dcBias,
            double obMean,         // NaN if no IAD1 / narrow OB
            double activeMean,
            // OB mean per plane 0 (for seed computation)
            double obMeanPlane0,   // NaN if unavailable
            // decoded full bayer (for OB extraction)
            int[] decoded,
            int refWidth,
            int refHeight,
            int[] reference,
            Iad1Box iad1
    ) {}

    private List<FixtureData> collectFixtureData(List<Path> fixtures, StringBuilder md)
            throws IOException {
        List<FixtureData> result = new ArrayList<>();
        for (Path fixture : fixtures) {
            Path refTiff = FIXTURES.resolve(fixture).requiredTiff();

            String name = FIXTURES.samplesRoot().relativize(fixture)
                    .toString().replace('\\', '/');

            ReferenceImage reference;
            try {
                reference = ReferenceImageLoader.load(refTiff);
            } catch (Exception e) {
                md.append("> WARNING: cannot load reference for `").append(name)
                        .append("`: ").append(e.getMessage()).append("\n\n");
                continue;
            }

            int[] decoded;
            try {
                decoded = decodeProductionFull(fixture, reference.width(), reference.height());
            } catch (Exception e) {
                md.append("> WARNING: decode error for `").append(name)
                        .append("`: ").append(e.getMessage()).append("\n\n");
                continue;
            }
            if (decoded == null) continue; // no lossless track

            Iad1Box iad1 = loadIad1(fixture);
            int[] leftOb = iad1 != null
                    ? clampRect(iad1.leftObLeft(), iad1.leftObTop(),
                    iad1.leftObRight(), iad1.leftObBottom(), reference.width(), reference.height())
                    : new int[]{0, 0, 0, 0};
            int[] active = iad1 != null
                    ? resolveActiveRect(iad1, leftOb, reference.width(), reference.height())
                    : new int[]{0, 0, reference.width(), reference.height()};

            double refMean = ReferenceMetrics.mean(reference.samples());
            double decMean = ReferenceMetrics.mean(decoded);
            double dcBias = refMean - decMean;

            double obMean = Double.NaN;
            double activeMean = decMean;
            double obMeanPlane0 = Double.NaN;

            if (leftOb[2] > 0 && leftOb[3] > 0) {
                obMean = meanRect(decoded, reference.width(), leftOb[0], leftOb[1], leftOb[2], leftOb[3]);
                obMeanPlane0 = meanRectForPlane(decoded, reference.width(), reference.height(),
                        leftOb[0], leftOb[1], leftOb[2], leftOb[3], 0);
            }
            if (active[2] > 0 && active[3] > 0) {
                activeMean = meanRect(decoded, reference.width(), active[0], active[1], active[2], active[3]);
            }

            result.add(new FixtureData(name, fixture, refMean, decMean, dcBias,
                    obMean, activeMean, obMeanPlane0,
                    decoded, reference.width(), reference.height(), reference.samples(), iad1));
        }
        return result;
    }

    // ── Test 1 — DC Means ────────────────────────────────────────────────

    private static void appendTest1(StringBuilder md, List<FixtureData> data) {
        md.append("---\n\n");
        md.append("## Test 1 — DC Means Comparison\n\n");
        md.append("Is the DC error **constant** (same bias for all scenes) or **image-dependent**?\n\n");
        md.append("| Fixture | RefMean | DecMean | DC-bias | OB-Mean | ActiveMean | Constant? |\n");
        md.append("|---|---:|---:|---:|---:|---:|:---:|\n");

        for (FixtureData d : data) {
            // "constant bias": active bias ≈ OB bias → same offset throughout the plane
            boolean constant = Double.isFinite(d.obMean())
                    && Math.abs((d.obMean() - d.decoderMean()) - (d.activeMean() - d.decoderMean())) < 50;

            md.append("| `").append(d.name()).append("` | ")
                    .append(fmt(d.refMean())).append(" | ")
                    .append(fmt(d.decoderMean())).append(" | ")
                    .append(fmt(d.dcBias())).append(" | ")
                    .append(nanFmt(d.obMean())).append(" | ")
                    .append(nanFmt(d.activeMean())).append(" | ")
                    .append(constant ? "**YES**" : "no").append(" |\n");

            System.out.printf("T1  %-55s refMean=%7.0f decMean=%7.0f bias=%+7.0f ob=%s active=%s%n",
                    d.name(), d.refMean(), d.decoderMean(), d.dcBias(),
                    nanFmt(d.obMean()), nanFmt(d.activeMean()));
        }
        md.append("\n> **DC-bias** = mean(reference) − mean(decoder). "
                + "A large positive bias means the decoder output is systematically too low. "
                + "If bias is consistent across OB and active regions, the error is a global offset.\n\n");
    }

    // ── Test 2 — Seed Variants ────────────────────────────────────────────

    private static void appendTest2(StringBuilder md, List<FixtureData> data,
                                    List<Path> fixtures) {
        md.append("---\n\n");
        md.append("## Test 2 — Seed Variants (test decoder, plane 0)\n\n");
        md.append("Seed variants tried against reference plane 0 using the normative test decoder.\n\n");
        md.append("| Fixture/Tile | Seed | SeedVal | MAE | Pearson | Best? |\n");
        md.append("|---|---|---:|---:|---:|:---:|\n");

        for (FixtureData fd : data) {
            List<SubbandExtractor.SubbandRef> subs;
            try {
                subs = SubbandExtractor.list(fd.fixture().toFile());
            } catch (Exception e) {
                md.append("| `").append(fd.name()).append("` | — | — | SUBBAND-ERROR: ")
                        .append(e.getMessage()).append(" | — | — |\n");
                continue;
            }

            // Only lossless plane 0 subbands, grouped by tile sequence
            Map<Integer, SubbandExtractor.SubbandRef> tileToSub = new LinkedHashMap<>();
            for (SubbandExtractor.SubbandRef sub : subs) {
                if (sub.planeIndex() == 0 && sub.subbandIndex() == 0 && sub.waveletLevels() == 0) {
                    tileToSub.putIfAbsent(sub.tileSequence(), sub);
                }
            }
            if (tileToSub.isEmpty()) continue;

            // Extract reference plane 0 and tile offsets
            int bayerW = fd.refWidth();
            int planeW = (bayerW + 1) / 2;

            for (Map.Entry<Integer, SubbandExtractor.SubbandRef> entry : tileToSub.entrySet()) {
                int tileSeq = entry.getKey();
                SubbandExtractor.SubbandRef sub = entry.getValue();

                byte[] payload;
                try {
                    payload = SubbandExtractor.read(fd.fixture().toFile(), sub);
                } catch (Exception e) {
                    md.append("| `").append(fd.name()).append("` (tile ").append(tileSeq)
                            .append(") | — | — | READ-ERROR: ").append(e.getMessage())
                            .append(" | — | — |\n");
                    continue;
                }

                // Reference plane 0 pixels for this tile
                int tileXOff = tileSeq == 0 ? 0
                        : (int) Math.round(tileSeq * (planeW / (double) tileToSub.size()));
                int tilePW = sub.planeWidth();
                int tilePH = sub.planeHeight();
                int[] refTilePlane = extractTilePlane(fd.reference(), bayerW, fd.refHeight(),
                        tileXOff, tilePW, tilePH, 0);
                if (refTilePlane == null) continue;

                // OB mean for plane 0 (from production decode)
                int obSeedVal = Double.isFinite(fd.obMeanPlane0())
                        ? (int) Math.round(fd.obMeanPlane0()) : 0;

                // Global first pixel of reference plane 0
                int firstPixelSeedVal = planePixel(fd.reference(), bayerW, 0, 0, 0);

                // Reference plane 0 pixel at this tile's start
                int tileStartSeedVal = refTilePlane[0];

                int[] seeds = {0, obSeedVal, firstPixelSeedVal, tileStartSeedVal};
                String[] seedNames = {"Seed=0", "Seed=OB_Mean", "Seed=First_Pixel", "Seed=Tile_Start"};

                double bestMae = Double.MAX_VALUE;
                double[] maes = new double[seeds.length];
                double[] pearsons = new double[seeds.length];

                for (int i = 0; i < seeds.length; i++) {
                    try {
                        CrxNormativeLosslessCandidate.Options opt = specOptions();
                        opt.firstPixelSeed = seeds[i];
                        CrxNormativeLosslessCandidate.Result res =
                                CrxNormativeLosslessCandidate.decodePlane(
                                        payload, tilePW, tilePH, sub.bitDepth(), opt);
                        ReferenceMetrics.Metrics metrics = ReferenceMetrics.compare(
                                refTilePlane, res.pixels);
                        maes[i] = metrics.mae();
                        pearsons[i] = metrics.pearson();
                        if (maes[i] < bestMae) bestMae = maes[i];
                    } catch (Exception e) {
                        maes[i] = Double.NaN;
                        pearsons[i] = Double.NaN;
                    }
                }

                String label = tileToSub.size() > 1
                        ? "`" + fd.name() + "` t" + tileSeq
                        : "`" + fd.name() + "`";

                for (int i = 0; i < seeds.length; i++) {
                    boolean best = Double.isFinite(maes[i]) && maes[i] == bestMae;
                    md.append("| ").append(label).append(" | ")
                            .append(seedNames[i]).append(" | ")
                            .append(seeds[i]).append(" | ")
                            .append(nanFmt(maes[i])).append(" | ")
                            .append(nanFmt(pearsons[i])).append(" | ")
                            .append(best ? "**YES**" : "no").append(" |\n");
                    System.out.printf("T2  %-48s %-22s seed=%6d  mae=%9.2f  r=%+.4f  best=%s%n",
                            fd.name(), seedNames[i], seeds[i], maes[i], pearsons[i],
                            best ? "YES" : "no");
                }
            }
        }

        md.append("\n> Seed variants are applied only to the first-pixel prediction of the test decoder.\n");
        md.append("> **Best?** marks the seed with the lowest MAE per tile.\n\n");
    }

    // ── Test 3 — First Error Point ────────────────────────────────────────

    private void appendTest3(List<FixtureData> data) throws IOException {
        // Find the fixture with the largest DC bias (D1 candidate)
        FixtureData target = data.stream()
                .filter(d -> d.dcBias() >= D1_BIAS_THRESHOLD)
                .max(Comparator.comparingDouble(FixtureData::dcBias))
                .orElse(data.isEmpty() ? null : data.get(0));

        if (target == null) return;

        // Get lossless plane 0 subband for tile 0
        List<SubbandExtractor.SubbandRef> subs;
        try {
            subs = SubbandExtractor.list(target.fixture().toFile());
        } catch (Exception e) {
            return;
        }
        SubbandExtractor.SubbandRef sub = subs.stream()
                .filter(s -> s.planeIndex() == 0 && s.subbandIndex() == 0
                        && s.tileSequence() == 0 && s.waveletLevels() == 0)
                .findFirst().orElse(null);
        if (sub == null) return;

        byte[] payload;
        try {
            payload = SubbandExtractor.read(target.fixture().toFile(), sub);
        } catch (Exception e) {
            return;
        }

        int tilePW = sub.planeWidth();
        int tilePH = sub.planeHeight();
        int[] refPlane = extractTilePlane(target.reference(), target.refWidth(),
                target.refHeight(), 0, tilePW, tilePH, 0);
        if (refPlane == null) return;

        // Decode with Seed=0
        CrxNormativeLosslessCandidate.Options opt = specOptions();
        opt.firstPixelSeed = 0;
        CrxNormativeLosslessCandidate.Result result;
        try {
            result = CrxNormativeLosslessCandidate.decodePlane(
                    payload, tilePW, tilePH, sub.bitDepth(), opt);
        } catch (Exception e) {
            return;
        }

        // Build step report
        StringBuilder sr = new StringBuilder();
        sr.append("# Decoder Step Report — First ").append(STEP_PIXELS).append(" Pixels\n\n");
        sr.append("Generated: ").append(Instant.now()).append("\n\n");
        sr.append("Fixture: `").append(target.name()).append("`  \n");
        sr.append("Plane: 0 (even-row, even-col)  \n");
        sr.append("Seed: 0  \n");
        sr.append("Decoder: CrxNormativeLosslessCandidate (spec options)  \n\n");
        sr.append("**Residual** = Decoded − Predictor (reconstruction residual, not bitstream symbol)  \n\n");
        sr.append("| Pixel | (x,y) | Predictor | Decoded | Reference | Residual | Diff(Dec−Ref) |\n");
        sr.append("|---:|---:|---:|---:|---:|---:|---:|\n");

        int[] decoded = result.pixels;
        int limit = Math.min(STEP_PIXELS, decoded.length);

        for (int idx = 0; idx < limit; idx++) {
            int x = idx % tilePW;
            int y = idx / tilePW;

            int predictor;
            if (x == 0 && y == 0) {
                predictor = opt.firstPixelSeed;
            } else if (y == 0) {
                predictor = decoded[x - 1];
            } else if (x == 0) {
                predictor = decoded[(y - 1) * tilePW];
            } else {
                int W = decoded[y * tilePW + (x - 1)];
                int N = decoded[(y - 1) * tilePW + x];
                int NW = decoded[(y - 1) * tilePW + (x - 1)];
                predictor = medPredict(W, N, NW);
            }

            int dec = decoded[idx];
            int ref = idx < refPlane.length ? refPlane[idx] : -1;
            int residual = dec - predictor;
            int diff = ref >= 0 ? dec - ref : Integer.MIN_VALUE;

            sr.append("| ").append(idx).append(" | (").append(x).append(",").append(y).append(") | ")
                    .append(predictor).append(" | ")
                    .append(dec).append(" | ")
                    .append(ref >= 0 ? ref : "n/a").append(" | ")
                    .append(residual).append(" | ")
                    .append(ref >= 0 ? String.valueOf(diff) : "n/a").append(" |\n");
        }

        sr.append("\n## Observations\n\n");
        // Quick check: is pixel 0 already wrong?
        if (refPlane.length > 0 && Math.abs(decoded[0] - refPlane[0]) > 100) {
            sr.append("- **Pixel 0 is already wrong** (decoded=").append(decoded[0])
                    .append(", reference=").append(refPlane[0])
                    .append(", diff=").append(decoded[0] - refPlane[0])
                    .append("). The seed (0) does not match the expected first value.\n");
        } else if (refPlane.length > 0) {
            sr.append("- Pixel 0 is close to reference (decoded=").append(decoded[0])
                    .append(", reference=").append(refPlane[0]).append("). Seed appears correct.\n");
        }

        // Check how quickly error accumulates
        int firstBigErrorIdx = -1;
        for (int i = 0; i < limit && i < refPlane.length; i++) {
            if (Math.abs(decoded[i] - refPlane[i]) > 500) {
                firstBigErrorIdx = i;
                break;
            }
        }
        if (firstBigErrorIdx == 0) {
            sr.append("- Large error starts at pixel 0 → likely a seed or OB-level problem.\n");
        } else if (firstBigErrorIdx > 0) {
            int ex = firstBigErrorIdx % tilePW;
            int ey = firstBigErrorIdx / tilePW;
            sr.append("- Large error (>500 LSB) first appears at pixel ").append(firstBigErrorIdx)
                    .append(" (x=").append(ex).append(", y=").append(ey).append("). ")
                    .append(ey > 0 ? "Error builds up across rows." : "Error within first row.")
                    .append("\n");
        } else {
            sr.append("- No large error (>500 LSB) found within the first ").append(STEP_PIXELS)
                    .append(" pixels.\n");
        }

        String stepReport = sr.toString();
        System.out.println("\n=== DECODER STEP REPORT ===\n" + stepReport);
        Files.writeString(OUT_DIR.resolve("decoder-step-report.md"), stepReport);
    }

    // ── Test 4 — OB Level ─────────────────────────────────────────────────

    private static void appendTest4(StringBuilder md, List<FixtureData> data) {
        md.append("---\n\n");
        md.append("## Test 4 — OB Level\n\n");
        md.append("If OB_Mean(decoded) is far from mean(reference), the error occurs **before** the predictor.\n\n");
        md.append("| Fixture | OB-Mean | RefMean | OB-Error | Verdict |\n");
        md.append("|---|---:|---:|---:|:---:|\n");

        for (FixtureData d : data) {
            if (!Double.isFinite(d.obMean())) {
                md.append("| `").append(d.name()).append("` | n/a | ")
                        .append(fmt(d.refMean())).append(" | n/a | no_OB |\n");
                continue;
            }
            double obError = d.obMean() - d.refMean();
            String verdict;
            if (Math.abs(obError) > 2000) {
                verdict = "**OB WRONG → error before predictor**";
            } else if (Math.abs(obError) > 500) {
                verdict = "OB_suspicious";
            } else {
                verdict = "OB_OK";
            }
            md.append("| `").append(d.name()).append("` | ")
                    .append(fmt(d.obMean())).append(" | ")
                    .append(fmt(d.refMean())).append(" | ")
                    .append(fmt(obError)).append(" | ")
                    .append(verdict).append(" |\n");

            System.out.printf("T4  %-55s ob=%7.0f ref=%7.0f err=%+7.0f  %s%n",
                    d.name(), d.obMean(), d.refMean(), obError, verdict);
        }
        md.append("\n> **OB-Error** = OB_Mean(decoded) − mean(reference). "
                + "A large negative value means the decoded OB pixels are far too low, "
                + "which typically indicates the decoder seed is wrong (e.g. 0 instead of "
                + "the camera's OB pedestal).\n");
    }

    // ── Fixture discovery ─────────────────────────────────────────────────

    private static List<Path> discoverFixturesWithReferences() throws IOException {
        return FIXTURES.discover().stream()
                .filter(fixture -> !fixture.missingSidecars().contains(
                        ReferenceFixtureResolver.MissingSidecar.TIFF))
                .map(ReferenceFixtureResolver.ResolvedFixture::cr3)
                .toList();
    }

    // ── Production decoder ────────────────────────────────────────────────

    /**
     * Decodes the full Bayer image with the production decoder. Returns
     * {@code null} if the fixture has no lossless RAW track.
     */
    private static int[] decodeProductionFull(Path fixture, int expectedW, int expectedH)
            throws IOException, Cr3Exception {
        try (ImageInputStream in = ImageIO.createImageInputStream(fixture.toFile())) {
            if (in == null) throw new IOException("Cannot open: " + fixture);
            Cr3Container container = Cr3Parser.parse(in);
            Cr3Track rawTrack = container.tracks().stream()
                    .filter(t -> t.type() == Cr3Track.TrackType.CRX_BIG
                            || t.type() == Cr3Track.TrackType.CRX_RAW)
                    .findFirst().orElse(null);
            if (rawTrack == null) return null;
            Cmp1Box cmp1 = rawTrack.cmp1();
            if (cmp1 == null || !cmp1.isLossless()) return null;

            byte[] payload = new byte[(int) rawTrack.dataSize()];
            in.seek(rawTrack.dataOffset());
            in.readFully(payload);

            CrxHeader.CodecVersion version = container.cncvBox() == null
                    ? CrxHeader.CodecVersion.V1
                    : CrxHeader.detectVersion(container.cncvBox().versionString());

            Cr3RawImage raw = CrxLosslessRawDecoder.decode(payload,
                    cmp1.imageWidth(), cmp1.imageHeight(),
                    cmp1.bitsPerSample(), version, cmp1);

            if (raw.width() != expectedW || raw.height() != expectedH) {
                throw new IllegalStateException("Decoder " + raw.width() + "x" + raw.height()
                        + " != reference " + expectedW + "x" + expectedH);
            }
            return raw.data();
        }
    }

    // ── IAD1 loading ──────────────────────────────────────────────────────

    private static Iad1Box loadIad1(Path fixture) {
        try (ImageInputStream in = ImageIO.createImageInputStream(fixture.toFile())) {
            if (in == null) return null;
            Cr3Container container = Cr3Parser.parse(in);
            return container.tracks().stream()
                    .filter(t -> t.type() == Cr3Track.TrackType.CRX_BIG
                            || t.type() == Cr3Track.TrackType.CRX_RAW)
                    .map(Cr3Track::iad1)
                    .filter(Objects::nonNull)
                    .findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    // ── Region statistics ─────────────────────────────────────────────────

    /**
     * Mean of all pixels in the Bayer image within the rectangle
     * {@code [x, x + width) × [y, y + height)}.
     */
    private static double meanRect(int[] bayer, int w, int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) return Double.NaN;
        long sum = 0;
        long count = 0;
        for (int row = y; row < y + height; row++) {
            int rowBase = row * w;
            for (int col = x; col < x + width; col++) {
                sum += bayer[rowBase + col];
                count++;
            }
        }
        return count > 0 ? (double) sum / count : Double.NaN;
    }

    /**
     * Mean for a specific Bayer plane within the rectangle
     * {@code [x, x + width) × [y, y + height)}.
     * Plane 0 = even row, even col; plane 1 = even row, odd col;
     * plane 2 = odd row, even col; plane 3 = odd row, odd col.
     */
    private static double meanRectForPlane(int[] bayer, int w, int h,
                                           int x, int y, int width, int height, int plane) {
        int rowParity = (plane >= 2) ? 1 : 0;
        int colParity = (plane == 1 || plane == 3) ? 1 : 0;
        long sum = 0;
        int count = 0;
        int yStart = alignToParity(y, rowParity);
        int yEnd = Math.min(y + height, h);
        int xStart = alignToParity(x, colParity);
        int xEnd = Math.min(x + width, w);
        for (int row = yStart; row < yEnd; row += 2) {
            int rowBase = row * w;
            for (int col = xStart; col < xEnd; col += 2) {
                sum += bayer[rowBase + col];
                count++;
            }
        }
        return count > 0 ? (double) sum / count : Double.NaN;
    }

    private static int alignToParity(int value, int parity) {
        return ((value ^ parity) & 1) == 0 ? value : value + 1;
    }

    // ── Plane extraction ──────────────────────────────────────────────────

    /**
     * Extracts one Bayer plane from the full Bayer array for a specific tile.
     *
     * <p>The CRX encoder interleaves planes positionally:
     * plane 0 = (even row, even col), plane 1 = (even row, odd col),
     * plane 2 = (odd row, even col), plane 3 = (odd row, odd col).</p>
     *
     * @param bayer      full Bayer array (width × height)
     * @param bayerW     full Bayer width
     * @param bayerH     full Bayer height
     * @param tileXOff   tile x-offset in the plane grid (pixels, not Bayer pixels)
     * @param tilePW     tile plane width in plane pixels
     * @param tilePH     tile plane height in plane pixels
     * @param planeIndex 0–3
     * @return the plane pixels, or {@code null} if bounds are invalid
     */
    private static int[] extractTilePlane(int[] bayer, int bayerW, int bayerH,
                                          int tileXOff, int tilePW, int tilePH, int planeIndex) {
        int rowOff = (planeIndex >= 2) ? 1 : 0;
        int colOff = (planeIndex == 1 || planeIndex == 3) ? 1 : 0;
        int[] out = new int[tilePW * tilePH];
        for (int py = 0; py < tilePH; py++) {
            int bayerY = py * 2 + rowOff;
            if (bayerY >= bayerH) break;
            for (int px = 0; px < tilePW; px++) {
                int bayerX = (tileXOff + px) * 2 + colOff;
                if (bayerX >= bayerW) break;
                out[py * tilePW + px] = bayer[bayerY * bayerW + bayerX];
            }
        }
        return out;
    }

    private static int planePixel(int[] bayer, int bayerW, int planeIndex, int planeX, int planeY) {
        int rowOff = (planeIndex >= 2) ? 1 : 0;
        int colOff = (planeIndex == 1 || planeIndex == 3) ? 1 : 0;
        return bayer[(planeY * 2 + rowOff) * bayerW + (planeX * 2 + colOff)];
    }

    private static int[] resolveActiveRect(Iad1Box iad1, int[] leftOb, int imgW, int imgH) {
        int[] active = clampRect(iad1.activeLeft(), iad1.activeTop(),
                iad1.activeRight(), iad1.activeBottom(), imgW, imgH);
        if (active[2] > 0 && active[3] > 0) {
            return active;
        }
        int[] crop = clampRect(iad1.cropLeft(), iad1.cropTop(),
                iad1.cropRight(), iad1.cropBottom(), imgW, imgH);
        if (crop[2] > 0 && crop[3] > 0) {
            return crop;
        }
        int left = Math.max(leftOb[0] + leftOb[2], 0);
        return clampRect(left, 0, imgW, imgH, imgW, imgH);
    }

    private static int[] clampRect(int x0, int y0, int x1, int y1, int w, int h) {
        x0 = Math.max(0, Math.min(w, x0));
        y0 = Math.max(0, Math.min(h, y0));
        x1 = Math.max(x0, Math.min(w, x1));
        y1 = Math.max(y0, Math.min(h, y1));
        return new int[]{x0, y0, x1 - x0, y1 - y0};
    }

    // ── Test decoder options ──────────────────────────────────────────────

    /** Spec (normative) options matching CrxNormativeLosslessCandidateIT. */
    private static CrxNormativeLosslessCandidate.Options specOptions() {
        CrxNormativeLosslessCandidate.Options o = new CrxNormativeLosslessCandidate.Options();
        o.kInit = 1;
        o.resetKPerRow = true;
        o.unaryOnesUntilZero = true;
        o.signBitMapping = true;
        o.escapeAtUnary15 = true;
        o.firstPixelSeed = 0;
        return o;
    }

    // ── MED predictor ─────────────────────────────────────────────────────

    private static int medPredict(int w, int n, int nw) {
        if (nw >= Math.max(w, n)) return Math.min(w, n);
        if (nw <= Math.min(w, n)) return Math.max(w, n);
        return w + n - nw;
    }

    private static String fmt(double v) {
        return Double.isFinite(v) ? String.format(Locale.ROOT, "%.2f", v) : "n/a";
    }

    private static String nanFmt(double v) {
        return Double.isFinite(v) ? String.format(Locale.ROOT, "%.2f", v) : "n/a";
    }
}
