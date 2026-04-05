package com.photographer.focusstack.camera.strategy;

import com.photographer.focusstack.common.analysis.AnalysisResult;

import java.util.List;

/**
 * A {@link FocusStepStrategy} that always returns the configured base step size.
 *
 * <p>This is the original linear behaviour and serves as a safe fallback when
 * analysis results are unavailable or the adaptive algorithm is not desired.
 */
public class LinearStepStrategy implements FocusStepStrategy {

    @Override
    public int nextStepSize(List<AnalysisResult> history, int baseStepSize) {
        return baseStepSize;
    }

    @Override
    public String name() {
        return "linear";
    }
}
