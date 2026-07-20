package com.photographer.focusstack.cr3.research;

import java.util.List;
import java.util.Locale;

final class ZeroContractedKReport {
    record Fixture(String name, KAdaptationPanelMetrics.Result hybrid,
                   KAdaptationPanelMetrics.Result d8) {}
    record Verdict(String state, int successes,
                   double hybridMae, double d8Mae,
                   double hybridBoundary, double d8Boundary,
                   double hybridSaturation, double d8Saturation,
                   boolean completionOkay) {}

    private ZeroContractedKReport() {}

    static String markdown(List<Fixture> fixtures, Verdict verdict) {
        StringBuilder out = new StringBuilder("# D8 Zero-Contracted K Discriminator\n\n")
                .append("Fixed panel: exactly 11 reference-backed lossless fixtures.\n\n")
                .append("## Verdict: **").append(verdict.state()).append("**\n\n")
                .append("- successful discriminator fixtures: ")
                .append(verdict.successes()).append(" / 3\n")
                .append("- median global MAE HYBRID/D8: ")
                .append(fmt(verdict.hybridMae())).append(" / ")
                .append(fmt(verdict.d8Mae())).append('\n')
                .append("- median boundary HYBRID/D8: ")
                .append(fmt(verdict.hybridBoundary())).append(" / ")
                .append(fmt(verdict.d8Boundary())).append('\n')
                .append("- median saturation HYBRID/D8: ")
                .append(fmt(verdict.hybridSaturation())).append(" / ")
                .append(fmt(verdict.d8Saturation())).append('\n')
                .append("- no completion regression: ")
                .append(verdict.completionOkay()).append("\n\n")
                .append("| Fixture | Rule | MAE | RMSE | Pearson | OB MAE | Boundary | Saturation | Completion | Exhausted |\n")
                .append("|---|---|---:|---:|---:|---:|---:|---:|---:|:---:|\n");
        for (Fixture fixture : fixtures) {
            row(out, fixture.name(), "HYBRID", fixture.hybrid());
            row(out, fixture.name(), "D8_ZERO_CONTRACT", fixture.d8());
        }
        return out.toString();
    }

    static String tsv(List<Fixture> fixtures) {
        StringBuilder out = new StringBuilder(
                "fixture\trule\tmae\trmse\tpearson\tobMae\tboundary\tsaturation\tcompletion\texhausted");
        for (int plane = 0; plane < 4; plane++) {
            out.append("\tp").append(plane).append("Mae")
                    .append("\tp").append(plane).append("Rmse")
                    .append("\tp").append(plane).append("Pearson");
        }
        out.append('\n');
        for (Fixture fixture : fixtures) {
            tsvRow(out, fixture.name(), "HYBRID", fixture.hybrid());
            tsvRow(out, fixture.name(), "D8_ZERO_CONTRACT", fixture.d8());
        }
        return out.toString();
    }

    static String json(List<Fixture> fixtures, Verdict verdict) {
        StringBuilder out = new StringBuilder("{\n  \"verdict\": \"")
                .append(verdict.state()).append("\",\n  \"successfulDiscriminators\": ")
                .append(verdict.successes()).append(",\n  \"noCompletionRegression\": ")
                .append(verdict.completionOkay()).append(",\n  \"results\": [\n");
        boolean first = true;
        for (Fixture fixture : fixtures) {
            first = jsonRow(out, first, fixture.name(), "HYBRID", fixture.hybrid());
            first = jsonRow(out, first, fixture.name(), "D8_ZERO_CONTRACT", fixture.d8());
        }
        return out.append("\n  ]\n}\n").toString();
    }

    private static void row(StringBuilder out, String fixture, String rule,
                            KAdaptationPanelMetrics.Result value) {
        out.append("| `").append(fixture).append("` | ").append(rule)
                .append(" | ").append(fmt(value.global().mae()))
                .append(" | ").append(fmt(value.global().rmse()))
                .append(" | ").append(fmt(value.global().pearson()))
                .append(" | ").append(fmt(value.opticalBlackMae()))
                .append(" | ").append(fmt(value.boundaryMae()))
                .append(" | ").append(fmt(value.saturation()))
                .append(" | ").append(fmt(value.completion()))
                .append(" | ").append(value.exhausted() ? "yes" : "no")
                .append(" |\n");
    }

    private static void tsvRow(StringBuilder out, String fixture, String rule,
                               KAdaptationPanelMetrics.Result value) {
        out.append(fixture).append('\t').append(rule).append('\t')
                .append(value.global().mae()).append('\t')
                .append(value.global().rmse()).append('\t')
                .append(value.global().pearson()).append('\t')
                .append(value.opticalBlackMae()).append('\t')
                .append(value.boundaryMae()).append('\t')
                .append(value.saturation()).append('\t')
                .append(value.completion()).append('\t')
                .append(value.exhausted());
        for (KAdaptationPanelMetrics.Metric plane : value.planes()) {
            out.append('\t').append(plane.mae())
                    .append('\t').append(plane.rmse())
                    .append('\t').append(plane.pearson());
        }
        out.append('\n');
    }

    private static boolean jsonRow(StringBuilder out, boolean first,
                                   String fixture, String rule,
                                   KAdaptationPanelMetrics.Result value) {
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
                .append(",\"planes\":[");
        for (int i = 0; i < value.planes().size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            KAdaptationPanelMetrics.Metric plane = value.planes().get(i);
            out.append("{\"plane\":").append(i)
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
                ? String.format(Locale.ROOT, "%.4f", value)
                : "n/a";
    }

    private static String number(double value) {
        return Double.isFinite(value) ? Double.toString(value) : "null";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
