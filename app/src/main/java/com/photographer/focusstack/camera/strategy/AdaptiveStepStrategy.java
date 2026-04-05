package com.photographer.focusstack.camera.strategy;

import com.photographer.focusstack.common.analysis.AnalysisResult;

import java.util.List;

/**
 * A gradient-based adaptive {@link FocusStepStrategy}.
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li><b>Far from peak</b> – sharpness is still increasing → keep full step size.</li>
 *   <li><b>Approaching peak</b> – sharpness gradient is slowing → halve the step.</li>
 *   <li><b>Past peak</b> – sharpness is decreasing → use a quarter step to find the
 *       exact peak and stay near it.</li>
 * </ol>
 *
 * <p>The minimum step size is 1 regardless of the multiplier.
 */
public class AdaptiveStepStrategy implements FocusStepStrategy {

    /**
     * Gradient ratio below which we consider sharpness to be plateauing.
     * When {@code gradient / prevSharpness < PLATEAU_RATIO}, steps are halved.
     */
    private static final double PLATEAU_RATIO = 0.05;

    /**
     * Step multiplier applied when sharpness is decreasing after the peak.
     */
    private static final double POST_PEAK_MULTIPLIER = 0.25;

    /**
     * Step multiplier applied when sharpness is plateauing (near the peak).
     */
    private static final double NEAR_PEAK_MULTIPLIER = 0.5;

    @Override
    public int nextStepSize(List<AnalysisResult> history, int baseStepSize) {
        if (history == null || history.size() < 2) {
            // Not enough data yet; use full step size
            return baseStepSize;
        }

        AnalysisResult latest = history.get(history.size() - 1);
        AnalysisResult previous = history.get(history.size() - 2);

        double currentSharpness = latest.sharpnessScore();
        double previousSharpness = previous.sharpnessScore();

        if (previousSharpness <= 0) {
            return baseStepSize;
        }

        double gradient = currentSharpness - previousSharpness;
        double relativeGradient = gradient / previousSharpness;

        double multiplier;
        if (gradient < 0) {
            // Sharpness is falling — we have passed the peak; narrow steps significantly
            multiplier = POST_PEAK_MULTIPLIER;
        } else if (relativeGradient < PLATEAU_RATIO) {
            // Sharpness is barely increasing — we are near the plateau/peak; narrow steps
            multiplier = NEAR_PEAK_MULTIPLIER;
        } else {
            // Sharpness is still rising noticeably — keep full steps
            multiplier = 1.0;
        }

        int adaptedStep = (int) Math.max(1, Math.round(baseStepSize * multiplier));
        return adaptedStep;
    }

    @Override
    public String name() {
        return "adaptive";
    }
}
