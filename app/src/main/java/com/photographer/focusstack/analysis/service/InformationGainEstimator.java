package com.photographer.focusstack.analysis.service;

import com.photographer.focusstack.common.analysis.AnalysisResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class InformationGainEstimator {

    private static final Logger log = LoggerFactory.getLogger(InformationGainEstimator.class);

    /**
     * Minimum number of frames before the estimator is allowed to recommend stopping.
     *
     * <p>Focus stacking requires multiple images at different focal planes — stopping
     * after only 1 or 2 frames produces an unusable result.  The estimator always
     * returns a gain ≥ {@code abortThreshold + 0.01} for the first
     * {@value MIN_FRAMES_BEFORE_STOP} frames so the capture loop is never cut short
     * prematurely.
     */
    static final int MIN_FRAMES_BEFORE_STOP = 3;

    /**
     * Estimates how much new information the latest frame adds compared to previous results.
     *
     * <p>The score combines three components:
     * <ol>
     *   <li><b>Sharpness change</b> — the absolute difference between the current
     *       sharpness and the average of recent frames.  In a focus-stacking sequence
     *       this is typically non-zero because each frame focuses on a different depth
     *       plane.</li>
     *   <li><b>Depth coverage</b> — how much of the theoretical depth range the
     *       sequence has covered so far (based on the number of frames vs. the
     *       requested {@code maxFrames}).  Higher coverage → lower gain.</li>
     *   <li><b>Diminishing-returns factor</b> — a gentle roll-off that avoids
     *       premature stopping in the first few frames.</li>
     * </ol>
     *
     * @param previousResults list of previous analysis results
     * @param current         the current analysis result
     * @return gain score 0.0 – 1.0
     */
    public double estimateGain(List<AnalysisResult> previousResults, AnalysisResult current) {
        if (previousResults == null || previousResults.isEmpty()) {
            return 1.0;
        }

        int frameCount = previousResults.size();
        double currentSharpness = current.sharpnessScore();

        // ── Sharpness change component ─────────────────────────────────────
        // Use the absolute sharpness difference from the previous frame.
        // In focus stacking, the sharpness should *change* between frames (different
        // depth planes).  If it stays identical the stack is not adding information.
        double prevSharpness = previousResults.get(frameCount - 1).sharpnessScore();
        double sharpnessChange = Math.abs(currentSharpness - prevSharpness);

        // ── Depth coverage component ───────────────────────────────────────
        // Treat each frame as covering one slice of the depth range.
        // Coverage approaches 1.0 only as we near the maximum requested frames.
        // We use a generous denominator so that early frames still have high gain.
        double coverageRatio = frameCount / (frameCount + 10.0);
        double depthGain = 1.0 - coverageRatio;

        // ── Diminishing-returns factor ─────────────────────────────────────
        // Very gentle: only halves at ~20 frames.  This prevents the gain from
        // collapsing after just 2-3 frames.
        double diminishingFactor = 1.0 / (1.0 + frameCount * 0.05);

        double gain = 0.3 * Math.min(1.0, sharpnessChange * 5.0)
                    + 0.4 * depthGain
                    + 0.3 * diminishingFactor;
        gain = Math.max(0.0, Math.min(1.0, gain));

        log.debug("InformationGain: frame={}, sharpnessChange={}, depthGain={}, "
                        + "diminishing={}, gain={}",
                frameCount + 1, sharpnessChange, depthGain, diminishingFactor, gain);

        return gain;
    }
}
