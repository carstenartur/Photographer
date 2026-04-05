package com.photographer.focusstack.camera.strategy;

import com.photographer.focusstack.common.analysis.AnalysisResult;

import java.util.List;

/**
 * Strategy interface for computing the next focus step size during a stacking sequence.
 *
 * <p>Implementations can use the history of analysis results to decide whether to keep
 * large steps (when far from the sharpness peak) or narrow them (when approaching or
 * past the peak).
 */
public interface FocusStepStrategy {

    /**
     * Returns the number of focus steps to move before capturing the next frame.
     *
     * @param history       analysis results collected so far (may be empty for the first frame)
     * @param baseStepSize  the configured base step size from {@code StackingParameters}
     * @return the step size to use for the next focus move (always ≥ 1)
     */
    int nextStepSize(List<AnalysisResult> history, int baseStepSize);

    /** Human-readable name of this strategy (for logging and configuration). */
    String name();
}
