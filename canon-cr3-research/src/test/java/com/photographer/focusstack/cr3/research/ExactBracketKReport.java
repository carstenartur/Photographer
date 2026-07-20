package com.photographer.focusstack.cr3.research;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class ExactBracketKReport {
    record Fixture(String name,
                   KAdaptationPanelMetrics.Result d8,
                   KAdaptationPanelMetrics.Result exact,
                   long bracketDifferences,
                   int regressedStreams,
                   int newlyExhaustedStreams,
                   int recoveredStreams) {
    }

    record Family(String name,
                  double d8Mae, double exactMae,
                  double d8ObMae, double exactObMae,
                  double d8Boundary, double exactBoundary,
                  boolean nonRegressing) {
    }

    record Verdict(String state,
                   int improvedFixtures,
                   int recoveredFixtures,
                   int regressedStreams,
                   int newlyExhaustedStreams,
                   int recoveredStreams,
                   double d8Mae, double exactMae,
                   double d8ObMae, double exactObMae,
                   double d8Boundary, double exactBoundary,
                   double d8Saturation, double exactSaturation,
                   boolean completionOkay,
                   boolean corpusGate,
                   boolean familyGate,
                   long bracketDifferences,
                   List<Family> families) {
    }

    private ExactBracketKReport() {
    }

    static String markdown(List<Fixture> fixtures, Verdict verdict) {
        StringBuilder out = new StringBuilder(
                "# D12 Exact Golomb-Rice K-Bracket Discriminator\n\n")
                .append("Fixed panel: exactly 11 reference-backed lossless fixtures. ")
                .append("Baseline is D8. The candidate changes only the non-zero ")
                .append("optimum K bracket.\n\n")
                .append("## Verdict: **").append(verdict.state()).append("**\n\n")
                .append("- improved fixtures: ").append(verdict.improvedFixtures()).append('\n')
                .append("- recovered fixtures: ").append(verdict.recoveredFixtures()).append('\n')
                .append("- regressed tile/plane streams: ").append(verdict.regressedStreams()).append('\n')
                .append("- newly exhausted tile/plane streams: ").append(verdict.newlyExhaustedStreams()).append('\n')
                .append("- recovered tile/plane streams: ").append(verdict.recoveredStreams()).append('\n')
                .append("- exact-vs-D8 K decisions: ").append(verdict.bracketDifferences()).append('\n')
                .append("- median global MAE D8/exact: ")
                .append(fmt(verdict.d8Mae())).append(" / ")
                .append(fmt(verdict.exactMae())).append('\n')
                .append("- median optical-black MAE D8/exact: ")
                .append(fmt(verdict.d8ObMae())).append(" / ")
                .append(fmt(verdict.exactObMae())).append('\n')
                .append("- median boundary MAE D8/exact: ")
                .append(fmt(verdict.d8Boundary())).append(" / ")
                .append(fmt(verdict.exactBoundary())).append('\n')
                .append("- median saturation D8/exact: ")
                .append(fmt(verdict.d8Saturation())).append(" / ")
                .append(fmt(verdict.exactSaturation())).append('\n')
                .append("- completion gate: ").append(verdict.completionOkay()).append('\n')
                .append("- corpus gate: ").append(verdict.corpusGate()).append('\n')
                .append("- family gate: ").append(verdict.familyGate()).append("\n\n")
                .append("## Family medians\n\n")
                .append("| Family | D8 MAE | Exact MAE | D8 OB | Exact OB | D8 boundary | Exact boundary | Non-regressing |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|:---:|\n");
        for (Family family : verdict.families()) {
            out.append("| ").append(family.name())
                    .append(" | ").append(fmt(family.d8Mae()))
                    .append(" | ").append(fmt(family.exactMae()))
                    .append(" | ").append(fmt(family.d8ObMae()))
                    .append(" | ").append(fmt(family.exactObMae()))
                    .append(" | ").append(fmt(family.d8Boundary()))
                    .append(" | ").append(fmt(family.exactBoundary()))
                    .append(" | ").append(family.nonRegressing() ? "yes" : "no")
                    .append(" |\n");
        }

        out.append("\n## Fixture metrics\n\n")
                .append("| Fixture | Rule | MAE | RMSE | Pearson | OB MAE | Boundary | Saturation | Completion | Exhausted | K differences | Stream regressions | New exhaustions | Recoveries |\n")
                .append("|---|---|---:|---:|---:|---:|---:|---:|---:|:---:|---:|---:|---:|---:|\n");
        for (Fixture fixture : fixtures) {
            row(out, fixture.name(), "D8_ZERO_CONTRACT", fixture.d8(),
                    0, 0, 0, 0);
            row(out, fixture.name(), "D12_EXACT_BRACKET", fixture.exact(),
                    fixture.bracketDifferences(), fixture.regressedStreams(),
                    fixture.newlyExhaustedStreams(), fixture.recoveredStreams());
        }
        return out.toString();
    }

    static String tsv(List<Fixture> fixtures) {
        StringBuilder out = new StringBuilder(
                "fixture\trule\tmae\trmse\tpearson\tobMae\tboundary\tsaturation\tcompletion\texhausted\tbracketDifferences\tregressedStreams\tnewlyExhaustedStreams\trecoveredStreams");
        for (int plane = 0; plane < 4; plane++) {
            out.append("\tp").append(plane).append("Mae")
                    .append("\tp").append(plane).append("Rmse")
                    .append("\tp").append(plane).append("Pearson");
        }
        out.append('\n');
        for (Fixture fixture : fixtures) {
            tsvRow(out, fixture.name(), "D8_ZERO_CONTRACT", fixture.d8(),
                    0, 0, 0, 0);
            tsvRow(out, fixture.name(), "D12_EXACT_BRACKET", fixture.exact(),
                    fixture.bracketDifferences(), fixture.regressedStreams(),
                    fixture.newlyExhaustedStreams(), fixture.recoveredStreams());
        }
        return out.toString();
    }

    static String json(List<Fixture> fixtures, Verdict verdict) {
        StringBuilder out = new StringBuilder("{\n  \"verdict\": \"")
                .append(verdict.state())
                .append("\",\n  \"improvedFixtures\": ")
                .append(verdict.improvedFixtures())
                .append(",\n  \"recoveredFixtures\": ")
                .append(verdict.recoveredFixtures())
                .append(",\n  \"regressedStreams\": ")
                .append(verdict.regressedStreams())
                .append(",\n  \"newlyExhaustedStreams\": ")
                .append(verdict.newlyExhaustedStreams())
                .append(",\n  \"recoveredStreams\": ")
                .append(verdict.recoveredStreams())
                .append(",\n  \"bracketDifferences\": ")
                .append(verdict.bracketDifferences())
                .append(",\n  \"completionGate\": ")
                .append(verdict.completionOkay())
                .append(",\n  \"corpusGate\": ")
                .append(verdict.corpusGate())
                .append(",\n  \"familyGate\": ")
                .append(verdict.familyGate())
                .append(",\n  \"families\": [");
        for (int index = 0; index < verdict.families().size(); index++) {
            if (index > 0) {
                out.append(',');
            }
            Family family = verdict.families().get(index);
            out.append("{\"name\":\"").append(escape(family.name()))
                    .append("\",\"d8Mae\":").append(number(family.d8Mae()))
                    .append(",\"exactMae\":").append(number(family.exactMae()))
                    .append(",\"d8ObMae\":").append(number(family.d8ObMae()))
                    .append(",\"exactObMae\":").append(number(family.exactObMae()))
                    .append(",\"d8Boundary\":").append(number(family.d8Boundary()))
                    .append(",\"exactBoundary\":").append(number(family.exactBoundary()))
                    .append(",\"nonRegressing\":").append(family.nonRegressing())
                    .append('}');
        }
        out.append("],\n  \"results\": [\n");
        boolean first = true;
        for (Fixture fixture : fixtures) {
            first = jsonRow(out, first, fixture.name(), "D8_ZERO_CONTRACT",
                    fixture.d8(), 0, 0, 0, 0);
            first = jsonRow(out, first, fixture.name(), "D12_EXACT_BRACKET",
                    fixture.exact(), fixture.bracketDifferences(),
                    fixture.regressedStreams(), fixture.newlyExhaustedStreams(),
                    fixture.recoveredStreams());
        }
        return out.append("\n  ]\n}\n").toString();
    }

    static Verdict verdict(List<Fixture> fixtures) {
        int improved = 0;
        int recoveredFixtures = 0;
        int regressedStreams = 0;
        int newlyExhaustedStreams = 0;
        int recoveredStreams = 0;
        boolean completionOkay = true;
        long differences = 0;
        for (Fixture fixture : fixtures) {
            differences += fixture.bracketDifferences();
            regressedStreams += fixture.regressedStreams();
            newlyExhaustedStreams += fixture.newlyExhaustedStreams();
            recoveredStreams += fixture.recoveredStreams();
            boolean completionRegression = fixture.exact().completion() + 1e-12
                    < fixture.d8().completion()
                    || (fixture.exact().exhausted() && !fixture.d8().exhausted())
                    || fixture.regressedStreams() > 0
                    || fixture.newlyExhaustedStreams() > 0;
            if (completionRegression) {
                completionOkay = false;
            }
            if (!completionRegression && primaryImprovement(fixture)) {
                improved++;
            }
            if (fixture.exact().completion() > fixture.d8().completion() + 1e-12
                    || (!fixture.exact().exhausted() && fixture.d8().exhausted())
                    || fixture.recoveredStreams() > 0) {
                recoveredFixtures++;
            }
        }

        double d8Mae = median(fixtures.stream()
                .map(value -> value.d8().global().mae()).toList());
        double exactMae = median(fixtures.stream()
                .map(value -> value.exact().global().mae()).toList());
        double d8Ob = medianFinite(fixtures.stream()
                .map(value -> value.d8().opticalBlackMae()).toList());
        double exactOb = medianFinite(fixtures.stream()
                .map(value -> value.exact().opticalBlackMae()).toList());
        double d8Boundary = medianFinite(fixtures.stream()
                .map(value -> value.d8().boundaryMae()).toList());
        double exactBoundary = medianFinite(fixtures.stream()
                .map(value -> value.exact().boundaryMae()).toList());
        double d8Saturation = median(fixtures.stream()
                .map(value -> value.d8().saturation()).toList());
        double exactSaturation = median(fixtures.stream()
                .map(value -> value.exact().saturation()).toList());

        boolean corpusGate = nonRegressing(exactMae, d8Mae)
                && nonRegressing(exactOb, d8Ob)
                && nonRegressing(exactBoundary, d8Boundary)
                && nonRegressing(exactSaturation, d8Saturation);
        List<Family> families = families(fixtures);
        boolean familyGate = families.stream().allMatch(Family::nonRegressing);
        boolean enoughEvidence = improved >= 2 || recoveredFixtures >= 1;
        String state = completionOkay && corpusGate && familyGate && enoughEvidence
                ? "SUPPORTED"
                : improved > 0 || recoveredFixtures > 0
                ? "PARTIAL"
                : "REJECTED";
        return new Verdict(state, improved, recoveredFixtures,
                regressedStreams, newlyExhaustedStreams, recoveredStreams,
                d8Mae, exactMae, d8Ob, exactOb,
                d8Boundary, exactBoundary,
                d8Saturation, exactSaturation,
                completionOkay, corpusGate, familyGate,
                differences, families);
    }

    private static List<Family> families(List<Fixture> fixtures) {
        Map<String, List<Fixture>> grouped = new LinkedHashMap<>();
        for (Fixture fixture : fixtures) {
            grouped.computeIfAbsent(family(fixture.name()), ignored -> new ArrayList<>())
                    .add(fixture);
        }
        List<Family> result = new ArrayList<>();
        for (Map.Entry<String, List<Fixture>> entry : grouped.entrySet()) {
            List<Fixture> values = entry.getValue();
            double d8Mae = median(values.stream()
                    .map(value -> value.d8().global().mae()).toList());
            double exactMae = median(values.stream()
                    .map(value -> value.exact().global().mae()).toList());
            double d8Ob = medianFinite(values.stream()
                    .map(value -> value.d8().opticalBlackMae()).toList());
            double exactOb = medianFinite(values.stream()
                    .map(value -> value.exact().opticalBlackMae()).toList());
            double d8Boundary = medianFinite(values.stream()
                    .map(value -> value.d8().boundaryMae()).toList());
            double exactBoundary = medianFinite(values.stream()
                    .map(value -> value.exact().boundaryMae()).toList());
            boolean nonRegressing = nonRegressing(exactMae, d8Mae)
                    && nonRegressing(exactOb, d8Ob)
                    && nonRegressing(exactBoundary, d8Boundary);
            result.add(new Family(entry.getKey(), d8Mae, exactMae,
                    d8Ob, exactOb, d8Boundary, exactBoundary, nonRegressing));
        }
        return List.copyOf(result);
    }

    private static String family(String fixture) {
        if (fixture.startsWith("eosr-")) {
            return "EOS_R";
        }
        if (fixture.startsWith("research/g5xm2-")) {
            return "G5_X_MARK_II_RESEARCH";
        }
        return "SAMPLE_SERIES";
    }

    private static boolean primaryImprovement(Fixture fixture) {
        return fixture.exact().global().mae() < fixture.d8().global().mae()
                || finiteImprovement(fixture.exact().opticalBlackMae(),
                fixture.d8().opticalBlackMae())
                || finiteImprovement(fixture.exact().boundaryMae(),
                fixture.d8().boundaryMae());
    }

    private static boolean finiteImprovement(double candidate, double baseline) {
        return Double.isFinite(candidate)
                && Double.isFinite(baseline)
                && candidate < baseline;
    }

    private static boolean nonRegressing(double candidate, double baseline) {
        return !Double.isFinite(baseline)
                || (Double.isFinite(candidate) && candidate <= baseline + 1e-12);
    }

    private static double median(List<Double> values) {
        double[] sorted = values.stream().mapToDouble(Double::doubleValue)
                .sorted().toArray();
        if (sorted.length == 0) {
            return Double.NaN;
        }
        int middle = sorted.length / 2;
        return sorted.length % 2 == 0
                ? (sorted[middle - 1] + sorted[middle]) / 2.0
                : sorted[middle];
    }

    private static double medianFinite(List<Double> values) {
        return median(values.stream().filter(Double::isFinite).toList());
    }

    private static void row(StringBuilder out, String fixture, String rule,
                            KAdaptationPanelMetrics.Result value,
                            long bracketDifferences,
                            int regressedStreams,
                            int newlyExhaustedStreams,
                            int recoveredStreams) {
        out.append("| `").append(fixture).append("` | ").append(rule)
                .append(" | ").append(fmt(value.global().mae()))
                .append(" | ").append(fmt(value.global().rmse()))
                .append(" | ").append(fmt(value.global().pearson()))
                .append(" | ").append(fmt(value.opticalBlackMae()))
                .append(" | ").append(fmt(value.boundaryMae()))
                .append(" | ").append(fmt(value.saturation()))
                .append(" | ").append(fmt(value.completion()))
                .append(" | ").append(value.exhausted() ? "yes" : "no")
                .append(" | ").append(bracketDifferences)
                .append(" | ").append(regressedStreams)
                .append(" | ").append(newlyExhaustedStreams)
                .append(" | ").append(recoveredStreams).append(" |\n");
    }

    private static void tsvRow(StringBuilder out, String fixture, String rule,
                               KAdaptationPanelMetrics.Result value,
                               long bracketDifferences,
                               int regressedStreams,
                               int newlyExhaustedStreams,
                               int recoveredStreams) {
        out.append(fixture).append('\t').append(rule).append('\t')
                .append(value.global().mae()).append('\t')
                .append(value.global().rmse()).append('\t')
                .append(value.global().pearson()).append('\t')
                .append(value.opticalBlackMae()).append('\t')
                .append(value.boundaryMae()).append('\t')
                .append(value.saturation()).append('\t')
                .append(value.completion()).append('\t')
                .append(value.exhausted()).append('\t')
                .append(bracketDifferences).append('\t')
                .append(regressedStreams).append('\t')
                .append(newlyExhaustedStreams).append('\t')
                .append(recoveredStreams);
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
                                   long bracketDifferences,
                                   int regressedStreams,
                                   int newlyExhaustedStreams,
                                   int recoveredStreams) {
        if (!first) {
            out.append(",\n");
        }
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
                .append(",\"bracketDifferences\":").append(bracketDifferences)
                .append(",\"regressedStreams\":").append(regressedStreams)
                .append(",\"newlyExhaustedStreams\":").append(newlyExhaustedStreams)
                .append(",\"recoveredStreams\":").append(recoveredStreams)
                .append(",\"planes\":[");
        for (int index = 0; index < value.planes().size(); index++) {
            if (index > 0) {
                out.append(',');
            }
            KAdaptationPanelMetrics.Metric plane = value.planes().get(index);
            out.append("{\"plane\":").append(index)
                    .append(",\"mae\":").append(number(plane.mae()))
                    .append(",\"rmse\":").append(number(plane.rmse()))
                    .append(",\"pearson\":").append(number(plane.pearson()))
                    .append('}');
        }
        out.append("]}");
        return false;
    }

    private static String fmt(double value) {
        return Double.isFinite(value)
                ? String.format(Locale.ROOT, "%.6f", value)
                : "n/a";
    }

    private static String number(double value) {
        return Double.isFinite(value) ? Double.toString(value) : "null";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
