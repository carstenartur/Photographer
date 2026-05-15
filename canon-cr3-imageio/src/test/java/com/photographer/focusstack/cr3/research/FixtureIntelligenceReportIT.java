package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.bmff.Cmp1Box;
import com.photographer.focusstack.cr3.bmff.Iad1Box;
import com.photographer.focusstack.cr3.bmff.PrvwBox;
import com.photographer.focusstack.cr3.bmff.ThmbBox;
import com.photographer.focusstack.cr3.container.CompressionMode;
import com.photographer.focusstack.cr3.container.Cr3Container;
import com.photographer.focusstack.cr3.container.Cr3Parser;
import com.photographer.focusstack.cr3.container.Cr3Track;
import com.photographer.focusstack.cr3.raw.codec.CrxHeader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Work‑package 4 — fixture intelligence tooling.
 *
 * <p>This integration test scans every CR3 file under {@code samples/} and
 * {@code samples/research/}, parses the container with the production BMFF
 * stack, and emits a single tab‑separated‑values report under
 * {@code target/fixture-intelligence/fixtures.tsv}.</p>
 *
 * <p>For each file it records (one row per file):</p>
 * <ol>
 *   <li>{@code path} — relative path of the CR3</li>
 *   <li>{@code camera} — model name from EXIF, falling back to the filename
 *       prefix (e.g. {@code g5xm2}, {@code eosr}) when EXIF is unavailable</li>
 *   <li>{@code compression} — {@code RAW_LOSSLESS} / {@code CRAW_LOSSY} /
 *       {@code UNKNOWN} as reported by {@link CompressionMode} on the CRX_BIG
 *       track</li>
 *   <li>{@code waveletLevels} — CMP1.waveletLevels (0 = lossless, 3 = C‑RAW)</li>
 *   <li>{@code quantValues} — sorted distinct {@code Subband.quantValue}s
 *       seen across the CRX_BIG payload</li>
 *   <li>{@code cfaLayout} — CMP1.cfaLayout integer + symbolic name</li>
 *   <li>{@code numPlanes} — CMP1.numPlanes</li>
 *   <li>{@code tilesPerFrame} / {@code subbandsPerFrame} — totals on CRX_BIG</li>
 *   <li>{@code obLeft / obTop} — width/height of the IAD1 left and top OB
 *       rectangles, in sensor pixels</li>
 *   <li>{@code crxBigDim / crxSmallDim / prvwDim / thmbDim} — pixel geometry
 *       of every track that exists in the file</li>
 *   <li>{@code burstGroup} — derived per‑file grouping key (camera + scene +
 *       exposure + runId) so adjacent frames that belong to the same body‑side
 *       burst end up with the same key</li>
 *   <li>{@code novelty} — a one‑word verdict comparing this row to all rows
 *       seen so far: {@code NEW} = brings a previously unseen
 *       {@code (compression, waveletLevels, quantValues, cfaLayout, numPlanes,
 *       tilesPerFrame)} tuple, otherwise {@code DUP} (duplicates an existing
 *       structural class) or {@code BURST} (member of an existing burst group
 *       that has been seen).</li>
 * </ol>
 *
 * <p>The IT does not modify any production code and must remain green even
 * if the corpus shrinks (it logs a skip and returns when no fixtures are
 * found).</p>
 */
class FixtureIntelligenceReportIT {

    private static final Logger log = LoggerFactory.getLogger(FixtureIntelligenceReportIT.class);

    private static final Path SAMPLES_DIR = Path.of("src", "test", "resources", "samples");
    private static final Path RESEARCH_DIR = SAMPLES_DIR.resolve("research");
    private static final Path REPORT_DIR = Path.of("target", "fixture-intelligence");

    @Test
    @DisplayName("Fixture-intelligence report is emitted for every CR3 in the corpus")
    void emitFixtureIntelligenceReport() throws Exception {
        if (!Files.isDirectory(SAMPLES_DIR)) {
            log.info("No samples directory at {} — skipping", SAMPLES_DIR.toAbsolutePath());
            return;
        }
        List<Path> all = new ArrayList<>();
        collectCr3(SAMPLES_DIR, all);
        if (Files.isDirectory(RESEARCH_DIR)) {
            collectCr3(RESEARCH_DIR, all);
        }
        all.sort((a, b) -> a.toString().compareTo(b.toString()));
        if (all.isEmpty()) {
            log.info("No CR3 fixtures found under {} — skipping", SAMPLES_DIR.toAbsolutePath());
            return;
        }

        Files.createDirectories(REPORT_DIR);
        Path tsv = REPORT_DIR.resolve("fixtures.tsv");
        Path md  = REPORT_DIR.resolve("fixtures.md");

        List<Row> rows = new ArrayList<>();
        for (Path p : all) {
            try {
                rows.add(scan(p));
            } catch (Exception e) {
                log.warn("Failed to scan {}: {}", p, e.toString());
                rows.add(Row.errorRow(p, e));
            }
        }

        // Novelty pass: a row is NEW if its (compression, waveletLevels, quantValues,
        // cfaLayout, numPlanes, tilesPerFrame) tuple has not been seen before; BURST
        // if its (camera, burstGroup) was first introduced by an earlier row that
        // already supplied a NEW or BURST verdict; DUP otherwise.
        Set<String> seenStructural = new TreeSet<>();
        Set<String> seenBurst = new TreeSet<>();
        for (Row r : rows) {
            if (r.error != null) { r.novelty = "ERROR"; continue; }
            String structural = String.join("|",
                    String.valueOf(r.compression),
                    String.valueOf(r.waveletLevels),
                    r.quantValues.toString(),
                    String.valueOf(r.cfaLayout),
                    String.valueOf(r.numPlanes),
                    String.valueOf(r.tilesPerFrame));
            if (seenStructural.add(structural)) {
                r.novelty = "NEW";
                seenBurst.add(r.burstGroup);
            } else if (!seenBurst.add(r.burstGroup)) {
                r.novelty = "BURST";
            } else {
                r.novelty = "DUP";
            }
        }

        // Write TSV
        try (var out = Files.newBufferedWriter(tsv)) {
            out.write(Row.tsvHeader());
            out.write('\n');
            for (Row r : rows) {
                out.write(r.toTsv());
                out.write('\n');
            }
        }
        // Write a small Markdown summary
        try (var out = Files.newBufferedWriter(md)) {
            out.write("# Fixture-intelligence report\n\n");
            out.write("Generated by `FixtureIntelligenceReportIT`. ");
            out.write(rows.size() + " CR3 files scanned.\n\n");
            // Novelty rollup
            Map<String, Long> byNovelty = rows.stream()
                    .collect(Collectors.groupingBy(r -> r.novelty == null ? "?" : r.novelty,
                            Collectors.counting()));
            out.write("## Novelty rollup\n\n");
            for (var e : byNovelty.entrySet()) {
                out.write("- **" + e.getKey() + "** — " + e.getValue() + "\n");
            }
            out.write("\n## Per-file rows\n\n");
            out.write("| file | camera | compression | wL | q | cfa | tiles | burst | novelty |\n");
            out.write("|---|---|---|---:|---|---|---:|---|---|\n");
            for (Row r : rows) {
                out.write("| " + r.path
                        + " | " + nz(r.camera)
                        + " | " + r.compression
                        + " | " + r.waveletLevels
                        + " | " + r.quantValues
                        + " | " + r.cfaLayoutName + "(" + r.cfaLayout + ")"
                        + " | " + r.tilesPerFrame
                        + " | " + r.burstGroup
                        + " | " + r.novelty
                        + " |\n");
            }
        }

        log.info("═══ Fixture-intelligence report ═══");
        log.info("  scanned {} files, wrote {}", rows.size(), tsv.toAbsolutePath());
        long news  = rows.stream().filter(r -> "NEW".equals(r.novelty)).count();
        long bursts = rows.stream().filter(r -> "BURST".equals(r.novelty)).count();
        long dups  = rows.stream().filter(r -> "DUP".equals(r.novelty)).count();
        long errs  = rows.stream().filter(r -> "ERROR".equals(r.novelty)).count();
        log.info("  novelty rollup — NEW={} BURST={} DUP={} ERROR={}", news, bursts, dups, errs);

        // Sanity-only assertion: the corpus must contain at least one row that
        // brought genuinely new structural information, otherwise our research
        // ITs are scanning a degenerate corpus.
        assertThat(news).as("at least one fixture brings new structural info").isPositive();
    }

    private static void collectCr3(Path dir, List<Path> out) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> Files.isRegularFile(p))
             .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".cr3"))
             .forEach(out::add);
        }
    }

    private static Row scan(Path file) throws Exception {
        Row r = new Row();
        r.path = SAMPLES_DIR.relativize(file).toString().replace('\\', '/');
        // Derive camera prefix from filename (works without EXIF).
        String name = file.getFileName().toString().toLowerCase();
        if (name.startsWith("g5xm2") || name.startsWith("sample")) {
            r.camera = "g5xm2";
        } else if (name.startsWith("eosr")) {
            r.camera = "eosr";
        } else {
            int dash = name.indexOf('-');
            r.camera = dash > 0 ? name.substring(0, dash) : "?";
        }

        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            Cr3Container c = Cr3Parser.parse(in);

            PrvwBox prvw = c.prvwBox();
            ThmbBox thmb = c.thmbBox();
            r.prvwDim = prvw == null ? "-" : prvw.width() + "x" + prvw.height();
            r.thmbDim = thmb == null ? "-" : thmb.width() + "x" + thmb.height();

            for (Cr3Track t : c.tracks()) {
                String dim = t.width() + "x" + t.height();
                switch (t.type()) {
                    case CRX_BIG, CRX_RAW, CRX_HDR_RAW -> r.crxBigDim = dim;
                    case CRX_SMALL -> r.crxSmallDim = dim;
                    default -> { /* ignore for the dim summary */ }
                }
            }

            // Find the RAW track to read CMP1, IAD1, CRX header.
            Cr3Track raw = c.tracks().stream()
                    .filter(t -> t.type() == Cr3Track.TrackType.CRX_BIG
                              || t.type() == Cr3Track.TrackType.CRX_RAW
                              || t.type() == Cr3Track.TrackType.CRX_HDR_RAW)
                    .findFirst().orElse(null);
            if (raw == null) {
                r.compression = CompressionMode.UNKNOWN.name();
                return r;
            }
            r.compression = String.valueOf(raw.compressionMode());

            Cmp1Box cmp1 = raw.cmp1();
            if (cmp1 != null) {
                r.waveletLevels = cmp1.waveletLevels();
                r.cfaLayout = cmp1.cfaLayout();
                r.cfaLayoutName = cmp1.cfaLayoutName();
                r.numPlanes = cmp1.numPlanes();
            }
            Iad1Box iad1 = raw.iad1();
            if (iad1 != null) {
                int leftObW = Math.max(0, iad1.leftObRight() - iad1.leftObLeft());
                int leftObH = Math.max(0, iad1.leftObBottom() - iad1.leftObTop());
                int topObW  = Math.max(0, iad1.topObRight()  - iad1.topObLeft());
                int topObH  = Math.max(0, iad1.topObBottom() - iad1.topObTop());
                r.obLeft = leftObW + "x" + leftObH;
                r.obTop  = topObW  + "x" + topObH;
            }

            // Pull the CRX header to enumerate per-subband quantValue.
            try {
                int prefixLen = (int) Math.min(raw.dataSize(), 8192L);
                byte[] head = new byte[prefixLen];
                in.seek(raw.dataOffset());
                in.readFully(head);
                CrxHeader.CodecVersion ver = c.cncvBox() == null
                        ? CrxHeader.CodecVersion.V1
                        : CrxHeader.detectVersion(c.cncvBox().versionString());
                CrxHeader hdr = CrxHeader.parse(head, ver);
                Set<Integer> qs = new TreeSet<>();
                int subbands = 0;
                int tiles = hdr.tiles().size();
                for (var t : hdr.tiles()) {
                    for (var pl : t.planes()) {
                        for (var sb : pl.subbands()) {
                            qs.add(sb.quantValue());
                            subbands++;
                        }
                    }
                }
                r.quantValues = qs;
                r.tilesPerFrame = tiles;
                r.subbandsPerFrame = subbands;
            } catch (Exception headerErr) {
                log.warn("CRX header parse failed for {}: {}", r.path, headerErr.toString());
            }
        }

        r.burstGroup = burstKey(file.getFileName().toString(), r.camera);
        return r;
    }

    /**
     * Burst‑key derivation — matches genuine body‑side bursts only, not
     * "files with similar names". A burst here means: same camera, same
     * scene tag, same exposure, same {@code runId} timestamp, differing only
     * in the trailing 2‑digit sequence number. Single‑shot fixtures (the
     * legacy {@code sample.cr3}, {@code sample-tele.cr3}, individual ISO
     * sweep frames in {@code eosr-iso-*}) get unique keys so they cannot
     * accidentally form a "burst" with an unrelated scene.
     */
    private static String burstKey(String name, String camera) {
        String base = name.toLowerCase().replaceFirst("\\.cr3$", "");
        // Cleanroom capture files are the only true bursts in the corpus.
        // Pattern: g5xm2-<scene>-...-<8-digit-date>-<6-digit-time>-<seq>.cr3
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(.*)-(\\d{8}-\\d{6})-(\\d{2})$").matcher(base);
        if (m.matches()) {
            return m.group(1) + "|" + m.group(2);
        }
        // Anything without the runId/seq tail is treated as an isolated frame.
        return camera + "|" + base + "|solo";
    }

    private static String nz(String s) { return s == null ? "-" : s; }

    /** One row of the report. */
    static final class Row {
        String path = "?";
        String camera = "?";
        String compression = "UNKNOWN";
        int waveletLevels = -1;
        Set<Integer> quantValues = java.util.Collections.emptySet();
        int cfaLayout = -1;
        String cfaLayoutName = "?";
        int numPlanes = -1;
        int tilesPerFrame = -1;
        int subbandsPerFrame = -1;
        String obLeft = "-";
        String obTop = "-";
        String crxBigDim = "-";
        String crxSmallDim = "-";
        String prvwDim = "-";
        String thmbDim = "-";
        String burstGroup = "-";
        String novelty;
        String error;

        static Row errorRow(Path p, Exception e) {
            Row r = new Row();
            r.path = SAMPLES_DIR.relativize(p).toString().replace('\\', '/');
            r.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            return r;
        }

        static String tsvHeader() {
            return String.join("\t",
                    "path", "camera", "compression", "waveletLevels", "quantValues",
                    "cfaLayout", "cfaLayoutName", "numPlanes",
                    "tilesPerFrame", "subbandsPerFrame",
                    "obLeft", "obTop",
                    "crxBigDim", "crxSmallDim", "prvwDim", "thmbDim",
                    "burstGroup", "novelty", "error");
        }

        String toTsv() {
            return String.join("\t",
                    path, camera, compression,
                    String.valueOf(waveletLevels),
                    quantValues.toString(),
                    String.valueOf(cfaLayout), cfaLayoutName,
                    String.valueOf(numPlanes),
                    String.valueOf(tilesPerFrame), String.valueOf(subbandsPerFrame),
                    obLeft, obTop,
                    crxBigDim, crxSmallDim, prvwDim, thmbDim,
                    burstGroup, String.valueOf(novelty), String.valueOf(error));
        }
    }
}
