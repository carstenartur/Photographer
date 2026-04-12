package com.photographer.focusstack.camera.strategy;

import com.photographer.focusstack.common.analysis.AnalysisResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MacroCaptureStrategyTest {

    private final MacroCaptureStrategy strategy = new MacroCaptureStrategy();

    @Test
    void emptyHistoryReturnsBaseStep() {
        assertEquals(8, strategy.nextStepSize(Collections.emptyList(), 8));
        assertEquals(8, strategy.nextStepSize(null, 8));
    }

    @Test
    void singleEntryReturnsBaseStep() {
        assertEquals(6, strategy.nextStepSize(List.of(result(0.5)), 6));
    }

    @Test
    void risingSharpnessKeepsFullStep() {
        // 0.3 → 0.6 is a large rise (100%), well above the approach threshold
        List<AnalysisResult> history = List.of(result(0.3), result(0.6));
        assertEquals(8, strategy.nextStepSize(history, 8));
    }

    @Test
    void approachingPeakHalvesStep() {
        // 0.50 → 0.51 is a 2% rise, below APPROACH_THRESHOLD (3%)
        List<AnalysisResult> history = List.of(result(0.50), result(0.51));
        assertEquals(4, strategy.nextStepSize(history, 8));
    }

    @Test
    void pastPeakQuartersStep() {
        // 0.8 → 0.5 sharpness is dropping
        List<AnalysisResult> history = List.of(result(0.8), result(0.5));
        assertEquals(2, strategy.nextStepSize(history, 8));
    }

    @Test
    void minimumStepIsOne() {
        // With very small base step and post-peak condition
        List<AnalysisResult> history = List.of(result(0.8), result(0.5));
        assertEquals(1, strategy.nextStepSize(history, 2));
        assertEquals(1, strategy.nextStepSize(history, 1));
    }

    @Test
    void zeroBaseStepReturnsMinimum() {
        // Edge case: base step of 0 (shouldn't happen, but handle gracefully)
        List<AnalysisResult> history = List.of(result(0.3), result(0.6));
        assertEquals(0, strategy.nextStepSize(history, 0));
    }

    @Test
    void zeroPreviousSharpnessReturnsBaseStep() {
        List<AnalysisResult> history = List.of(result(0.0), result(0.5));
        assertEquals(8, strategy.nextStepSize(history, 8));
    }

    @Test
    void onlyUsesLastTwoEntries() {
        List<AnalysisResult> history = new ArrayList<>();
        history.add(result(0.9));
        history.add(result(0.1));
        history.add(result(0.3));
        history.add(result(0.6)); // last two: 0.3 → 0.6 = big rise
        int step = strategy.nextStepSize(history, 8);
        assertEquals(8, step, "Should use full step when sharpness rises between last two");
    }

    @Test
    void nameIsMacro() {
        assertEquals("macro", strategy.name());
    }

    @Test
    void finerThanAdaptiveNearPeak() {
        // Macro uses APPROACH_THRESHOLD of 0.03 vs Adaptive's 0.05
        // A gradient of 3.5% should trigger approach mode in adaptive but not macro
        List<AnalysisResult> history = List.of(result(1.0), result(1.035));
        AdaptiveStepStrategy adaptive = new AdaptiveStepStrategy();
        int macroStep = strategy.nextStepSize(history, 8);
        int adaptiveStep = adaptive.nextStepSize(history, 8);

        // Macro: 3.5% > 3% threshold → full step; Adaptive: 3.5% < 5% threshold → halved
        assertEquals(8, macroStep, "Macro should keep full step at 3.5% gradient");
        assertEquals(4, adaptiveStep, "Adaptive should halve step at 3.5% gradient");
    }

    private AnalysisResult result(double sharpness) {
        return new AnalysisResult("id", sharpness, 0.5, false, 0.0, 0.1, true);
    }
}
