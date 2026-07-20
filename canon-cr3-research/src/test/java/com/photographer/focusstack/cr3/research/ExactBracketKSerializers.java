package com.photographer.focusstack.cr3.research;

import java.util.List;
import java.util.Locale;

final class ExactBracketKSerializers {
    private ExactBracketKSerializers() {
    }

    static String markdown(List<ExactBracketKReport.Fixture> fixtures,
                           ExactBracketKReport.Verdict verdict) {
        StringBuilder out = new StringBuilder(
                "# D12 Exact Golomb-Rice K-Bracket Discriminator\n\n")
                .append("Fixed panel: exactly 11 reference-backed lossless fixtures. ")
                .append("Baseline is D8. Only the non-zero optimum K bracket changes.\n\n")
                .append("## Verdict: **").append(verdict.state()).append("**\n\n")
                .append("- improved fixtures: ").append(verdict.improvedFixtures()).append('\n')
                .append("- fixtures becoming complete: ").append(verdict.completedFixtures()).append('\n')
                .append("- regressed tile/plane streams: ").append(verdict.regressedStreams()).append('\n')
                .append("- newly exhausted tile/plane streams: ").append(verdict.newlyExhaustedStreams()).append('\n')
                .append("- recovered tile/plane streams: ").append(verdict.recoveredStreams()).append('\n')
                .append("- exact-vs-D8 K decisions: ").append(verdict.bracketDifferences()).append('\n')
                .append("- median global MAE D8/exact: ").append(fmt(verdict.d8Mae()))
                .append(" / ").append(fmt(verdict.exactMae())).append('\n')
                .append("- median optical-black MAE D8/exact: ").append(fmt(verdict.d8ObMae()))
                .append(" / ").append(fmt(verdict.exactObMae())).append('\n')
                .append("- median boundary MAE D8/exact: ").append(fmt(verdict.d8Boundary()))
                .append(" / ").append(fmt(verdict.exactBoundary())).append('\n')
                .append("- median saturation D8/exact: ").append(fmt(verdict.d8Saturation()))
                .append(" / ").append(fmt(verdict.exactSaturation())).append('\n')
                .append("- completion/corpus/family gates: ")
                .append(verdict.completionOkay()).append(" / ")
                .append(verdict.corpusGate()).append(" / ")
                .append(verdict.familyGate()).append("\n\n")
                .append("## Family medians\n\n")
                .append("| Family | D8 MAE | Exact MAE | D8 OB | Exact OB | D8 boundary | Exact boundary | Gate |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|:---:|\n");
        for (ExactBracketKReport.Family family : verdict.families()) {
            out.append("| ").append(family.name())
                    .append(" | ").append(fmt(family.d8Mae()))
                    .append(" | ").append(fmt(family.exactMae()))
                    .append(" | ").append(fmt(family.d8ObMae()))
                    .append(" | ").append(fmt(family.exactObMae()))
                    .append(" | ").append(fmt(family.d8Boundary()))
                    .append(" | ").append(fmt(family.exactBoundary()))
                    .append(" | ").append(family.nonRegressing() ? "pass" : "fail")
                    .append(" |\n");
        }

        out.append("\n## Fixture metrics\n\n")
                .append("| Fixture | Rule | MAE | OB | Boundary | Saturation | Completion | Exhausted | K differences | Stream regressions | New exhaustions | Recoveries |\n")
                .append("|---|---|---:|---:|---:|---:|---:|:---:|---:|---:|---:|---:|\n");
        for (ExactBracketKReport.Fixture fixture : fixtures) {
            markdownRow(out, fixture.name(), "D8_ZERO_CONTRACT", fixture.d8(), 0, 0, 0, 0);
            markdownRow(out, fixture.name(), "D12_EXACT_BRACKET", fixture.exact(),
                    fixture.bracketDifferences(), fixture.regressedStreams(),
                    fixture.newlyExhaustedStreams(), fixture.recoveredStreams());
        }
        return out.toString();
    }

    static String tsv(List<ExactBracketKReport.Fixture> fixtures) {
        StringBuilder out = new StringBuilder(
                "fixture\trule\tmae\trmse\tpearson\tobMae\tboundary\tsaturation\tcompletion\texhausted\tbracketDifferences\tregressedStreams\tnewlyExhaustedStreams\trecoveredStreams");
        for (int plane = 0; plane < 4; plane++) {
            out.append("\tp").append(plane).append("Mae")
                    .append("\tp").append(plane).append("Rmse")
                    .append("\tp").append(plane).append("Pearson");
        }
        out.append('\n');
        for (ExactBracketKReport.Fixture fixture : fixtures) {
            tsvRow(out, fixture.name(), "D8_ZERO_CONTRACT", fixture.d8(), 0, 0, 0, 0);
            tsvRow(out, fixture.name(), "D12_EXACT_BRACKET", fixture.exact(),
                    fixture.bracketDifferences(), fixture.regressedStreams(),
                    fixture.newlyExhaustedStreams(), fixture.recoveredStreams());
        }
        return out.toString();
    }

    static String json(List<ExactBracketKReport.Fixture> fixtures,
                       ExactBracketKReport.Verdict verdict) {
        StringBuilder out = new StringBuilder("{\n")
                .append("  \"verdict\": \"").append(verdict.state()).append("\",\n")
                .append("  \"improvedFixtures\": ").append(verdict.improvedFixtures()).append(",\n")
                .append("  \"completedFixtures\": ").append(verdict.completedFixtures()).append(",\n")
                .append("  \"regressedStreams\": ").append(verdict.regressedStreams()).append(",\n")
                .append("  \"newlyExhaustedStreams\": ").append(verdict.newlyExhaustedStreams()).append(",\n")
                .append("  \"recoveredStreams\": ").append(verdict.recoveredStreams()).append(",\n")
                .append("  \"bracketDifferences\": ").append(verdict.bracketDifferences()).append(",\n")
                .append("  \"completionGate\": ").append(verdict.completionOkay()).append(",\n")
                .append("  \"corpusGate\": ").append(verdict.corpusGate()).append(",\n")
                .append("  \"familyGate\": ").append(verdict.familyGate()).append(",\n")
                .append("  \"families\": [");
        for (int index = 0; index < verdict.families().size(); index++) {
            if (index > 0) out.append(',');
            ExactBracketKReport.Family family = verdict.families().get(index);
            out.append("{\"name\":\"").append(escape(family.name()))
                    .append("\",\"d8Mae\":").append(number(family.d8Mae()))
                    .append(",\"exactMae\":").append(number(family.exactMae()))
                    .append(",\"d8ObMae\":").append(number(family.d8ObMae()))
                    .append(",\"exactObMae\":").append(number(family.exactObMae()))
                    .append(",\"d8Boundary\":").append(number(family.d8Boundary()))
                    .append(",\"exactBoundary\":").append(number(family.exactBoundary()))
                    .append(",\"nonRegressing\":").append(family.nonRegressing()).append('}');
        }
        out.append("],\n  \"results\": [\n");
        boolean first = true;
        for (ExactBracketKReport.Fixture fixture : fixtures) {
            first = jsonRow(out, first, fixture.name(), "D8_ZERO_CONTRACT", fixture.d8(), 0, 0, 0, 0);
            first = jsonRow(out, first, fixture.name(), "D12_EXACT_BRACKET", fixture.exact(),
                    fixture.bracketDifferences(), fixture.regressedStreams(),
                    fixture.newlyExhaustedStreams(), fixture.recoveredStreams());
        }
        return out.append("\n  ]\n}\n").toString();
    }

    private static void markdownRow(StringBuilder out, String fixture, String rule,
                                    KAdaptationPanelMetrics.Result value,
                                    long differences, int regressions,
                                    int newExhaustions, int recoveries) {
        out.append("| `").append(fixture).append("` | ").append(rule)
                .append(" | ").append(fmt(value.global().mae()))
                .append(" | ").append(fmt(value.opticalBlackMae()))
                .append(" | ").append(fmt(value.boundaryMae()))
                .append(" | ").append(fmt(value.saturation()))
                .append(" | ").append(fmt(value.completion()))
                .append(" | ").append(value.exhausted() ? "yes" : "no")
                .append(" | ").append(differences)
                .append(" | ").append(regressions)
                .append(" | ").append(newExhaustions)
                .append(" | ").append(recoveries).append(" |\n");
    }

    private static void tsvRow(StringBuilder out, String fixture, String rule,
                               KAdaptationPanelMetrics.Result value,
                               long differences, int regressions,
                               int newExhaustions, int recoveries) {
        out.append(fixture).append('\t').append(rule).append('\t')
                .append(value.global().mae()).append('\t')
                .append(value.global().rmse()).append('\t')
                .append(value.global().pearson()).append('\t')
                .append(value.opticalBlackMae()).append('\t')
                .append(value.boundaryMae()).append('\t')
                .append(value.saturation()).append('\t')
                .append(value.completion()).append('\t')
                .append(value.exhausted()).append('\t')
                .append(differences).append('\t').append(regressions).append('\t')
                .append(newExhaustions).append('\t').append(recoveries);
        for (KAdaptationPanelMetrics.Metric plane : value.planes()) {
            out.append('\t').append(plane.mae())
                    .append('\t').append(plane.rmse())
                    .append('\t').append(plane.pearson());
        }
        out.append('\n');
    }

    private static boolean jsonRow(StringBuilder out, boolean first,
                                   String fixture, String rule,
                                   KAdaptationPanelMetrics.Result value,
                                   long differences, int regressions,
                                   int newExhaustions, int recoveries) {
        if (!first) out.append(",\n");
        out.append("    {\"fixture\":\"").append(escape(fixture))
                .append("\",\"rule\":\"").append(rule)
                .append("\",\"mae\":").append(number(value.global().mae()))
                .append(",\"rmse\":").append(number(value.global().rmse()))
                .append(",\"pearson\":").append(number(value.global().pearson()))
                .append(",\"obMae\":").append(number(value.opticalBlackMae()))
                .append(",\"boundary\":").append(number(value.boundaryMae()))
                .append(",\"saturation\":").append(number(value.saturation()))
                .append(",\"completion\":").append(number(value.completion()))
                .append(",\"exhausted\":").append(value.exhausted())
                .append(",\"bracketDifferences\":").append(differences)
                .append(",\"regressedStreams\":").append(regressions)
                .append(",\"newlyExhaustedStreams\":").append(newExhaustions)
                .append(",\"recoveredStreams\":").append(recoveries)
                .append(",\"planes\":[");
        for (int index = 0; index < value.planes().size(); index++) {
            if (index > 0) out.append(',');
            KAdaptationPanelMetrics.Metric plane = value.planes().get(index);
            out.append("{\"plane\":").append(index)
                    .append(",\"mae\":").append(number(plane.mae()))
                    .append(",\"rmse\":").append(number(plane.rmse()))
                    .append(",\"pearson\":").append(number(plane.pearson())).append('}');
        }
        out.append("]}");
        return false;
    }

    private static String fmt(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.6f", value) : "n/a";
    }

    private static String number(double value) {
        return Double.isFinite(value) ? Double.toString(value) : "null";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
