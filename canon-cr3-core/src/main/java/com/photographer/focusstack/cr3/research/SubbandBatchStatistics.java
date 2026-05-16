package com.photographer.focusstack.cr3.research;

import com.photographer.focusstack.cr3.raw.codec.SubbandExtractor;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Batch statistics for CRX entropy-coded subband payloads.
 *
 * <p>The class is deliberately independent from the CLI so future diagnostic
 * harnesses, integration tests, or UI code can reuse the same tabular analysis
 * without shelling out to {@code cr3-cli}. It performs no decoding: it only
 * extracts subband payload bytes and applies {@link SubbandStatistics}.</p>
 */
public final class SubbandBatchStatistics {

    private SubbandBatchStatistics() {
        // utility class
    }

    /** One TSV-ready statistics row for one subband of one source file. */
    public record Row(
            String sourceFile,
            int sequence,
            int tileSequence,
            int planeIndex,
            int subbandIndex,
            long dataOffsetInFile,
            long dataSize,
            int quantValue,
            int roundedBits,
            int trackWidth,
            int trackHeight,
            int bitDepth,
            int tileWidth,
            int tileHeight,
            int planeWidth,
            int planeHeight,
            int waveletLevels,
            int encodingType,
            String codecVersion,
            String cncvVersion,
            long totalBits,
            long oneBits,
            double oneBitFraction,
            int longestZeroRun,
            int topByte0,
            long topByte0Count,
            int topByte1,
            long topByte1Count,
            int topByte2,
            long topByte2Count,
            long zeroRun0,
            long zeroRun1,
            long zeroRun2,
            long zeroRun3,
            long zeroRun4To15,
            long zeroRun16Plus) {
    }

    /**
     * Computes one statistics row for every subband in every input CR3 file.
     *
     * @param cr3Files files to scan, in output order
     * @return statistics rows in file/subband sequence order
     * @throws IOException if a CR3 file cannot be read
     */
    public static List<Row> analyze(List<File> cr3Files) throws IOException {
        if (cr3Files == null) {
            throw new IllegalArgumentException("cr3Files must not be null");
        }
        List<Row> rows = new ArrayList<>();
        for (File cr3 : cr3Files) {
            List<SubbandExtractor.SubbandRef> refs = SubbandExtractor.list(cr3);
            for (int seq = 0; seq < refs.size(); seq++) {
                SubbandExtractor.SubbandRef ref = refs.get(seq);
                byte[] bytes = SubbandExtractor.read(cr3, ref);
                rows.add(from(cr3.getPath(), seq, ref, SubbandStatistics.compute(bytes)));
            }
        }
        return List.copyOf(rows);
    }

    /** Builds a row from an already-computed statistic block. Useful for tests and non-CR3 callers. */
    public static Row from(String sourceFile, int sequence, SubbandExtractor.SubbandRef ref, SubbandStatistics stats) {
        if (ref == null) {
            throw new IllegalArgumentException("ref must not be null");
        }
        if (stats == null) {
            throw new IllegalArgumentException("stats must not be null");
        }
        long[] byteHist = stats.byteHistogram();
        int[] top = topBytes(byteHist, 3);
        long[] zeroRuns = stats.zeroRunHistogram();
        long zero4To15 = 0;
        for (int i = 4; i <= 15 && i < zeroRuns.length; i++) {
            zero4To15 += zeroRuns[i];
        }
        long zero16Plus = 0;
        for (int i = 16; i < zeroRuns.length; i++) {
            zero16Plus += zeroRuns[i];
        }
        return new Row(
                sourceFile,
                sequence,
                ref.tileSequence(),
                ref.planeIndex(),
                ref.subbandIndex(),
                ref.dataOffsetInFile(),
                ref.dataSize(),
                ref.quantValue(),
                ref.roundedBits(),
                ref.trackWidth(),
                ref.trackHeight(),
                ref.bitDepth(),
                ref.tileWidth(),
                ref.tileHeight(),
                ref.planeWidth(),
                ref.planeHeight(),
                ref.waveletLevels(),
                ref.encodingType(),
                ref.codecVersion().name(),
                ref.cncvVersion(),
                stats.totalBits(),
                stats.oneBits(),
                stats.oneBitFraction(),
                stats.longestZeroRun(),
                top[0], byteHist[top[0]],
                top[1], byteHist[top[1]],
                top[2], byteHist[top[2]],
                zeroRuns.length > 0 ? zeroRuns[0] : 0,
                zeroRuns.length > 1 ? zeroRuns[1] : 0,
                zeroRuns.length > 2 ? zeroRuns[2] : 0,
                zeroRuns.length > 3 ? zeroRuns[3] : 0,
                zero4To15,
                zero16Plus);
    }

    /** TSV header for {@link #toTsv(List)}. */
    public static String tsvHeader() {
        return String.join("\t",
                "sourceFile", "sequence", "tileSequence", "planeIndex", "subbandIndex",
                "dataOffsetInFile", "dataSize", "quantValue", "roundedBits",
                "trackWidth", "trackHeight", "bitDepth", "tileWidth", "tileHeight",
                "planeWidth", "planeHeight", "waveletLevels", "encodingType",
                "codecVersion", "cncvVersion", "totalBits", "oneBits", "oneBitFraction",
                "longestZeroRun", "topByte0", "topByte0Count", "topByte1", "topByte1Count",
                "topByte2", "topByte2Count", "zeroRun0", "zeroRun1", "zeroRun2",
                "zeroRun3", "zeroRun4To15", "zeroRun16Plus");
    }

    /** Renders rows as tab-separated values with a header row and trailing newline. */
    public static String toTsv(List<Row> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(tsvHeader()).append('\n');
        for (Row r : rows) {
            appendRow(sb, r).append('\n');
        }
        return sb.toString();
    }

    private static StringBuilder appendRow(StringBuilder sb, Row r) {
        return sb.append(tsv(r.sourceFile())).append('\t')
                .append(r.sequence()).append('\t')
                .append(r.tileSequence()).append('\t')
                .append(r.planeIndex()).append('\t')
                .append(r.subbandIndex()).append('\t')
                .append(r.dataOffsetInFile()).append('\t')
                .append(r.dataSize()).append('\t')
                .append(r.quantValue()).append('\t')
                .append(r.roundedBits()).append('\t')
                .append(r.trackWidth()).append('\t')
                .append(r.trackHeight()).append('\t')
                .append(r.bitDepth()).append('\t')
                .append(r.tileWidth()).append('\t')
                .append(r.tileHeight()).append('\t')
                .append(r.planeWidth()).append('\t')
                .append(r.planeHeight()).append('\t')
                .append(r.waveletLevels()).append('\t')
                .append(r.encodingType()).append('\t')
                .append(r.codecVersion()).append('\t')
                .append(tsv(r.cncvVersion())).append('\t')
                .append(r.totalBits()).append('\t')
                .append(r.oneBits()).append('\t')
                .append(String.format(Locale.ROOT, "%.6f", r.oneBitFraction())).append('\t')
                .append(r.longestZeroRun()).append('\t')
                .append(formatByte(r.topByte0())).append('\t')
                .append(r.topByte0Count()).append('\t')
                .append(formatByte(r.topByte1())).append('\t')
                .append(r.topByte1Count()).append('\t')
                .append(formatByte(r.topByte2())).append('\t')
                .append(r.topByte2Count()).append('\t')
                .append(r.zeroRun0()).append('\t')
                .append(r.zeroRun1()).append('\t')
                .append(r.zeroRun2()).append('\t')
                .append(r.zeroRun3()).append('\t')
                .append(r.zeroRun4To15()).append('\t')
                .append(r.zeroRun16Plus());
    }

    private static int[] topBytes(long[] hist, int count) {
        return java.util.stream.IntStream.range(0, hist.length)
                .boxed()
                .sorted(Comparator.<Integer>comparingLong(i -> hist[i]).reversed().thenComparingInt(i -> i))
                .limit(count)
                .mapToInt(Integer::intValue)
                .toArray();
    }

    private static String formatByte(int value) {
        return String.format(Locale.ROOT, "0x%02X", value & 0xFF);
    }

    private static String tsv(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }
}
