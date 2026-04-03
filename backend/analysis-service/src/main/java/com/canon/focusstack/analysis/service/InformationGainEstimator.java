package com.canon.focusstack.analysis.service;

import com.canon.focusstack.common.analysis.AnalysisResult;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class InformationGainEstimator {

    /**
     * Estimates how much new information the latest frame adds compared to previous results.
     * @param previousResults list of previous analysis results
     * @param current         the current analysis result
     * @return gain score 0.0 - 1.0
     */
    public double estimateGain(List<AnalysisResult> previousResults, AnalysisResult current) {
        if (previousResults == null || previousResults.isEmpty()) {
            return 1.0;
        }

        double currentSharpness = current.sharpnessScore();
        double currentRegion = current.sharpRegionPercentage();

        double bestPrevSharpness = 0.0;
        double totalCoveredRegion = 0.0;
        for (AnalysisResult prev : previousResults) {
            bestPrevSharpness = Math.max(bestPrevSharpness, prev.sharpnessScore());
            totalCoveredRegion = Math.min(1.0, totalCoveredRegion + prev.sharpRegionPercentage());
        }

        // Sharpness improvement component
        double sharpnessGain = Math.max(0.0, currentSharpness - bestPrevSharpness);

        // New region coverage component
        double regionGain = Math.max(0.0, currentRegion - totalCoveredRegion);

        // Diminishing returns based on number of frames
        double diminishingFactor = 1.0 / (1.0 + previousResults.size() * 0.1);

        double gain = (0.5 * sharpnessGain + 0.5 * regionGain + 0.2 * diminishingFactor);
        return Math.max(0.0, Math.min(1.0, gain));
    }
}
