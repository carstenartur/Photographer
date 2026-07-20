package com.photographer.focusstack.cr3.research;

import java.util.List;
import java.util.Locale;

final class CrxSubbandTopologyReport {
    record Row(String fixture,
               String family,
               int recordOrder,
               int tile,
               int plane,
               int waveletLevels,
               int subband,
               int width,
               int height,
               int bitDepth,
               int payloadBytes,
               String dimensionParentCandidates,
               String causalParentCandidates,
               String boundaryFlags) {
    }

    record Summary(int fixtures,
                   int rows,
                   int dimensionCandidates,
                   int causalCandidates,
                   int crossFamilySignatures,
                   int contradictorySignatures) {
    }

    private CrxSubbandTopologyReport() {
    }

    static Summary summary(List<Row> rows) {
        int candidates = 0;
        int causal = 0;
        for (Row row : rows) {
            candidates += count(row.dimensionParentCandidates());
            causal += count(row.causalParentCandidates());
        }
        long fixtures = rows.stream().map(Row::fixture).distinct().count();
        long signatures = rows.stream()
                .map(CrxSubbandTopologyReport::signature)
                .distinct().count();
        long contradictory = rows.stream()
                .collect(java.util.stream.Collectors.groupingBy(Row::family))
                .values().stream()
                .map(group -> group.stream().map(CrxSubbandTopologyReport::signature)
                        .collect(java.util.stream.Collectors.toSet()))
                .mapToLong(java.util.Set::size)
                .max().orElse(0L) - signatures;
        return new Summary((int) fixtures, rows.size(), candidates, causal,
                (int) signatures, (int) Math.max(0L, contradictory));
    }

    static String markdown(List<Row> rows) {
        Summary summary = summary(rows);
        StringBuilder out = new StringBuilder(
                "# D13 CRX Subband Topology Inventory\n\n")
                .append("Observation only. No candidate is selected from output quality. ")
                .append("Payload offsets are reported as unavailable when the clean-room ")
                .append("extractor exposes only the extracted byte array.\n\n")
                .append("## Summary\n\n")
                .append("- fixtures: ").append(summary.fixtures()).append('\n')
                .append("- records: ").append(summary.rows()).append('\n')
                .append("- dimension-compatible parent candidates: ")
                .append(summary.dimensionCandidates()).append('\n')
                .append("- candidates available earlier in record order: ")
                .append(summary.causalCandidates()).append('\n')
                .append("- distinct structural signatures: ")
                .append(summary.crossFamilySignatures()).append("\n\n")
                .append("## Records\n\n")
                .append("| Fixture | Family | Order | Tile | Plane | Levels | Subband | Width | Height | Bits | Payload bytes | Dimension candidates | Causal candidates | Boundaries |\n")
                .append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|---|\n");
        for (Row row : rows) {
            out.append("| `").append(row.fixture()).append("` | ")
                    .append(row.family()).append(" | ")
                    .append(row.recordOrder()).append(" | ")
                    .append(row.tile()).append(" | ")
                    .append(row.plane()).append(" | ")
                    .append(row.waveletLevels()).append(" | ")
                    .append(row.subband()).append(" | ")
                    .append(row.width()).append(" | ")
                    .append(row.height()).append(" | ")
                    .append(row.bitDepth()).append(" | ")
                    .append(row.payloadBytes()).append(" | ")
                    .append(empty(row.dimensionParentCandidates())).append(" | ")
                    .append(empty(row.causalParentCandidates())).append(" | ")
                    .append(empty(row.boundaryFlags())).append(" |\n");
        }
        return out.toString();
    }

    static String tsv(List<Row> rows) {
        StringBuilder out = new StringBuilder(
                "fixture\tfamily\trecordOrder\ttile\tplane\twaveletLevels\tsubband\twidth\theight\tbitDepth\tpayloadBytes\tpayloadOffset\tdimensionParentCandidates\tcausalParentCandidates\tboundaryFlags\n");
        for (Row row : rows) {
            out.append(row.fixture()).append('\t')
                    .append(row.family()).append('\t')
                    .append(row.recordOrder()).append('\t')
                    .append(row.tile()).append('\t')
                    .append(row.plane()).append('\t')
                    .append(row.waveletLevels()).append('\t')
                    .append(row.subband()).append('\t')
                    .append(row.width()).append('\t')
                    .append(row.height()).append('\t')
                    .append(row.bitDepth()).append('\t')
                    .append(row.payloadBytes()).append('\t')
                    .append("NOT_EXPOSED").append('\t')
                    .append(row.dimensionParentCandidates()).append('\t')
                    .append(row.causalParentCandidates()).append('\t')
                    .append(row.boundaryFlags()).append('\n');
        }
        return out.toString();
    }

    static String json(List<Row> rows) {
        Summary summary = summary(rows);
        StringBuilder out = new StringBuilder("{\n")
                .append("  \"fixtures\": ").append(summary.fixtures()).append(",\n")
                .append("  \"records\": ").append(summary.rows()).append(",\n")
                .append("  \"dimensionCandidates\": ")
                .append(summary.dimensionCandidates()).append(",\n")
                .append("  \"causalCandidates\": ")
                .append(summary.causalCandidates()).append(",\n")
                .append("  \"rows\": [\n");
        for (int index = 0; index < rows.size(); index++) {
            if (index > 0) out.append(",\n");
            Row row = rows.get(index);
            out.append("    {\"fixture\":\"").append(escape(row.fixture()))
                    .append("\",\"family\":\"").append(row.family())
                    .append("\",\"recordOrder\":").append(row.recordOrder())
                    .append(",\"tile\":").append(row.tile())
                    .append(",\"plane\":").append(row.plane())
                    .append(",\"waveletLevels\":").append(row.waveletLevels())
                    .append(",\"subband\":").append(row.subband())
                    .append(",\"width\":").append(row.width())
                    .append(",\"height\":").append(row.height())
                    .append(",\"bitDepth\":").append(row.bitDepth())
                    .append(",\"payloadBytes\":").append(row.payloadBytes())
                    .append(",\"payloadOffset\":null")
                    .append(",\"dimensionParentCandidates\":\"")
                    .append(escape(row.dimensionParentCandidates()))
                    .append("\",\"causalParentCandidates\":\"")
                    .append(escape(row.causalParentCandidates()))
                    .append("\",\"boundaryFlags\":\"")
                    .append(escape(row.boundaryFlags())).append("\"}");
        }
        return out.append("\n  ]\n}\n").toString();
    }

    private static int count(String candidates) {
        return candidates.isBlank() ? 0 : candidates.split(",", -1).length;
    }

    private static String signature(Row row) {
        return String.format(Locale.ROOT, "%d:%d:%d:%dx%d:%d",
                row.plane(), row.waveletLevels(), row.subband(),
                row.width(), row.height(), row.bitDepth());
    }

    private static String empty(String value) {
        return value.isBlank() ? "—" : value;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
