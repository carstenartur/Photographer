package com.photographer.focusstack.cr3.research;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReferenceGuidedFirstDivergenceAnalyzerTest {

    @Test
    void mapsTileLocalPlaneCoordinatesToFullSensorBayerCoordinates() {
        var mapping = new ReferenceGuidedFirstDivergenceAnalyzer.PlaneMapping(
                10, 8, 1, 3, 2, 2, 3);

        assertThat(mapping.fullSensorX(0)).isEqualTo(5);
        assertThat(mapping.fullSensorX(1)).isEqualTo(7);
        assertThat(mapping.fullSensorY(0)).isEqualTo(1);
        assertThat(mapping.fullSensorY(2)).isEqualTo(5);
        assertThat(mapping.referenceIndex(1, 2)).isEqualTo(57);
    }

    @Test
    void derivesMedPredictorAndResidualOnlyFromReferencePlaneSamples() {
        var mapping = new ReferenceGuidedFirstDivergenceAnalyzer.PlaneMapping(
                4, 4, 0, 0, 0, 2, 2);
        int[] reference = new int[16];
        reference[mapping.referenceIndex(0, 0)] = 10;
        reference[mapping.referenceIndex(1, 0)] = 12;
        reference[mapping.referenceIndex(0, 1)] = 11;
        reference[mapping.referenceIndex(1, 1)] = 13;

        var first = ReferenceGuidedFirstDivergenceAnalyzer.referenceContext(
                mapping, reference, 0, 0, 0);
        assertThat(first.west()).isNull();
        assertThat(first.north()).isNull();
        assertThat(first.predictor()).isZero();
        assertThat(first.expectedSignedResidual()).isEqualTo(10);
        assertThat(first.expectedUnsignedSymbol()).isEqualTo(20);

        var interior = ReferenceGuidedFirstDivergenceAnalyzer.referenceContext(
                mapping, reference, 1, 1, 0);
        assertThat(interior.west()).isEqualTo(11);
        assertThat(interior.north()).isEqualTo(12);
        assertThat(interior.northWest()).isEqualTo(10);
        assertThat(interior.predictor()).isEqualTo(12);
        assertThat(interior.referenceValue()).isEqualTo(13);
        assertThat(interior.expectedSignedResidual()).isEqualTo(1);
        assertThat(interior.expectedUnsignedSymbol()).isEqualTo(2);
    }

    @Test
    void referenceSeedModeNeutralizesOnlyTheInitialDcMismatch() {
        var mapping = new ReferenceGuidedFirstDivergenceAnalyzer.PlaneMapping(
                2, 2, 0, 0, 0, 1, 1);
        int[] reference = {7, 0, 0, 0};
        byte[] zeroSymbolPayload = {0};

        var baseline = ReferenceGuidedFirstDivergenceAnalyzer.analyzePlane(
                zeroSymbolPayload,
                mapping,
                14,
                reference,
                ReferenceGuidedFirstDivergenceAnalyzer.InitializationMode.CONFIGURED_BASELINE,
                baselineOptions());
        var oracle = ReferenceGuidedFirstDivergenceAnalyzer.analyzePlane(
                zeroSymbolPayload,
                mapping,
                14,
                reference,
                ReferenceGuidedFirstDivergenceAnalyzer.InitializationMode
                        .REFERENCE_FIRST_SAMPLE_ORACLE,
                baselineOptions());

        assertThat(baseline.firstPixelSeed()).isZero();
        assertThat(baseline.classification())
                .isEqualTo(ReferenceGuidedFirstDivergenceAnalyzer.Classification
                        .SYMBOL_OR_RESIDUAL_INTERPRETATION);
        assertThat(baseline.matchedPrefixSymbols()).isZero();
        assertThat(baseline.firstDivergence()).get()
                .extracting(d -> d.trace().ordinal()).isEqualTo(0);

        assertThat(oracle.firstPixelSeed()).isEqualTo(7);
        assertThat(oracle.classification())
                .isEqualTo(ReferenceGuidedFirstDivergenceAnalyzer.Classification.NO_DIVERGENCE);
        assertThat(oracle.matchedPrefixSymbols()).isEqualTo(1);
        assertThat(oracle.firstDivergence()).isEmpty();
    }

    @Test
    void classifiesTheEarliestStageThatContradictsReferenceContext() {
        var reference = new ReferenceGuidedFirstDivergenceAnalyzer.ReferenceContext(
                11, 12, 10, 12, 13, 1, 2);

        var wrongPredictor = trace(11, 2, 1, 13);
        assertThat(ReferenceGuidedFirstDivergenceAnalyzer.classify(
                wrongPredictor, reference, 14))
                .isEqualTo(ReferenceGuidedFirstDivergenceAnalyzer.Classification.PREDICTOR_CONTEXT);

        var wrongSymbol = trace(12, 4, 2, 14);
        assertThat(ReferenceGuidedFirstDivergenceAnalyzer.classify(
                wrongSymbol, reference, 14))
                .isEqualTo(ReferenceGuidedFirstDivergenceAnalyzer.Classification
                        .SYMBOL_OR_RESIDUAL_INTERPRETATION);

        var wrongReconstruction = trace(12, 2, 1, 12);
        assertThat(ReferenceGuidedFirstDivergenceAnalyzer.classify(
                wrongReconstruction, reference, 14))
                .isEqualTo(ReferenceGuidedFirstDivergenceAnalyzer.Classification
                        .CLAMPING_OR_RECONSTRUCTION);

        assertThatThrownBy(() -> ReferenceGuidedFirstDivergenceAnalyzer.classify(
                trace(12, 2, 1, 13), reference, 14))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not diverge");
    }

    @Test
    void rendersStableMarkdownTsvAndJson() {
        var mapping = new ReferenceGuidedFirstDivergenceAnalyzer.PlaneMapping(
                4, 4, 0, 0, 0, 2, 2);
        var reference = new ReferenceGuidedFirstDivergenceAnalyzer.ReferenceContext(
                11, 12, 10, 12, 13, 1, 2);
        var trace = trace(12, 4, 2, 14);
        var divergence = new ReferenceGuidedFirstDivergenceAnalyzer.Divergence(
                ReferenceGuidedFirstDivergenceAnalyzer.Classification
                        .SYMBOL_OR_RESIDUAL_INTERPRETATION,
                1, 1, 2, 2, trace, reference);
        var stream = new ReferenceGuidedFirstDivergenceAnalyzer.AnalysisResult(
                mapping,
                ReferenceGuidedFirstDivergenceAnalyzer.InitializationMode
                        .REFERENCE_FIRST_SAMPLE_ORACLE,
                11,
                divergence.classification(),
                4,
                4,
                false,
                1,
                Optional.of(divergence));
        var fixture = new ReferenceGuidedFirstDivergenceReport.FixtureResult(
                "fixture.cr3", "LibRaw", "test-version", "abc123",
                4, 4, List.of(stream));

        String markdown = ReferenceGuidedFirstDivergenceReport.markdown(List.of(fixture));
        String tsv = ReferenceGuidedFirstDivergenceReport.tsv(List.of(fixture));
        String json = ReferenceGuidedFirstDivergenceReport.json(List.of(fixture));

        assertThat(markdown)
                .contains("LibRaw-guided first CRX reference divergence")
                .contains("REFERENCE_FIRST_SAMPLE_ORACLE")
                .contains("SYMBOL_OR_RESIDUAL_INTERPRETATION")
                .contains("predictor=12");
        assertThat(tsv)
                .startsWith("fixture\treferenceTool")
                .contains("fixture.cr3\tLibRaw\ttest-version")
                .contains("REFERENCE_FIRST_SAMPLE_ORACLE\t11");
        assertThat(json)
                .contains("\"fixture\": \"fixture.cr3\"")
                .contains("\"initializationMode\": \"REFERENCE_FIRST_SAMPLE_ORACLE\"")
                .contains("\"classification\": \"SYMBOL_OR_RESIDUAL_INTERPRETATION\"")
                .contains("\"firstDivergence\": {");
        assertThat(ReferenceGuidedFirstDivergenceReport.json(List.of(fixture)))
                .isEqualTo(json);
    }

    private static CrxNormativeLosslessCandidate.Options baselineOptions() {
        CrxNormativeLosslessCandidate.Options options =
                new CrxNormativeLosslessCandidate.Options();
        options.kInit = 1;
        options.resetKPerRow = true;
        options.unaryOnesUntilZero = true;
        options.signBitMapping = true;
        options.escapeAtUnary15 = false;
        options.firstPixelSeed = 0;
        options.escapeBitWidth = 14;
        options.kAdaptation =
                CrxNormativeLosslessCandidate.KAdaptation.HYBRID_ACCUMULATOR;
        return options;
    }

    private static CrxNormativeLosslessCandidate.SymbolTrace trace(
            int predictor,
            int unsignedSymbol,
            int signedResidual,
            int reconstructed) {
        return new CrxNormativeLosslessCandidate.SymbolTrace(
                3, 1, 1, 20, 24, 1, 1, 0,
                unsignedSymbol, signedResidual, predictor, reconstructed, 1);
    }
}
