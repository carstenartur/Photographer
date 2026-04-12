package com.photographer.focusstack.camera.strategy;

import com.photographer.focusstack.common.analysis.AnalysisResult;

import java.util.List;

/**
 * Focus-bracketing strategy optimised for macro photography.
 *
 * <p>Macro mode typically operates at very high magnification where depth-of-field
 * is extremely shallow. This strategy starts with fine steps and progressively
 * reduces the step size when the sharpness peak is detected, then extends by
 * one extra step in each direction to guarantee the peak is bracketed.</p>
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li><b>Initial sweep</b> – use the base step size for the first frames.</li>
 *   <li><b>Peak approach</b> – when sharpness rises more slowly (relative gradient
 *       below {@value #APPROACH_THRESHOLD}), switch to fine steps ({@code baseStepSize / 2},
 *       minimum 1).</li>
 *   <li><b>Post-peak overshoot</b> – once sharpness drops, use ultra-fine steps
 *       ({@code baseStepSize / 4}, minimum 1) to bracket the exact peak.</li>
 * </ol>
 */
public class MacroCaptureStrategy implements FocusStepStrategy {

    /** Relative gradient threshold below which steps are halved (approaching peak). */
    static final double APPROACH_THRESHOLD = 0.03;

    /** Minimum step size (always at least 1 focus motor step). */
    private static final int MIN_STEP = 1;

    @Override
    public int nextStepSize(List<AnalysisResult> history, int baseStepSize) {
        if (history == null || history.size() < 2) {
            // Not enough history — use base step
            return baseStepSize;
        }

        AnalysisResult latest = history.get(history.size() - 1);
        AnalysisResult previous = history.get(history.size() - 2);

        double current = latest.sharpnessScore();
        double prev = previous.sharpnessScore();

        if (prev <= 0) {
            return baseStepSize;
        }

        double gradient = current - prev;
        double relativeGradient = gradient / prev;

        if (gradient < 0) {
            // Past the peak — ultra-fine steps
            return Math.max(MIN_STEP, baseStepSize / 4);
        } else if (relativeGradient < APPROACH_THRESHOLD) {
            // Approaching peak — fine steps
            return Math.max(MIN_STEP, baseStepSize / 2);
        }
        // Still climbing — full base step
        return baseStepSize;
    }

    @Override
    public String name() {
        return "macro";
    }
}
