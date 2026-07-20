package com.photographer.focusstack.cr3.research;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExactBracketKReportTest {

    @Test
    void supportsWhenTwoFixturesImproveAndAllGatesPass() {
        List<ExactBracketKReport.Fixture> fixtures = List.of(
                fixture("eosr-iso-low.cr3", 100, 90, 100, 90,
                        100, 90, 1.0, 1.0, false, false, 12),
                fixture("eosr-iso-mid.cr3", 120, 110, 120, 110,
                        120, 110, 1.0, 1.0, false, false, 15),
                fixture("research/g5xm2-dark.cr3", 200, 190, 200, 190,
                        200, 190, 1.0, 1.0, false, false, 8),
                fixture("sample-wide.cr3", 180, 180, 180, 180,
                        180, 180, 1.0, 1.0, false, false, 4));

        ExactBracketKReport.Verdict verdict = ExactBracketKReport.verdict(fixtures);

        assertThat(verdict.state()).isEqualTo("SUPPORTED");
        assertThat(verdict.improvedFixtures()).isEqualTo(3);
        assertThat(verdict.completedFixtures()).isZero();
        assertThat(verdict.completionOkay()).isTrue();
        assertThat(verdict.corpusGate()).isTrue();
        assertThat(verdict.familyGate()).isTrue();
        assertThat(verdict.bracketDifferences()).isEqualTo(39);
    }

    @Test
    void oneIncompleteFixtureBecomingCompleteCanSupport() {
        List<ExactBracketKReport.Fixture> fixtures = List.of(
                fixture("eosr-iso-low.cr3", 100, 100, 100, 100,
                        100, 100, 0.8, 1.0, true, false,
                        12, 0, 0, 2),
                fixture("research/g5xm2-dark.cr3", 200, 200, 200, 200,
                        200, 200, 1.0, 1.0, false, false, 8));

        ExactBracketKReport.Verdict verdict = ExactBracketKReport.verdict(fixtures);

        assertThat(verdict.state()).isEqualTo("SUPPORTED");
        assertThat(verdict.completedFixtures()).isEqualTo(1);
        assertThat(verdict.recoveredStreams()).isEqualTo(2);
    }

    @Test
    void completionRegressionPreventsSupportEvenWithQualityGain() {
        List<ExactBracketKReport.Fixture> fixtures = List.of(
                fixture("eosr-iso-low.cr3", 100, 90, 100, 90,
                        100, 90, 1.0, 0.9, false, true, 10),
                fixture("research/g5xm2-dark.cr3", 200, 190, 200, 190,
                        200, 190, 1.0, 1.0, false, false, 10));

        ExactBracketKReport.Verdict verdict = ExactBracketKReport.verdict(fixtures);

        assertThat(verdict.state()).isEqualTo("PARTIAL");
        assertThat(verdict.completionOkay()).isFalse();
    }

    @Test
    void hiddenTilePlaneRegressionPreventsSupport() {
        List<ExactBracketKReport.Fixture> fixtures = List.of(
                fixture("eosr-iso-low.cr3", 100, 90, 100, 90,
                        100, 90, 1.0, 1.0, false, false,
                        10, 1, 1, 0),
                fixture("research/g5xm2-dark.cr3", 200, 190, 200, 190,
                        200, 190, 1.0, 1.0, false, false, 10));

        ExactBracketKReport.Verdict verdict = ExactBracketKReport.verdict(fixtures);

        assertThat(verdict.state()).isEqualTo("PARTIAL");
        assertThat(verdict.completionOkay()).isFalse();
        assertThat(verdict.regressedStreams()).isEqualTo(1);
        assertThat(verdict.newlyExhaustedStreams()).isEqualTo(1);
    }

    @Test
    void recoveredStreamWithoutCompleteFixtureIsOnlyPartialEvidence() {
        List<ExactBracketKReport.Fixture> fixtures = List.of(
                fixture("eosr-iso-low.cr3", 100, 100, 100, 100,
                        100, 100, 0.7, 0.8, true, true,
                        10, 0, 0, 1),
                fixture("research/g5xm2-dark.cr3", 200, 200, 200, 200,
                        200, 200, 1.0, 1.0, false, false, 10));

        ExactBracketKReport.Verdict verdict = ExactBracketKReport.verdict(fixtures);

        assertThat(verdict.state()).isEqualTo("PARTIAL");
        assertThat(verdict.completedFixtures()).isZero();
        assertThat(verdict.recoveredStreams()).isEqualTo(1);
    }

    @Test
    void cameraFamilyRegressionPreventsSupport() {
        List<ExactBracketKReport.Fixture> fixtures = List.of(
                fixture("eosr-iso-low.cr3", 100, 80, 100, 80,
                        100, 80, 1.0, 1.0, false, false, 10),
                fixture("eosr-iso-mid.cr3", 110, 90, 110, 90,
                        110, 90, 1.0, 1.0, false, false, 10),
                fixture("research/g5xm2-dark.cr3", 200, 230, 200, 230,
                        200, 230, 1.0, 1.0, false, false, 10),
                fixture("sample-wide.cr3", 180, 170, 180, 170,
                        180, 170, 1.0, 1.0, false, false, 10));

        ExactBracketKReport.Verdict verdict = ExactBracketKReport.verdict(fixtures);

        assertThat(verdict.state()).isEqualTo("PARTIAL");
        assertThat(verdict.familyGate()).isFalse();
        assertThat(verdict.families())
                .anySatisfy(family -> {
                    assertThat(family.name()).isEqualTo("G5_X_MARK_II_RESEARCH");
                    assertThat(family.nonRegressing()).isFalse();
                });
    }

    @Test
    void noMeasuredGainIsRejected() {
        List<ExactBracketKReport.Fixture> fixtures = List.of(
                fixture("eosr-iso-low.cr3", 100, 100, 100, 100,
                        100, 100, 1.0, 1.0, false, false, 1),
                fixture("research/g5xm2-dark.cr3", 200, 200, 200, 200,
                        200, 200, 1.0, 1.0, false, false, 1));

        assertThat(ExactBracketKReport.verdict(fixtures).state())
                .isEqualTo("REJECTED");
    }

    @Test
    void jsonContainsPerPlaneAndStreamMetrics() {
        List<ExactBracketKReport.Fixture> fixtures = List.of(
                fixture("eosr-iso-low.cr3", 100, 90, 100, 90,
                        100, 90, 1.0, 1.0, false, false,
                        2, 1, 1, 3));
        ExactBracketKReport.Verdict verdict = ExactBracketKReport.verdict(fixtures);

        assertThat(ExactBracketKReport.json(fixtures, verdict))
                .contains("\"planes\"")
                .contains("\"plane\":0")
                .contains("\"mae\":100.0")
                .contains("\"regressedStreams\":1")
                .contains("\"newlyExhaustedStreams\":1")
                .contains("\"recoveredStreams\":3");
    }

    private static ExactBracketKReport.Fixture fixture(
            String name,
            double d8Mae, double exactMae,
            double d8Ob, double exactOb,
            double d8Boundary, double exactBoundary,
            double d8Completion, double exactCompletion,
            boolean d8Exhausted, boolean exactExhausted,
            long differences) {
        return fixture(name, d8Mae, exactMae, d8Ob, exactOb,
                d8Boundary, exactBoundary, d8Completion, exactCompletion,
                d8Exhausted, exactExhausted, differences, 0, 0, 0);
    }

    private static ExactBracketKReport.Fixture fixture(
            String name,
            double d8Mae, double exactMae,
            double d8Ob, double exactOb,
            double d8Boundary, double exactBoundary,
            double d8Completion, double exactCompletion,
            boolean d8Exhausted, boolean exactExhausted,
            long differences,
            int regressedStreams,
            int newlyExhaustedStreams,
            int recoveredStreams) {
        return new ExactBracketKReport.Fixture(
                name,
                metrics(d8Mae, d8Ob, d8Boundary, d8Completion, d8Exhausted),
                metrics(exactMae, exactOb, exactBoundary, exactCompletion, exactExhausted),
                differences, regressedStreams, newlyExhaustedStreams, recoveredStreams);
    }

    private static KAdaptationPanelMetrics.Result metrics(
            double mae, double ob, double boundary,
            double completion, boolean exhausted) {
        KAdaptationPanelMetrics.Metric metric =
                new KAdaptationPanelMetrics.Metric(mae, mae, 0.5);
        return new KAdaptationPanelMetrics.Result(
                metric, ob, boundary,
                List.of(metric, metric, metric, metric),
                0.0, completion, exhausted);
    }
}
