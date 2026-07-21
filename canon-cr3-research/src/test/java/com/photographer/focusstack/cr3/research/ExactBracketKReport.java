package com.photographer.focusstack.cr3.research;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ExactBracketKReport {
    private static final double EPSILON = 1e-12;

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
                   int completedFixtures,
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
        return ExactBracketKSerializers.markdown(fixtures, verdict);
    }

    static String tsv(List<Fixture> fixtures) {
        return ExactBracketKSerializers.tsv(fixtures);
    }

    static String json(List<Fixture> fixtures, Verdict verdict) {
        return ExactBracketKSerializers.json(fixtures, verdict);
    }

    static Verdict verdict(List<Fixture> fixtures) {
        int improved = 0;
        int completed = 0;
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

            boolean incompleteExhaustion = fixture.exact().exhausted()
                    && fixture.exact().completion() < 1.0 - EPSILON
                    && !fixture.d8().exhausted();
            boolean completionRegression = fixture.exact().completion() + EPSILON
                    < fixture.d8().completion()
                    || incompleteExhaustion
                    || fixture.regressedStreams() > 0
                    || fixture.newlyExhaustedStreams() > 0;
            completionOkay &= !completionRegression;
            if (!completionRegression && primaryImprovement(fixture)) {
                improved++;
            }
            if (fixture.d8().completion() < 1.0 - EPSILON
                    && fixture.exact().completion() >= 1.0 - EPSILON
                    && !fixture.exact().exhausted()) {
                completed++;
            }
        }

        double d8Mae = median(values(fixtures, MetricKind.D8_MAE));
        double exactMae = median(values(fixtures, MetricKind.EXACT_MAE));
        double d8Ob = medianFinite(values(fixtures, MetricKind.D8_OB));
        double exactOb = medianFinite(values(fixtures, MetricKind.EXACT_OB));
        double d8Boundary = medianFinite(values(fixtures, MetricKind.D8_BOUNDARY));
        double exactBoundary = medianFinite(values(fixtures, MetricKind.EXACT_BOUNDARY));
        double d8Saturation = median(values(fixtures, MetricKind.D8_SATURATION));
        double exactSaturation = median(values(fixtures, MetricKind.EXACT_SATURATION));

        boolean corpusGate = nonRegressing(exactMae, d8Mae)
                && nonRegressing(exactOb, d8Ob)
                && nonRegressing(exactBoundary, d8Boundary)
                && nonRegressing(exactSaturation, d8Saturation);
        List<Family> families = families(fixtures);
        boolean familyGate = families.stream().allMatch(Family::nonRegressing);
        boolean enoughEvidence = improved >= 2 || completed >= 1;
        boolean partialEvidence = improved > 0 || completed > 0 || recoveredStreams > 0;
        String state = completionOkay && corpusGate && familyGate && enoughEvidence
                ? "SUPPORTED"
                : partialEvidence ? "PARTIAL" : "REJECTED";

        return new Verdict(state, improved, completed,
                regressedStreams, newlyExhaustedStreams, recoveredStreams,
                d8Mae, exactMae, d8Ob, exactOb,
                d8Boundary, exactBoundary, d8Saturation, exactSaturation,
                completionOkay, corpusGate, familyGate, differences, families);
    }

    private enum MetricKind {
        D8_MAE, EXACT_MAE, D8_OB, EXACT_OB,
        D8_BOUNDARY, EXACT_BOUNDARY, D8_SATURATION, EXACT_SATURATION
    }

    private static List<Double> values(List<Fixture> fixtures, MetricKind kind) {
        return fixtures.stream().map(fixture -> switch (kind) {
            case D8_MAE -> fixture.d8().global().mae();
            case EXACT_MAE -> fixture.exact().global().mae();
            case D8_OB -> fixture.d8().opticalBlackMae();
            case EXACT_OB -> fixture.exact().opticalBlackMae();
            case D8_BOUNDARY -> fixture.d8().boundaryMae();
            case EXACT_BOUNDARY -> fixture.exact().boundaryMae();
            case D8_SATURATION -> fixture.d8().saturation();
            case EXACT_SATURATION -> fixture.exact().saturation();
        }).toList();
    }

    private static List<Family> families(List<Fixture> fixtures) {
        Map<String, List<Fixture>> grouped = new LinkedHashMap<>();
        for (Fixture fixture : fixtures) {
            grouped.computeIfAbsent(familyName(fixture.name()), ignored -> new ArrayList<>())
                    .add(fixture);
        }
        return grouped.entrySet().stream().map(entry -> {
            List<Fixture> group = entry.getValue();
            double d8Mae = median(values(group, MetricKind.D8_MAE));
            double exactMae = median(values(group, MetricKind.EXACT_MAE));
            double d8Ob = medianFinite(values(group, MetricKind.D8_OB));
            double exactOb = medianFinite(values(group, MetricKind.EXACT_OB));
            double d8Boundary = medianFinite(values(group, MetricKind.D8_BOUNDARY));
            double exactBoundary = medianFinite(values(group, MetricKind.EXACT_BOUNDARY));
            boolean okay = nonRegressing(exactMae, d8Mae)
                    && nonRegressing(exactOb, d8Ob)
                    && nonRegressing(exactBoundary, d8Boundary);
            return new Family(entry.getKey(), d8Mae, exactMae,
                    d8Ob, exactOb, d8Boundary, exactBoundary, okay);
        }).toList();
    }

    private static String familyName(String fixture) {
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
                || finiteImprovement(fixture.exact().opticalBlackMae(), fixture.d8().opticalBlackMae())
                || finiteImprovement(fixture.exact().boundaryMae(), fixture.d8().boundaryMae());
    }

    private static boolean finiteImprovement(double candidate, double baseline) {
        return Double.isFinite(candidate) && Double.isFinite(baseline) && candidate < baseline;
    }

    private static boolean nonRegressing(double candidate, double baseline) {
        return !Double.isFinite(baseline)
                || (Double.isFinite(candidate) && candidate <= baseline + EPSILON);
    }

    private static double median(List<Double> values) {
        double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
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
}
