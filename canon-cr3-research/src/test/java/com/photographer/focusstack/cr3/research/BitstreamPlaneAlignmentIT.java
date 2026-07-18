package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.bmff.Cmp1Box;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Phase A — Work-package 6: bitstream / plane alignment diagnostic harness.
 *
 * <p>This IT exists to isolate the suspected low-level break point in the
 * decoder without launching another broad parameter sweep. It does not
 * decode anything — it only emits, per (file, tile, plane), the structural
 * properties of the encoded bitstream:
 * <ul>
 *   <li><b>Entry point</b> — absolute byte offset of the plane's first
 *       subband inside the original {@code mdat}, plus the relative offset
 *       inside the track payload.</li>
 *   <li><b>Start state</b> — the first 16 bytes of the plane's first
 *       subband, hex-encoded. Two adjacent tiles whose first 16 bytes are
 *       identical strongly suggest a shared start state; two tiles whose
 *       first bytes differ structurally are a clue for per-tile state
 *       initialisation.</li>
 *   <li><b>Sizes</b> — per-plane and per-subband encoded byte counts and
 *       bits per pixel (against the plane's CMP1-implied pixel count).</li>
 *   <li><b>Relational position</b> — distance, in bytes, between successive
 *       subbands of the same plane and across plane boundaries; whether
 *       the next plane starts at a 4 / 8 / 16-byte aligned boundary.</li>
 *   <li><b>Tile-vs-tile delta</b> — for each plane index, the byte-by-byte
 *       difference between tile 0 and tile 1 in their first 16 bytes (a
 *       cleanroom-safe summary: how many of those 16 bytes match).</li>
 * </ul>
 *
 * <p>This is exactly the data needed to argue or refute very narrow
 * alignment hypotheses (e.g. "plane 1 of tile 1 starts on a non-4-byte
 * boundary that the bit reader does not honour") <em>without</em> kicking
 * off a parametric scan over 50 candidate offsets. If the data points at a
 * specific offset disagreement, a tightly scoped follow-up IT (≤ 16
 * candidates) is appropriate; if the data shows no anomaly, the alignment
 * hypothesis is parked.</p>
 *
 * <p>The IT writes {@code target/bitstream-alignment/alignment.tsv}.
 * Because it is structural-only it works on every CR3 in the corpus,
 * including C-RAW.</p>
 */
class BitstreamPlaneAlignmentIT {

    private static final Logger log = LoggerFactory.getLogger(BitstreamPlaneAlignmentIT.class);

    private static final Path SAMPLES_DIR  = Path.of("src", "test", "resources", "samples");
    private static final Path RESEARCH_DIR = SAMPLES_DIR.resolve("research");
    private static final Path REPORT_DIR   = Path.of("target", "bitstream-alignment");

    @Test
    @DisplayName("Per (file, tile, plane) bitstream entry / size / first-bytes / alignment")
    void emitAlignmentReport() throws Exception {
        if (!Files.isDirectory(SAMPLES_DIR)) {
            log.info("No samples dir at {} — skipping", SAMPLES_DIR.toAbsolutePath());
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

        Files.createDirectories(REPORT_DIR);
        Path tsv = REPORT_DIR.resolve("alignment.tsv");
        try (var out = Files.newBufferedWriter(tsv)) {
            out.write("file\ttileSeq\ttileIdx\tplaneIdx\tsubbandIdx\t"
                    + "absOffset\trelOffset\talignMod4\talignMod8\talignMod16\t"
                    + "encodedBytes\tquantValue\tfirst16Hex\tplaneEncodedBytes\t"
                    + "tileEncodedBytes\theaderSize\ttotalTrackBytes\n");
            for (Path file : all) {
                try {
                    analyse(file, out);
                } catch (Exception ex) {
                    log.warn("Skipped {}: {}", file.getFileName(), ex.toString());
                }
            }
        }
        log.info("Wrote {}", tsv.toAbsolutePath());
    }

    // ---------- helpers ----------

    private static void collect(Path dir, List<Path> out) throws java.io.IOException {
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isRegularFile)
             .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".cr3"))
             .forEach(out::add);
        }
    }

    private static void analyse(Path file, java.io.BufferedWriter out) throws Exception {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            Cr3Container c = Cr3Parser.parse(in);
            Cr3Track raw = c.tracks().stream()
                    .filter(t -> t.type() == Cr3Track.TrackType.CRX_BIG
                              || t.type() == Cr3Track.TrackType.CRX_RAW
                              || t.type() == Cr3Track.TrackType.CRX_HDR_RAW)
                    .findFirst().orElse(null);
            if (raw == null) {
                log.info("  {}: no CRX_BIG/CRX_RAW track — skipping", file.getFileName());
                return;
            }
            long trackOffset = raw.dataOffset();
            long trackSize = raw.dataSize();
            byte[] bytes = new byte[(int) Math.min(trackSize, Integer.MAX_VALUE)];
            in.seek(trackOffset);
            in.readFully(bytes);
            CrxHeader.CodecVersion ver = c.cncvBox() == null
                    ? CrxHeader.CodecVersion.V1
                    : CrxHeader.detectVersion(c.cncvBox().versionString());
            CrxHeader hdr = CrxHeader.parse(bytes, ver);

            int tileSeq = 0;
            for (CrxHeader.Tile t : hdr.tiles()) {
                int planeIdx = 0;
                for (CrxHeader.Plane pl : t.planes()) {
                    int sbIdx = 0;
                    for (CrxHeader.Subband sb : pl.subbands()) {
                        long abs = trackOffset + sb.dataOffset();
                        long rel = sb.dataOffset();
                        String first = first16Hex(bytes, (int) sb.dataOffset(), (int) sb.dataSize());
                        out.write(String.format(
                                "%s\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%s\t%d\t%d\t%d\t%d%n",
                                SAMPLES_DIR.relativize(file),
                                tileSeq, t.index(), pl.index(), sb.index(),
                                abs, rel,
                                rel & 3, rel & 7, rel & 15,
                                sb.dataSize(), sb.quantValue(),
                                first,
                                pl.dataSize(),
                                t.dataSize(),
                                hdr.headerSize(),
                                trackSize));
                        sbIdx++;
                    }
                    planeIdx++;
                }
                // Cross-tile delta for the leading plane of this tile vs tile 0
                if (tileSeq > 0 && !hdr.tiles().isEmpty() && !t.planes().isEmpty()
                        && !hdr.tiles().get(0).planes().isEmpty()) {
                    CrxHeader.Plane t0p0 = hdr.tiles().get(0).planes().get(0);
                    CrxHeader.Plane tNp0 = t.planes().get(0);
                    if (!t0p0.subbands().isEmpty() && !tNp0.subbands().isEmpty()) {
                        int match = matchingPrefix(bytes,
                                (int) t0p0.subbands().get(0).dataOffset(),
                                (int) tNp0.subbands().get(0).dataOffset(), 16);
                        log.info("  {}: tile{} plane0 subband0 first16 matches tile0 plane0 subband0 in {} / 16 bytes",
                                file.getFileName(), tileSeq, match);
                    }
                }
                tileSeq++;
            }
            // Compact log line summarising structure
            Cmp1Box cmp1 = raw.cmp1();
            log.info("  {}: tiles={} cmp1.numPlanes={} wL={} headerSize={} trackBytes={}",
                    file.getFileName(), hdr.tiles().size(),
                    cmp1 != null ? cmp1.numPlanes() : -1,
                    cmp1 != null ? cmp1.waveletLevels() : -1,
                    hdr.headerSize(), trackSize);
        }
    }

    private static String first16Hex(byte[] data, int offset, int size) {
        int n = Math.min(16, Math.min(size, Math.max(0, data.length - offset)));
        if (n <= 0) return "-";
        StringBuilder sb = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) sb.append(String.format("%02x", data[offset + i] & 0xff));
        return sb.toString();
    }

    private static int matchingPrefix(byte[] data, int offA, int offB, int max) {
        int n = Math.min(max, Math.min(Math.max(0, data.length - offA),
                                       Math.max(0, data.length - offB)));
        int match = 0;
        for (int i = 0; i < n; i++) {
            if (data[offA + i] == data[offB + i]) match++;
            else break;
        }
        return match;
    }
}
