package com.photographer.focusstack.camera.strategy;

import com.photographer.focusstack.common.analysis.AnalysisResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AdaptiveStepStrategyTest {

    private final AdaptiveStepStrategy strategy = new AdaptiveStepStrategy();
    private final LinearStepStrategy linear = new LinearStepStrategy();

    @Test
    void emptyHistoryShouldReturnBaseStepSize() {
        assertEquals(5, strategy.nextStepSize(Collections.emptyList(), 5));
        assertEquals(5, strategy.nextStepSize(null, 5));
    }

    @Test
    void singleEntryHistoryShouldReturnBaseStepSize() {
        List<AnalysisResult> history = List.of(result(0.3));
        assertEquals(10, strategy.nextStepSize(history, 10));
    }

    @Test
    void risingSharpnessShouldKeepFullStep() {
        // Sharpness rising from 0.3 → 0.5 (66% increase, well above plateau threshold)
        List<AnalysisResult> history = List.of(result(0.3), result(0.5));
        int step = strategy.nextStepSize(history, 10);
        assertEquals(10, step, "Should keep full step when sharpness is rising quickly");
    }

    @Test
    void plateauingSharpnessShouldHalveStep() {
        // Sharpness barely increasing: 0.50 → 0.51 (2%, below plateau ratio of 5%)
        List<AnalysisResult> history = List.of(result(0.50), result(0.51));
        int step = strategy.nextStepSize(history, 10);
        assertEquals(5, step, "Should halve step near sharpness plateau");
    }

    @Test
    void decreasingSharpnessShouldQuarterStep() {
        // Sharpness falling: 0.8 → 0.5 (past the peak)
        List<AnalysisResult> history = List.of(result(0.8), result(0.5));
        int step = strategy.nextStepSize(history, 8);
        assertEquals(2, step, "Should use quarter step when past the peak");
    }

    @Test
    void stepShouldNeverBeBelowOne() {
        // Even with a tiny base step size and past-peak condition
        List<AnalysisResult> history = List.of(result(0.8), result(0.5));
        int step = strategy.nextStepSize(history, 1);
        assertEquals(1, step, "Step size should never be less than 1");
    }

    @Test
    void linearStrategyAlwaysReturnsBaseStep() {
        List<AnalysisResult> history = List.of(result(0.8), result(0.5));
        assertEquals(7, linear.nextStepSize(history, 7));
        assertEquals(7, linear.nextStepSize(Collections.emptyList(), 7));
    }

    @Test
    void strategyNamesAreCorrect() {
        assertEquals("adaptive", strategy.name());
        assertEquals("linear", linear.name());
    }

    @Test
    void onlyUsesLastTwoEntriesOfHistory() {
        // History with many entries; decision should only look at last two
        List<AnalysisResult> history = new ArrayList<>();
        history.add(result(0.1)); // old
        history.add(result(0.2)); // old
        history.add(result(0.3)); // old
        history.add(result(0.5)); // previous
        history.add(result(0.8)); // latest (still rising)
        int step = strategy.nextStepSize(history, 10);
        assertEquals(10, step, "Should keep full step while sharpness is still rising");
    }

    private AnalysisResult result(double sharpness) {
        return new AnalysisResult("id", sharpness, 0.5, false, 0.0, 0.1, true);
    }
}
