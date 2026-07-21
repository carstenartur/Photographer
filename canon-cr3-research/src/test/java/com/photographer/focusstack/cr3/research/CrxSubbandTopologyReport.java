package com.photographer.focusstack.cr3.research;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

final class CrxSubbandTopologyReport {
    record Row(String fixture,
               String family,
               int recordOrder,
               int tileIndex,
               int tileSequence,
               int plane,
               int declaredWaveletLevels,
               int subband,
               int width,
               int height,
               int bitDepth,
               long payloadOffset,
               long payloadSize,
               long payloadEndExclusive,
               String dimensionParentCandidates,
               String causalAssessment,
               String continuity,
               String boundaryFlags) {
    }

    record FamilySummary(String family, int fixtures, int topologyVariants) {
    }

    record Summary(int fixtures,
                   int rows,
                   long totalPayloadBytes,
                   int losslessSingleSubbandRecords,
                   int unresolvedParentRecords,
                   int contiguousTransitions,
                   int gaps,
                   int overlaps,
                   int distinctTopologyVariants,
                   int familiesWithInternalVariation,
                   boolean crossFamilyUniform,
                   List<FamilySummary> families) {
    }

    private CrxSubbandTopologyReport() {
    }

    static Summary summary(List<Row> rows) {
        Map<String, List<Row>> byFixture = rows.stream()
                .collect(Collectors.groupingBy(Row::fixture,
                        LinkedHashMap::new, Collectors.toList()));
        Map<String, String> fixtureSignatures = new LinkedHashMap<>();
        for (Map.Entry<String, List<Row>> entry : byFixture.entrySet()) {
            fixtureSignatures.put(entry.getKey(), fixtureSignature(entry.getValue()));
        }

        Map<String, List<String>> fixturesByFamily = rows.stream()
                .collect(Collectors.groupingBy(Row::family,
                        LinkedHashMap::new,
                        Collectors.mapping(Row::fixture,
                                Collectors.collectingAndThen(
                                        Collectors.toCollection(java.util.LinkedHashSet::new),
                                        ArrayList::new))));
        List<FamilySummary> families = fixturesByFamily.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new FamilySummary(
                        entry.getKey(),
                        entry.getValue().size(),
                        (int) entry.getValue().stream()
                                .map(fixtureSignatures::get)
                                .distinct().count()))
                .toList();

        int distinctVariants = (int) fixtureSignatures.values().stream()
                .distinct().count();
        int internalVariation = (int) families.stream()
                .filter(family -> family.topologyVariants() > 1)
                .count();
        return new Summary(
                byFixture.size(),
                rows.size(),
                rows.stream().mapToLong(Row::payloadSize).sum(),
                (int) rows.stream()
                        .filter(row -> row.declaredWaveletLevels() == 0
                                && row.subband() == 0)
                        .count(),
                (int) rows.stream()
                        .filter(row -> row.causalAssessment().startsWith("UNRESOLVED"))
                        .count(),
                (int) rows.stream()
                        .filter(row -> row.continuity().equals("CONTIGUOUS_WITH_PREVIOUS"))
                        .count(),
                (int) rows.stream()
                        .filter(row -> row.continuity().startsWith("GAP_FROM_PREVIOUS_"))
                        .count(),
                (int) rows.stream()
                        .filter(row -> row.continuity().startsWith("OVERLAP_WITH_PREVIOUS_"))
                        .count(),
                distinctVariants,
                internalVariation,
                distinctVariants == 1,
                families);
    }

    static String markdown(List<Row> rows) {
        Summary summary = summary(rows);
        StringBuilder out = new StringBuilder(
                "# D13 CRX Subband Topology Inventory\n\n")
                .append("Observation only. No decoder candidate or subband role is selected ")
                .append("from image quality. `declaredWaveletLevels` is the CMP1-wide ")
                .append("decomposition count, not an inferred per-record level.\n\n")
                .append("## Summary\n\n")
                .append("- fixtures: ").append(summary.fixtures()).append('\n')
                .append("- records: ").append(summary.rows()).append('\n')
                .append("- total extracted payload bytes: ")
                .append(summary.totalPayloadBytes()).append('\n')
                .append("- lossless single-subband records: ")
                .append(summary.losslessSingleSubbandRecords()).append('\n')
                .append("- records with unresolved parent/context mapping: ")
                .append(summary.unresolvedParentRecords()).append('\n')
                .append("- contiguous transitions / gaps / overlaps: ")
                .append(summary.contiguousTransitions()).append(" / ")
                .append(summary.gaps()).append(" / ")
                .append(summary.overlaps()).append('\n')
                .append("- distinct fixture topology variants: ")
                .append(summary.distinctTopologyVariants()).append('\n')
                .append("- families with internal topology variation: ")
                .append(summary.familiesWithInternalVariation()).append('\n')
                .append("- one topology shared by every fixture: ")
                .append(summary.crossFamilyUniform()).append("\n\n")
                .append("## Camera-family topology variants\n\n")
                .append("| Family | Fixtures | Distinct topology variants |\n")
                .append("|---|---:|---:|\n");
        for (FamilySummary family : summary.families()) {
            out.append("| ").append(family.family())
                    .append(" | ").append(family.fixtures())
                    .append(" | ").append(family.topologyVariants())
                    .append(" |\n");
        }

        out.append("\n## Records\n\n")
                .append("| Fixture | Family | Order | Tile idx/seq | Plane | Declared levels | Subband | Grid | Bits | Payload range | Parent candidates | Causal assessment | Continuity | Boundaries |\n")
                .append("|---|---|---:|---|---:|---:|---:|---:|---:|---|---|---|---|---|\n");
        for (Row row : ordered(rows)) {
            out.append("| `").append(row.fixture()).append("` | ")
                    .append(row.family()).append(" | ")
                    .append(row.recordOrder()).append(" | ")
                    .append(row.tileIndex()).append('/').append(row.tileSequence())
                    .append(" | ").append(row.plane())
                    .append(" | ").append(row.declaredWaveletLevels())
                    .append(" | ").append(row.subband())
                    .append(" | ").append(row.width()).append('×').append(row.height())
                    .append(" | ").append(row.bitDepth())
                    .append(" | `").append(row.payloadOffset()).append("..")
                    .append(row.payloadEndExclusive()).append("` (")
                    .append(row.payloadSize()).append(")")
                    .append(" | ").append(empty(row.dimensionParentCandidates()))
                    .append(" | ").append(row.causalAssessment())
                    .append(" | ").append(row.continuity())
                    .append(" | ").append(empty(row.boundaryFlags()))
                    .append(" |\n");
        }
        return out.toString();
    }

    static String tsv(List<Row> rows) {
        StringBuilder out = new StringBuilder(
                "fixture\tfamily\trecordOrder\ttileIndex\ttileSequence\tplane\tdeclaredWaveletLevels\tsubband\twidth\theight\tbitDepth\tpayloadOffset\tpayloadSize\tpayloadEndExclusive\tdimensionParentCandidates\tcausalAssessment\tcontinuity\tboundaryFlags\n");
        for (Row row : ordered(rows)) {
            out.append(row.fixture()).append('\t')
                    .append(row.family()).append('\t')
                    .append(row.recordOrder()).append('\t')
                    .append(row.tileIndex()).append('\t')
                    .append(row.tileSequence()).append('\t')
                    .append(row.plane()).append('\t')
                    .append(row.declaredWaveletLevels()).append('\t')
                    .append(row.subband()).append('\t')
                    .append(row.width()).append('\t')
                    .append(row.height()).append('\t')
                    .append(row.bitDepth()).append('\t')
                    .append(row.payloadOffset()).append('\t')
                    .append(row.payloadSize()).append('\t')
                    .append(row.payloadEndExclusive()).append('\t')
                    .append(row.dimensionParentCandidates()).append('\t')
                    .append(row.causalAssessment()).append('\t')
                    .append(row.continuity()).append('\t')
                    .append(row.boundaryFlags()).append('\n');
        }
        return out.toString();
    }

    static String json(List<Row> rows) {
        Summary summary = summary(rows);
        StringBuilder out = new StringBuilder("{\n")
                .append("  \"fixtures\": ").append(summary.fixtures()).append(",\n")
                .append("  \"records\": ").append(summary.rows()).append(",\n")
                .append("  \"totalPayloadBytes\": ").append(summary.totalPayloadBytes()).append(",\n")
                .append("  \"losslessSingleSubbandRecords\": ")
                .append(summary.losslessSingleSubbandRecords()).append(",\n")
                .append("  \"unresolvedParentRecords\": ")
                .append(summary.unresolvedParentRecords()).append(",\n")
                .append("  \"contiguousTransitions\": ")
                .append(summary.contiguousTransitions()).append(",\n")
                .append("  \"gaps\": ").append(summary.gaps()).append(",\n")
                .append("  \"overlaps\": ").append(summary.overlaps()).append(",\n")
                .append("  \"distinctTopologyVariants\": ")
                .append(summary.distinctTopologyVariants()).append(",\n")
                .append("  \"familiesWithInternalVariation\": ")
                .append(summary.familiesWithInternalVariation()).append(",\n")
                .append("  \"crossFamilyUniform\": ")
                .append(summary.crossFamilyUniform()).append(",\n")
                .append("  \"families\": [");
        for (int index = 0; index < summary.families().size(); index++) {
            if (index > 0) out.append(',');
            FamilySummary family = summary.families().get(index);
            out.append("{\"family\":\"").append(escape(family.family()))
                    .append("\",\"fixtures\":").append(family.fixtures())
                    .append(",\"topologyVariants\":")
                    .append(family.topologyVariants()).append('}');
        }
        out.append("],\n  \"rows\": [\n");
        List<Row> ordered = ordered(rows);
        for (int index = 0; index < ordered.size(); index++) {
            if (index > 0) out.append(",\n");
            Row row = ordered.get(index);
            out.append("    {\"fixture\":\"").append(escape(row.fixture()))
                    .append("\",\"family\":\"").append(escape(row.family()))
                    .append("\",\"recordOrder\":").append(row.recordOrder())
                    .append(",\"tileIndex\":").append(row.tileIndex())
                    .append(",\"tileSequence\":").append(row.tileSequence())
                    .append(",\"plane\":").append(row.plane())
                    .append(",\"declaredWaveletLevels\":")
                    .append(row.declaredWaveletLevels())
                    .append(",\"subband\":").append(row.subband())
                    .append(",\"width\":").append(row.width())
                    .append(",\"height\":").append(row.height())
                    .append(",\"bitDepth\":").append(row.bitDepth())
                    .append(",\"payloadOffset\":").append(row.payloadOffset())
                    .append(",\"payloadSize\":").append(row.payloadSize())
                    .append(",\"payloadEndExclusive\":")
                    .append(row.payloadEndExclusive())
                    .append(",\"dimensionParentCandidates\":\"")
                    .append(escape(row.dimensionParentCandidates()))
                    .append("\",\"causalAssessment\":\"")
                    .append(escape(row.causalAssessment()))
                    .append("\",\"continuity\":\"")
                    .append(escape(row.continuity()))
                    .append("\",\"boundaryFlags\":\"")
                    .append(escape(row.boundaryFlags())).append("\"}");
        }
        return out.append("\n  ]\n}\n").toString();
    }

    private static List<Row> ordered(List<Row> rows) {
        return rows.stream()
                .sorted(Comparator.comparing(Row::fixture)
                        .thenComparingInt(Row::recordOrder))
                .toList();
    }

    private static String fixtureSignature(List<Row> rows) {
        return rows.stream()
                .sorted(Comparator.comparingInt(Row::recordOrder))
                .map(row -> "%d:%d:%d:%d:%d:%dx%d:%d".formatted(
                        row.tileIndex(), row.tileSequence(), row.plane(),
                        row.declaredWaveletLevels(), row.subband(),
                        row.width(), row.height(), row.bitDepth()))
                .collect(Collectors.joining("|"));
    }

    private static String empty(String value) {
        return value.isBlank() ? "—" : value;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
