package com.photographer.focusstack.cr3.research;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class KTransitionMatrixReport {
    enum TransitionClass {
        BOTH_HOLD,
        BOTH_INCREASE,
        BOTH_DECREASE,
        HYBRID_DECREASE_D8_HOLD,
        HYBRID_DECREASE_D8_INCREASE,
        HYBRID_HOLD_D8_INCREASE,
        HYBRID_INCREASE_D8_HOLD,
        HYBRID_INCREASE_D8_DECREASE,
        OTHER_OPPOSITE_DIRECTION,
        OTHER_SAME_DIRECTION
    }

    record Cell(int tile, int plane, TransitionClass transition,
                boolean zeroSymbol, long count) {
    }

    record Split(int tile, int plane, int ordinal,
                 long hybridBitStart, long d8BitStart,
                 String reason) {
    }

    record Fixture(String name, D8OutcomeTable.Outcome outcome,
                   long alignedSymbols, List<Cell> cells,
                   Map<Integer, Long> kBeforeBins,
                   Map<String, Long> symbolMagnitudeBins,
                   List<Split> splits) {
    }

    record Association(TransitionClass transition,
                       D8OutcomeTable.OutcomeClass outcomeClass,
                       int support,
                       int completeImprovementContradictions,
                       List<String> supportingFixtures,
                       List<String> contradictingFixtures) {
        boolean predictive() {
            return support >= 2 && completeImprovementContradictions == 0;
        }
    }

    private KTransitionMatrixReport() {
    }

    static String selectedNextCause(List<Association> associations) {
        return associations.stream()
                .filter(Association::predictive)
                .sorted(Comparator.comparingInt(Association::support).reversed()
                        .thenComparing(association -> association.transition().name())
                        .thenComparing(association -> association.outcomeClass().name()))
                .map(association -> association.transition().name()
                        + "->" + association.outcomeClass().name())
                .findFirst()
                .orElse("NO_PREDICTIVE_TRANSITION_CLASS");
    }

    static List<Association> associations(List<Fixture> fixtures) {
        List<Association> output = new ArrayList<>();
        for (TransitionClass transition : TransitionClass.values()) {
            if (!isCandidateTransition(transition)) {
                continue;
            }
            for (D8OutcomeTable.OutcomeClass outcomeClass : List.of(
                    D8OutcomeTable.OutcomeClass.COMPLETION_REGRESSION,
                    D8OutcomeTable.OutcomeClass.COMPLETE_GLOBAL_REGRESSION)) {
                List<String> support = new ArrayList<>();
                List<String> contradictions = new ArrayList<>();
                for (Fixture fixture : fixtures) {
                    if (!containsNonZeroTransition(fixture, transition)) {
                        continue;
                    }
                    if (fixture.outcome().outcomeClass() == outcomeClass) {
                        support.add(fixture.name());
                    } else if (fixture.outcome().outcomeClass()
                            == D8OutcomeTable.OutcomeClass.COMPLETE_IMPROVEMENT) {
                        contradictions.add(fixture.name());
                    }
                }
                output.add(new Association(transition, outcomeClass,
                        support.size(), contradictions.size(),
                        List.copyOf(support), List.copyOf(contradictions)));
            }
        }
        return List.copyOf(output);
    }

    private static boolean isCandidateTransition(TransitionClass transition) {
        return switch (transition) {
            case HYBRID_DECREASE_D8_HOLD,
                    HYBRID_DECREASE_D8_INCREASE,
                    HYBRID_HOLD_D8_INCREASE,
                    HYBRID_INCREASE_D8_HOLD,
                    HYBRID_INCREASE_D8_DECREASE,
                    OTHER_OPPOSITE_DIRECTION -> true;
            default -> false;
        };
    }

    private static boolean containsNonZeroTransition(
            Fixture fixture, TransitionClass transition) {
        return fixture.cells().stream()
                .anyMatch(cell -> !cell.zeroSymbol()
                        && cell.transition() == transition
                        && cell.count() > 0);
    }

    static String markdown(List<Fixture> fixtures,
                           List<Association> associations) {
        String selected = selectedNextCause(associations);
        StringBuilder out = new StringBuilder(
                "# D10 K-Transition / Decoder-Outcome Matrix\n\n")
                .append("Only symbols decoded identically by HYBRID and D8 before the first ")
                .append("bit/symbol split of each tile-plane are counted. Predictive ")
                .append("associations use non-zero symbols only.\n\n")
                .append("## Verdict\n\n`").append(selected).append("`\n\n")
                .append("## Fixture summary\n\n")
                .append("| Fixture | Outcome | Aligned symbols | Cells | Splits | ΔMAE | ΔOB | ΔBoundary | ΔCompletion |\n")
                .append("|---|---|---:|---:|---:|---:|---:|---:|---:|\n");
        for (Fixture fixture : fixtures) {
            out.append("| `").append(fixture.name()).append("` | ")
                    .append(fixture.outcome().outcomeClass()).append(" | ")
                    .append(fixture.alignedSymbols()).append(" | ")
                    .append(fixture.cells().size()).append(" | ")
                    .append(fixture.splits().size()).append(" | ")
                    .append(fmt(fixture.outcome().deltaMae())).append(" | ")
                    .append(fmt(fixture.outcome().deltaObMae())).append(" | ")
                    .append(fmt(fixture.outcome().deltaBoundary())).append(" | ")
                    .append(fmt(fixture.outcome().deltaCompletion())).append(" |\n");
        }

        out.append("\n## Associations\n\n")
                .append("| Transition | Outcome | Support | Improvement contradictions | Predictive | Supporting fixtures | Contradictions |\n")
                .append("|---|---|---:|---:|:---:|---|---|\n");
        for (Association association : associations) {
            out.append("| ").append(association.transition()).append(" | ")
                    .append(association.outcomeClass()).append(" | ")
                    .append(association.support()).append(" | ")
                    .append(association.completeImprovementContradictions()).append(" | ")
                    .append(association.predictive() ? "yes" : "no").append(" | ")
                    .append(join(association.supportingFixtures())).append(" | ")
                    .append(join(association.contradictingFixtures())).append(" |\n");
        }

        out.append("\n## Transition totals per fixture\n\n")
                .append("| Fixture | Symbol kind | Transition | Count | Rate |\n")
                .append("|---|---|---|---:|---:|\n");
        for (Fixture fixture : fixtures) {
            for (Cell cell : fixture.cells()) {
                double rate = fixture.alignedSymbols() == 0 ? 0.0
                        : (double) cell.count() / fixture.alignedSymbols();
                out.append("| `").append(fixture.name()).append("` | ")
                        .append(cell.zeroSymbol() ? "ZERO" : "NON_ZERO").append(" | ")
                        .append(cell.transition()).append(" | ")
                        .append(cell.count()).append(" | ")
                        .append(fmt(rate)).append(" |\n");
            }
        }
        return out.toString();
    }

    static String tsv(List<Fixture> fixtures) {
        StringBuilder out = new StringBuilder(
                "fixture\toutcomeClass\ttile\tplane\tsymbolKind\ttransition\tcount\talignedSymbols\tdeltaMae\tdeltaObMae\tdeltaBoundary\tdeltaCompletion\n");
        for (Fixture fixture : fixtures) {
            for (Cell cell : fixture.cells()) {
                out.append(fixture.name()).append('\t')
                        .append(fixture.outcome().outcomeClass()).append('\t')
                        .append(cell.tile()).append('\t')
                        .append(cell.plane()).append('\t')
                        .append(cell.zeroSymbol() ? "ZERO" : "NON_ZERO").append('\t')
                        .append(cell.transition()).append('\t')
                        .append(cell.count()).append('\t')
                        .append(fixture.alignedSymbols()).append('\t')
                        .append(fixture.outcome().deltaMae()).append('\t')
                        .append(fixture.outcome().deltaObMae()).append('\t')
                        .append(fixture.outcome().deltaBoundary()).append('\t')
                        .append(fixture.outcome().deltaCompletion()).append('\n');
            }
        }
        return out.toString();
    }

    static String json(List<Fixture> fixtures,
                       List<Association> associations) {
        StringBuilder out = new StringBuilder("{\n  \"verdict\": \"")
                .append(selectedNextCause(associations))
                .append("\",\n  \"fixtures\": [\n");
        for (int index = 0; index < fixtures.size(); index++) {
            if (index > 0) {
                out.append(",\n");
            }
            Fixture fixture = fixtures.get(index);
            out.append("    {\"fixture\":\"").append(escape(fixture.name()))
                    .append("\",\"outcomeClass\":\"")
                    .append(fixture.outcome().outcomeClass())
                    .append("\",\"alignedSymbols\":")
                    .append(fixture.alignedSymbols())
                    .append(",\"kBeforeBins\":");
            mapJson(out, fixture.kBeforeBins());
            out.append(",\"symbolMagnitudeBins\":");
            mapJson(out, fixture.symbolMagnitudeBins());
            out.append(",\"cells\":[");
            for (int cellIndex = 0; cellIndex < fixture.cells().size(); cellIndex++) {
                if (cellIndex > 0) {
                    out.append(',');
                }
                Cell cell = fixture.cells().get(cellIndex);
                out.append("{\"tile\":").append(cell.tile())
                        .append(",\"plane\":").append(cell.plane())
                        .append(",\"transition\":\"").append(cell.transition())
                        .append("\",\"zeroSymbol\":").append(cell.zeroSymbol())
                        .append(",\"count\":").append(cell.count()).append('}');
            }
            out.append("],\"splits\":[");
            for (int splitIndex = 0; splitIndex < fixture.splits().size(); splitIndex++) {
                if (splitIndex > 0) {
                    out.append(',');
                }
                Split split = fixture.splits().get(splitIndex);
                out.append("{\"tile\":").append(split.tile())
                        .append(",\"plane\":").append(split.plane())
                        .append(",\"ordinal\":").append(split.ordinal())
                        .append(",\"hybridBitStart\":").append(split.hybridBitStart())
                        .append(",\"d8BitStart\":").append(split.d8BitStart())
                        .append(",\"reason\":\"").append(escape(split.reason()))
                        .append("\"}");
            }
            out.append("]}");
        }
        out.append("\n  ],\n  \"associations\": [\n");
        for (int index = 0; index < associations.size(); index++) {
            if (index > 0) {
                out.append(",\n");
            }
            Association association = associations.get(index);
            out.append("    {\"transition\":\"").append(association.transition())
                    .append("\",\"outcomeClass\":\"")
                    .append(association.outcomeClass())
                    .append("\",\"support\":").append(association.support())
                    .append(",\"completeImprovementContradictions\":")
                    .append(association.completeImprovementContradictions())
                    .append(",\"predictive\":").append(association.predictive())
                    .append('}');
        }
        return out.append("\n  ]\n}\n").toString();
    }

    private static void mapJson(StringBuilder out, Map<?, Long> values) {
        out.append('{');
        boolean first = true;
        for (Map.Entry<?, Long> entry : values.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append('\"').append(escape(entry.getKey().toString())).append("\":")
                    .append(entry.getValue());
        }
        out.append('}');
    }

    private static String join(List<String> values) {
        return values.isEmpty() ? "—" : String.join(", ", values);
    }

    private static String fmt(double value) {
        return Double.isFinite(value)
                ? String.format(Locale.ROOT, "%.6f", value)
                : "n/a";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
