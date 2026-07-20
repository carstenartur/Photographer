package com.photographer.focusstack.cr3.research;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExactBracketKExhaustionSemanticsTest {

    @Test
    void fullCompletionDoesNotFailGateBecauseOfExhaustedFlagAlone() {
        KAdaptationPanelMetrics.Metric baselineMetric =
                new KAdaptationPanelMetrics.Metric(100.0, 100.0, 0.5);
        KAdaptationPanelMetrics.Metric candidateMetric =
                new KAdaptationPanelMetrics.Metric(90.0, 90.0, 0.6);
        KAdaptationPanelMetrics.Result baseline = new KAdaptationPanelMetrics.Result(
                baselineMetric, 100.0, 100.0,
                List.of(baselineMetric, baselineMetric, baselineMetric, baselineMetric),
                0.0, 1.0, false);
        KAdaptationPanelMetrics.Result candidate = new KAdaptationPanelMetrics.Result(
                candidateMetric, 90.0, 90.0,
                List.of(candidateMetric, candidateMetric, candidateMetric, candidateMetric),
                0.0, 1.0, true);
        ExactBracketKReport.Fixture fixture = new ExactBracketKReport.Fixture(
                "eosr-iso-low.cr3", baseline, candidate,
                10, 0, 0, 0);

        ExactBracketKReport.Verdict verdict =
                ExactBracketKReport.verdict(List.of(fixture));

        assertThat(verdict.completionOkay()).isTrue();
        assertThat(verdict.newlyExhaustedStreams()).isZero();
        assertThat(verdict.state()).isEqualTo("PARTIAL");
    }
}
