package com.photographer.focusstack.analysis.service;

import com.photographer.focusstack.common.analysis.AnalysisResult;
import com.photographer.focusstack.common.model.QualityMode;
import com.photographer.focusstack.common.model.StackingParameters;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StackingAdvisorTest {

    private final StackingAdvisor advisor = new StackingAdvisor(
            new SharpnessAnalyzer(),
            new MotionDetector(),
            new InformationGainEstimator(),
            new ImageJProcessor(),
            "pixel-diff"  // use pixel-diff strategy for unit tests (no ImageJ file decoding needed)
    );

    @Test
    void firstFrameShouldRecommendContinue() {
        byte[] image = new byte[100];
        for (int i = 0; i < 100; i++) {
            image[i] = (byte) (i % 256);
        }

        StackingParameters params = new StackingParameters(0, 1, 30, 0.01, true, QualityMode.BALANCED);

        AnalysisResult result = advisor.analyzeFrame(image, null, Collections.emptyList(), params);
        assertNotNull(result);
        assertTrue(result.recommendContinue(), "First frame should recommend continue");
    }

    @Test
    void shouldStopWhenMaxFramesReached() {
        byte[] image = new byte[100];
        StackingParameters params = new StackingParameters(0, 1, 2, 0.01, false, QualityMode.BALANCED);

        List<AnalysisResult> history = new ArrayList<>();
        history.add(new AnalysisResult("1", 0.5, 0.3, false, 0.0, 0.5, true));
        history.add(new AnalysisResult("2", 0.6, 0.4, false, 0.0, 0.3, true));

        AnalysisResult result = advisor.analyzeFrame(image, null, history, params);
        assertFalse(result.recommendContinue(), "Should stop when max frames reached");
    }

    @Test
    void shouldStopOnMotionDetected() {
        byte[] frame1 = new byte[100];
        byte[] frame2 = new byte[100];
        java.util.Arrays.fill(frame1, (byte) 0);
        java.util.Arrays.fill(frame2, (byte) 255);

        StackingParameters params = new StackingParameters(0, 1, 30, 0.01, true, QualityMode.BALANCED);

        AnalysisResult result = advisor.analyzeFrame(frame2, frame1, Collections.emptyList(), params);
        assertFalse(result.recommendContinue(), "Should stop when motion detected");
    }
}
